package com.ocarinaengine.client.movement;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ocarina of Time Ledge Grab, Shimmy, Climb-Up & Drop controller:
 * - Automatically catches climbable ledges ~1.5 to ~3.2 blocks tall (2-block, 3-block, and fractional slabs/stairs/walls
 *   in between) when jumping or falling toward the ledge face.
 * - Allows smooth lateral shimmying left & right along contiguous edges with automatic corner & obstacle clamping.
 * - Pushing Joystick Up / W (or Jump) smoothly vaults Link onto the top of the ledge;
 *   pushing Joystick Down / S (or Cancel) cleanly lets go with re-grab cooldown.
 */
public final class ZeldaLedgeSystem {
	public enum LedgeState {
		NONE,
		HANGING,
		CLIMBING
	}

	private static final float PLAYER_RADIUS = 0.30f;
	private static final float PLAYER_HEIGHT = 1.80f;

	/** Offset from wall face to player center while hanging (0.30 radius + 0.04 breathing room). */
	private static final double HANG_WALL_OFFSET = 0.34D;
	/** Vertical offset from ledge top surface down to Link's feet while hanging (peeking just over lip). */
	private static final double HANG_TOP_TO_FEET = 1.82D;
	/** Reach window (ledgeTopY - player.y) allowing both 2-block and 3-block apex catches. */
	private static final double MIN_REACH_ABOVE_FEET = 0.45D;
	private static final double MAX_REACH_ABOVE_FEET = 2.18D;
	/** Minimum vertical clearance beneath the ledge lip so 1-block steps never trigger a grab. */
	private static final double MIN_LEDGE_STEP_HEIGHT = 1.48D;
	private static final double MIN_GROUND_CLEARANCE = 1.50D;

	private static final float CATCH_SETTLE_DURATION = 0.18f;
	private static final float HOLD_UP_AUTO_CLIMB_DELAY = 0.30f;
	private static final float CLIMB_DURATION = 0.44f;
	private static final float SHIMMY_SPEED_BPS = 1.65f;
	private static final float DROP_REGRAB_COOLDOWN = 0.35f;

	private static LedgeState state = LedgeState.NONE;
	private static Direction wallDir = Direction.NORTH;
	private static BlockPos ledgeBlockPos = BlockPos.ZERO;
	private static double ledgeTopY = 0.0D;
	private static double wallFaceCoord = 0.0D;
	private static double ledgeHalfDepth = 0.5D;
	private static double hangX = 0.0D;
	private static double hangY = 0.0D;
	private static double hangZ = 0.0D;
	private static float hangYawDeg = 0.0f;

	private static float hangElapsed = 0.0f;
	private static float catchProgress = 0.0f;
	private static boolean enteredWithUpHeld = false;
	private static float dropCooldown = 0.0f;

	// Ground / air tracking for accurate 2..3 block ledge eligibility.
	private static double lastGroundY = 0.0D;
	private static boolean hasGroundRef = false;
	private static float airborneTime = 0.0f;

	// Shimmy animation & audio state (positive = shimmy right, negative = shimmy left).
	private static float inputSx = 0.0f;
	private static float inputSy = 0.0f;
	private static float shimmyInput = 0.0f;
	private static float shimmyVelNorm = 0.0f;
	private static float shimmyPhase = 0.0f;
	private static float prevShimmySoundPhase = 0.0f;
	private static boolean jumpEdgeBuffered = false;

	// Climb-up trajectory anchors.
	private static Vec3 climbStartPos = Vec3.ZERO;
	private static Vec3 climbEndPos = Vec3.ZERO;
	private static float climbElapsed = 0.0f;
	private static float pendingPostClimbCarry = 0.0f;

	private static final class LedgeCandidate {
		final BlockPos blockPos;
		final Direction wallDir;
		final double topY;
		final double faceCoord;
		final double halfDepth;
		final double hangX;
		final double hangY;
		final double hangZ;
		final double climbX;
		final double climbZ;
		final double score;

		LedgeCandidate(BlockPos blockPos, Direction wallDir, double topY, double faceCoord, double halfDepth,
					   double hangX, double hangY, double hangZ, double climbX, double climbZ, double score) {
			this.blockPos = blockPos;
			this.wallDir = wallDir;
			this.topY = topY;
			this.faceCoord = faceCoord;
			this.halfDepth = halfDepth;
			this.hangX = hangX;
			this.hangY = hangY;
			this.hangZ = hangZ;
			this.climbX = climbX;
			this.climbZ = climbZ;
			this.score = score;
		}
	}

	private ZeldaLedgeSystem() {}

	public static void reset() {
		state = LedgeState.NONE;
		hangElapsed = 0.0f;
		catchProgress = 0.0f;
		enteredWithUpHeld = false;
		dropCooldown = 0.0f;
		hasGroundRef = false;
		airborneTime = 0.0f;
		inputSx = 0.0f;
		inputSy = 0.0f;
		shimmyInput = 0.0f;
		shimmyVelNorm = 0.0f;
		shimmyPhase = 0.0f;
		prevShimmySoundPhase = 0.0f;
		jumpEdgeBuffered = false;
		climbElapsed = 0.0f;
		pendingPostClimbCarry = 0.0f;
	}

	public static LedgeState getState() {
		return state;
	}

	public static boolean isActive() {
		return state != LedgeState.NONE;
	}

	public static boolean isHanging() {
		return state == LedgeState.HANGING;
	}

	public static boolean isClimbing() {
		return state == LedgeState.CLIMBING;
	}

	public static float getHangYawDeg() {
		return hangYawDeg;
	}

	public static float getCatchProgress() {
		return catchProgress;
	}

	public static float getShimmyVelNorm() {
		return shimmyVelNorm;
	}

	public static float getShimmyPhase() {
		return shimmyPhase;
	}

	public static float getClimbProgress() {
		return (state == LedgeState.CLIMBING) ? Mth.clamp(climbElapsed / CLIMB_DURATION, 0.0f, 1.0f) : 0.0f;
	}

	/** Records player directional stick & jump edge from KeyboardInputMixin each tick. */
	public static void recordMovementInput(LocalPlayer player, float sx, float sy, boolean jumpEdge) {
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive()
				|| player.isDeadOrDying() || player.isSleeping() || player.isPassenger()) {
			if (isActive()) {
				reset();
			}
			return;
		}

		inputSx = Mth.clamp(sx, -1.0f, 1.0f);
		inputSy = Mth.clamp(sy, -1.0f, 1.0f);

		if (state == LedgeState.NONE) {
			if (player.onGround()) {
				lastGroundY = player.getY();
				hasGroundRef = true;
				airborneTime = 0.0f;
			} else {
				if (!hasGroundRef) {
					lastGroundY = player.getY();
					hasGroundRef = true;
				}
				airborneTime += 0.05f;
			}
			return;
		}

		if (state == LedgeState.HANGING) {
			if (jumpEdge) {
				jumpEdgeBuffered = true;
			}
			// Keep Link squared cleanly toward the wall face while hanging.
			player.setYRot(hangYawDeg);
			player.setYHeadRot(hangYawDeg);
			player.yBodyRot = hangYawDeg;
			if (!player.isSwimming() && !player.isFallFlying()) {
				player.setXRot(0.0f);
			}
			if (enteredWithUpHeld && (inputSy < 0.35f || Math.abs(inputSx) > 0.35f)) {
				enteredWithUpHeld = false;
			}
		} else if (state == LedgeState.CLIMBING) {
			player.setYRot(hangYawDeg);
			player.setYHeadRot(hangYawDeg);
			player.yBodyRot = hangYawDeg;
			if (!player.isSwimming() && !player.isFallFlying()) {
				player.setXRot(0.0f);
			}
		}
	}

	/** Advances smooth render-rate timers & shimmy pose phase from EngineState.frameUpdate(). */
	public static void stepFrame(LocalPlayer player, float dt) {
		if (dt <= 0.0f) {
			return;
		}
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive() || player.isDeadOrDying()) {
			if (isActive()) {
				reset();
			}
			return;
		}

		if (dropCooldown > 0.0f) {
			dropCooldown = Math.max(0.0f, dropCooldown - dt);
		}

		if (state == LedgeState.HANGING) {
			hangElapsed += dt;
			catchProgress = Mth.clamp(hangElapsed / CATCH_SETTLE_DURATION, 0.0f, 1.0f);

			float targetShimmy = 0.0f;
			if (hangElapsed >= 0.08f && Math.abs(inputSx) > 0.22f && Math.abs(inputSx) >= Math.abs(inputSy) * 0.65f) {
				targetShimmy = Mth.clamp(inputSx, -1.0f, 1.0f);
			}
			shimmyInput = targetShimmy;
			float dampAlpha = 1.0f - (float) Math.exp(-16.0f * dt);
			shimmyVelNorm = Mth.lerp(dampAlpha, shimmyVelNorm, targetShimmy);
			if (Math.abs(shimmyVelNorm) < 0.02f && targetShimmy == 0.0f) {
				shimmyVelNorm = 0.0f;
			}
			if (Math.abs(shimmyVelNorm) > 0.03f) {
				shimmyPhase = (shimmyPhase + Math.abs(shimmyVelNorm) * 2.35f * dt) % 1.0f;
			}
		} else if (state == LedgeState.CLIMBING) {
			climbElapsed += dt;
			shimmyVelNorm = Mth.lerp(1.0f - (float) Math.exp(-18.0f * dt), shimmyVelNorm, 0.0f);
		} else {
			shimmyVelNorm = 0.0f;
			catchProgress = 0.0f;
		}
	}

	/** Explicit release request (e.g., Cancel / B button). */
	public static boolean requestDrop(LocalPlayer player) {
		if (state != LedgeState.HANGING || hangElapsed < 0.10f) {
			return false;
		}
		detachDrop(player);
		return true;
	}

	/**
	 * Attempts to catch a nearby climbable ledge while airborne (called each physics tick before travel).
	 * Returns true if a ledge was caught this tick.
	 */
	public static boolean tryGrabLedge(LocalPlayer player) {
		if (state != LedgeState.NONE || dropCooldown > 0.0f) {
			return false;
		}
		if (player == null || player.clientLevel == null || !EngineState.isActive()) {
			return false;
		}
		if (ZeldaChestCutscene.isActive() || ZeldaCombatSystem.isActiveCombat()) {
			return false;
		}
		if (player.onGround() || player.getAbilities().flying || player.isFallFlying()
				|| player.isInWater() || player.isInLava() || player.isSwimming()
				|| player.onClimbable() || player.isPassenger() || player.isSleeping()
				|| player.isDeadOrDying() || player.hurtTime > 0) {
			return false;
		}

		Vec3 vel = player.getDeltaMovement();
		// Wait until Link has left the ground or is descending so takeoff isn't snapped at y == groundY.
		boolean leftGround = (airborneTime >= 0.05f) || (player.getY() - lastGroundY >= 0.16D) || (vel.y <= 0.26D);
		if (!leftGround) {
			return false;
		}
		// Reject excessively steep downward plummets where grabbing a thin edge would feel unnatural.
		if (vel.y < -1.25D) {
			return false;
		}

		// Determine player's desired world travel vector (from stick/WASD relative to camera or facing).
		float moveMag = (float) Math.sqrt(inputSx * inputSx + inputSy * inputSy);
		double moveWorldX = 0.0D;
		double moveWorldZ = 0.0D;
		if (moveMag > 0.18f) {
			float stickAngle = (float) Math.toDegrees(Math.atan2(inputSx, inputSy));
			float worldYawRad = (EngineState.camYaw + stickAngle) * Mth.DEG_TO_RAD;
			moveWorldX = -Mth.sin(worldYawRad);
			moveWorldZ = Mth.cos(worldYawRad);
		}

		float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
		double faceX = -Mth.sin(yawRad);
		double faceZ = Mth.cos(yawRad);

		double minReachY = player.getY() + MIN_REACH_ABOVE_FEET;
		double maxReachY = player.getY() + MAX_REACH_ABOVE_FEET;

		LedgeCandidate best = null;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			double wx = dir.getStepX();
			double wz = dir.getStepZ();
			double faceDot = faceX * wx + faceZ * wz;
			if (faceDot < 0.36D) {
				continue;
			}
			double inputDot = moveMag > 0.18f ? (moveWorldX * wx + moveWorldZ * wz) : 0.0D;
			double velDot = vel.x * wx + vel.z * wz;
			boolean movingToward = inputDot >= 0.20D || velDot >= 0.015D || (player.horizontalCollision && faceDot >= 0.65D);
			if (!movingToward) {
				continue;
			}

			LedgeCandidate cand = findLedgeCandidate(
					player.clientLevel,
					player,
					player.getX(),
					player.getY(),
					player.getZ(),
					dir,
					minReachY,
					maxReachY,
					true
			);
			if (cand != null && (best == null || cand.score < best.score)) {
				best = cand;
			}
		}

		if (best == null) {
			return false;
		}

		beginHang(player, best);
		return true;
	}

	/**
	 * Drives client physics during HANGING and CLIMBING inside LivingEntityMixin.travel().
	 * Returns true when vanilla LivingEntity.travel() should be cancelled.
	 */
	public static boolean handleTravel(LocalPlayer player) {
		if (state == LedgeState.NONE) {
			tryGrabLedge(player);
			if (state == LedgeState.NONE) {
				return false;
			}
		}

		if (player == null || player.clientLevel == null || !EngineState.isActive()
				|| ZeldaChestCutscene.isActive() || player.isDeadOrDying()
				|| player.isInWater() || player.isInLava() || player.hurtTime > 0) {
			reset();
			return false;
		}

		if (state == LedgeState.HANGING) {
			stepHangingTick(player);
			return true;
		}
		if (state == LedgeState.CLIMBING) {
			stepClimbingTick(player);
			return true;
		}
		return false;
	}

	private static void beginHang(LocalPlayer player, LedgeCandidate cand) {
		ZeldaCombatSystem.cancelForLedgeGrab();

		state = LedgeState.HANGING;
		wallDir = cand.wallDir;
		ledgeBlockPos = cand.blockPos;
		ledgeTopY = cand.topY;
		wallFaceCoord = cand.faceCoord;
		ledgeHalfDepth = cand.halfDepth;
		hangX = cand.hangX;
		hangY = cand.hangY;
		hangZ = cand.hangZ;
		hangYawDeg = Mth.wrapDegrees(wallDir.toYRot());

		hangElapsed = 0.0f;
		catchProgress = 0.0f;
		enteredWithUpHeld = (inputSy > 0.45f) && (Math.abs(inputSx) < 0.45f);
		shimmyInput = 0.0f;
		shimmyVelNorm = 0.0f;
		shimmyPhase = 0.0f;
		prevShimmySoundPhase = 0.0f;
		jumpEdgeBuffered = false;

		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		player.setOnGround(false);
		player.setSprinting(false);
		player.setYRot(hangYawDeg);
		player.setYHeadRot(hangYawDeg);
		player.yBodyRot = hangYawDeg;

		Minecraft mc = Minecraft.getInstance();
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.18f));
		BlockState blockState = player.clientLevel.getBlockState(ledgeBlockPos);
		if (!blockState.isAir()) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(blockState.getSoundType().getStepSound(), 1.05f));
		}
	}

	private static void stepHangingTick(LocalPlayer player) {
		ClientLevel level = player.clientLevel;
		if (level == null) {
			reset();
			return;
		}

		// Verify the ledge block under our hands is still valid and headroom above didn't get blocked.
		LedgeCandidate check = findLedgeCandidate(
				level,
				player,
				hangX,
				hangY,
				hangZ,
				wallDir,
				ledgeTopY - 0.35D,
				ledgeTopY + 0.35D,
				false
		);
		if (check == null) {
			detachDrop(player);
			return;
		}

		// 1) Check Drop input (Joystick Down / S).
		boolean downHeld = (inputSy < -0.45f) && (-inputSy >= Math.abs(inputSx) * 0.80f);
		if (hangElapsed >= 0.12f && (downHeld || player.isShiftKeyDown())) {
			detachDrop(player);
			return;
		}

		// 2) Check Climb-Up input (Joystick Up / W or fresh Jump press).
		boolean pureUpHeld = (inputSy > 0.42f) && (inputSy >= Math.abs(inputSx) * 0.85f);
		boolean autoClimbReady = !enteredWithUpHeld || hangElapsed >= HOLD_UP_AUTO_CLIMB_DELAY;
		boolean climbRequested = (hangElapsed >= 0.08f && pureUpHeld && autoClimbReady)
				|| (hangElapsed >= 0.10f && jumpEdgeBuffered);
		jumpEdgeBuffered = false;

		if (climbRequested) {
			// Ensure climb destination on top of the block has standing room.
			AABB topBox = playerAabbAt(check.climbX, check.topY + 0.02D, check.climbZ);
			if (level.noCollision(player, topBox)) {
				beginClimb(player, check);
				return;
			}
		}

		// 3) Lateral Shimmy left/right along the contiguous ledge face.
		float lateralCmd = 0.0f;
		if (hangElapsed >= 0.08f && Math.abs(inputSx) > 0.22f && Math.abs(inputSx) >= Math.abs(inputSy) * 0.65f) {
			lateralCmd = Mth.clamp(inputSx, -1.0f, 1.0f);
		}
		if (Math.abs(lateralCmd) > 0.01f) {
			double rightX = -Mth.cos(hangYawDeg * Mth.DEG_TO_RAD);
			double rightZ = -Mth.sin(hangYawDeg * Mth.DEG_TO_RAD);
			double stepDist = lateralCmd * SHIMMY_SPEED_BPS * 0.05D;

			for (double frac : new double[] {1.0D, 0.5D, 0.25D}) {
				double tryX = hangX + rightX * (stepDist * frac);
				double tryZ = hangZ + rightZ * (stepDist * frac);
				LedgeCandidate nextCand = findLedgeCandidate(
						level,
						player,
						tryX,
						hangY,
						tryZ,
						wallDir,
						ledgeTopY - 0.55D,
						ledgeTopY + 0.55D,
						false
				);
				if (nextCand != null) {
					hangX = nextCand.hangX;
					hangZ = nextCand.hangZ;
					ledgeTopY = nextCand.topY;
					hangY = nextCand.hangY;
					ledgeBlockPos = nextCand.blockPos;
					wallFaceCoord = nextCand.faceCoord;
					ledgeHalfDepth = nextCand.halfDepth;
					break;
				}
			}

			// Hand-over-hand shimmy audio beat every half cycle.
			if (crossedHalfCycle(prevShimmySoundPhase, shimmyPhase)) {
				BlockState blockState = level.getBlockState(ledgeBlockPos);
				if (!blockState.isAir()) {
					Minecraft.getInstance().getSoundManager().play(
							SimpleSoundInstance.forUI(blockState.getSoundType().getStepSound(), 1.30f));
				}
			}
			prevShimmySoundPhase = shimmyPhase;
		}

		// Smoothly move Link toward exact hang anchor each tick (preserves interpolation & collisions).
		double lerpAlpha = (hangElapsed < 0.14f) ? 0.58D : 0.72D;
		double nextX = Mth.lerp(lerpAlpha, player.getX(), hangX);
		double nextY = Mth.lerp(lerpAlpha, player.getY(), hangY);
		double nextZ = Mth.lerp(lerpAlpha, player.getZ(), hangZ);
		Vec3 delta = new Vec3(nextX - player.getX(), nextY - player.getY(), nextZ - player.getZ());

		player.move(MoverType.SELF, delta);
		player.setDeltaMovement(Vec3.ZERO);
		player.setOnGround(false);
		player.resetFallDistance();
		player.setYRot(hangYawDeg);
		player.setYHeadRot(hangYawDeg);
		player.yBodyRot = hangYawDeg;
	}

	private static void beginClimb(LocalPlayer player, LedgeCandidate cand) {
		state = LedgeState.CLIMBING;
		climbElapsed = 0.0f;
		climbStartPos = player.position();
		ledgeTopY = cand.topY;
		climbEndPos = new Vec3(cand.climbX, cand.topY, cand.climbZ);
		pendingPostClimbCarry = (inputSy > 0.25f) ? 0.10f : 0.0f;
		jumpEdgeBuffered = false;

		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		player.setOnGround(false);

		Minecraft.getInstance().getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.96f));
	}

	private static void stepClimbingTick(LocalPlayer player) {
		float u = Mth.clamp(climbElapsed / CLIMB_DURATION, 0.0f, 1.0f);

		double targetX;
		double targetY;
		double targetZ;
		if (u < 0.56f) {
			float vU = easeInOut(u / 0.56f);
			targetX = climbStartPos.x;
			targetZ = climbStartPos.z;
			targetY = Mth.lerp(vU, climbStartPos.y, climbEndPos.y + 0.035D);
		} else {
			float hU = easeInOut((u - 0.56f) / 0.44f);
			targetX = Mth.lerp(hU, climbStartPos.x, climbEndPos.x);
			targetZ = Mth.lerp(hU, climbStartPos.z, climbEndPos.z);
			targetY = Mth.lerp(hU, climbEndPos.y + 0.035D, climbEndPos.y);
		}

		Vec3 delta = new Vec3(targetX - player.getX(), targetY - player.getY(), targetZ - player.getZ());
		player.move(MoverType.SELF, delta);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		player.setYRot(hangYawDeg);
		player.setYHeadRot(hangYawDeg);
		player.yBodyRot = hangYawDeg;

		if (u >= 1.0f) {
			// Finish climb: settle cleanly onto top surface with optional forward stride carry.
			state = LedgeState.NONE;
			climbElapsed = 0.0f;
			lastGroundY = climbEndPos.y;
			hasGroundRef = true;
			airborneTime = 0.0f;
			dropCooldown = 0.16f;

			double fwdX = -Mth.sin(hangYawDeg * Mth.DEG_TO_RAD);
			double fwdZ = Mth.cos(hangYawDeg * Mth.DEG_TO_RAD);
			double carry = (inputSy > 0.25f) ? 0.11D : pendingPostClimbCarry;
			player.setDeltaMovement(fwdX * carry, -0.06D, fwdZ * carry);
			player.setOnGround(true);

			ClientLevel level = player.clientLevel;
			if (level != null) {
				BlockState topState = level.getBlockState(BlockPos.containing(climbEndPos.x, climbEndPos.y - 0.15D, climbEndPos.z));
				if (!topState.isAir()) {
					Minecraft.getInstance().getSoundManager().play(
							SimpleSoundInstance.forUI(topState.getSoundType().getStepSound(), 0.92f));
				}
			}
		}
	}

	private static void detachDrop(LocalPlayer player) {
		state = LedgeState.NONE;
		hangElapsed = 0.0f;
		climbElapsed = 0.0f;
		shimmyVelNorm = 0.0f;
		jumpEdgeBuffered = false;
		dropCooldown = DROP_REGRAB_COOLDOWN;

		if (player != null) {
			double backX = -wallDir.getStepX();
			double backZ = -wallDir.getStepZ();
			player.setDeltaMovement(backX * 0.08D, -0.08D, backZ * 0.08D);
			player.setOnGround(false);
			player.resetFallDistance();
			lastGroundY = player.getY();
			hasGroundRef = true;
			airborneTime = 0.0f;
			Minecraft.getInstance().getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.76f));
		}
	}

	/**
	 * Scans block collision shapes directly ahead of (px, pz) along wallDir within [minTopY, maxTopY].
	 * Supports full blocks, half slabs, stairs, walls, fences, and stacked combinations seamlessly.
	 */
	private static LedgeCandidate findLedgeCandidate(ClientLevel level, LocalPlayer player,
													   double px, double py, double pz,
													   Direction dir, double minTopY, double maxTopY,
													   boolean checkStepHeight) {
		int stepX = dir.getStepX();
		int stepZ = dir.getStepZ();
		int minBy = Mth.floor(minTopY - 0.15D);
		int maxBy = Mth.floor(maxTopY + 0.15D);

		LedgeCandidate best = null;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (double d : new double[] {0.30D, 0.52D, 0.72D}) {
			int bx = Mth.floor(px + stepX * d);
			int bz = Mth.floor(pz + stepZ * d);
			for (int by = maxBy; by >= minBy; by--) {
				cursor.set(bx, by, bz);
				BlockState blockState = level.getBlockState(cursor);
				if (blockState.isAir() || !blockState.getFluidState().isEmpty()) {
					continue;
				}
				VoxelShape shape = blockState.getCollisionShape(level, cursor);
				if (shape.isEmpty()) {
					continue;
				}

				List<AABB> boxes = shape.toAabbs();
				for (AABB local : boxes) {
					AABB box = local.move(bx, by, bz);
					double topY = box.maxY;
					if (topY < minTopY || topY > maxTopY) {
						continue;
					}

					// Lateral overlap check: ledge face must actually span across player's center line.
					if (stepX != 0) {
						if (pz < box.minZ - 0.18D || pz > box.maxZ + 0.18D) {
							continue;
						}
					} else {
						if (px < box.minX - 0.18D || px > box.maxX + 0.18D) {
							continue;
						}
					}

					double faceCoord;
					double backCoord;
					double distToFace;
					if (stepX == 1) {
						faceCoord = box.minX;
						backCoord = box.maxX;
						distToFace = faceCoord - px;
					} else if (stepX == -1) {
						faceCoord = box.maxX;
						backCoord = box.minX;
						distToFace = px - faceCoord;
					} else if (stepZ == 1) {
						faceCoord = box.minZ;
						backCoord = box.maxZ;
						distToFace = faceCoord - pz;
					} else {
						faceCoord = box.maxZ;
						backCoord = box.minZ;
						distToFace = pz - faceCoord;
					}

					if (distToFace < 0.16D || distToFace > 0.70D) {
						continue;
					}

					double depth = Math.abs(backCoord - faceCoord);
					if (depth < 0.12D) {
						continue;
					}
					double halfDepth = depth * 0.5D;
					double intoBlock = Math.min(0.36D, halfDepth);

					double candHangX = (stepX != 0) ? (faceCoord - stepX * HANG_WALL_OFFSET) : px;
					double candHangZ = (stepZ != 0) ? (faceCoord - stepZ * HANG_WALL_OFFSET) : pz;
					double candClimbX = (stepX != 0) ? (faceCoord + stepX * intoBlock) : px;
					double candClimbZ = (stepZ != 0) ? (faceCoord + stepZ * intoBlock) : pz;

					// Verify fluids aren't occupying the hang or climb top spot.
					if (!level.getFluidState(BlockPos.containing(candHangX, topY - 0.5D, candHangZ)).isEmpty()
							|| !level.getFluidState(BlockPos.containing(candClimbX, topY + 0.2D, candClimbZ)).isEmpty()) {
						continue;
					}

					// Verify standing headroom at climb destination on top of the ledge.
					AABB topStandBox = playerAabbAt(candClimbX, topY + 0.02D, candClimbZ);
					if (!level.noCollision(player, topStandBox)) {
						continue;
					}

					// Verify floor clearance under hang position so 1-block hops are never grabbed.
					double floorY = findFloorY(level, candHangX, candHangZ, topY - 0.10D, topY - 3.80D);
					double clearance = Double.isNaN(floorY) ? 3.80D : (topY - floorY);
					if (clearance < MIN_GROUND_CLEARANCE) {
						continue;
					}

					if (checkStepHeight) {
						boolean tallFromJump = (topY - lastGroundY) >= MIN_LEDGE_STEP_HEIGHT;
						boolean caughtInFall = (player.getDeltaMovement().y < 0.05D) && ((topY - py) >= 1.20D);
						if (!tallFromJump && !caughtInFall) {
							continue;
						}
					}

					double targetHangY = topY - HANG_TOP_TO_FEET;
					if (!Double.isNaN(floorY)) {
						targetHangY = Math.max(targetHangY, floorY + 0.08D);
					}

					// Verify unobstructed hang volume directly outside the wall.
					AABB hangBox = new AABB(
							candHangX - 0.27D, targetHangY + 0.04D, candHangZ - 0.27D,
							candHangX + 0.27D, topY - 0.03D, candHangZ + 0.27D
					);
					if (!level.noCollision(player, hangBox)) {
						continue;
					}

					double score = distToFace + Math.abs((topY - py) - 1.65D) * 0.35D;
					if (best == null || score < best.score) {
						best = new LedgeCandidate(
								cursor.immutable(), dir, topY, faceCoord, halfDepth,
								candHangX, targetHangY, candHangZ, candClimbX, candClimbZ, score
						);
					}
				}
			}
		}
		return best;
	}

	private static double findFloorY(ClientLevel level, double x, double z, double startY, double minY) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int maxBy = Mth.floor(startY);
		int minBy = Mth.floor(minY);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		double highest = Double.NaN;

		for (int by = maxBy; by >= minBy; by--) {
			cursor.set(bx, by, bz);
			BlockState s = level.getBlockState(cursor);
			if (s.isAir()) {
				continue;
			}
			VoxelShape shape = s.getCollisionShape(level, cursor);
			if (shape.isEmpty()) {
				continue;
			}
			for (AABB local : shape.toAabbs()) {
				double top = by + local.maxY;
				if (top <= startY + 0.05D && x >= bx + local.minX - 0.25D && x <= bx + local.maxX + 0.25D
						&& z >= bz + local.minZ - 0.25D && z <= bz + local.maxZ + 0.25D) {
					if (Double.isNaN(highest) || top > highest) {
						highest = top;
					}
				}
			}
			if (!Double.isNaN(highest)) {
				return highest;
			}
		}
		return Double.NaN;
	}

	private static AABB playerAabbAt(double x, double bottomY, double z) {
		return new AABB(
				x - PLAYER_RADIUS + 0.01D, bottomY, z - PLAYER_RADIUS + 0.01D,
				x + PLAYER_RADIUS - 0.01D, bottomY + PLAYER_HEIGHT - 0.02D, z + PLAYER_RADIUS - 0.01D
		);
	}

	private static boolean crossedHalfCycle(float prev, float cur) {
		return crossed(prev, cur, 0.05f) || crossed(prev, cur, 0.55f);
	}

	private static boolean crossed(float prev, float cur, float threshold) {
		if (cur >= prev) {
			return prev < threshold && cur >= threshold;
		}
		return threshold > prev || threshold <= cur;
	}

	private static float easeInOut(float t) {
		float x = Mth.clamp(t, 0.0f, 1.0f);
		return x * x * (3.0f - 2.0f * x);
	}
}
