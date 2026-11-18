package com.ocarinaengine.client.animation;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Context-sensitive Zelda: Ocarina of Time 3D inspired idle animation system.
 *
 * Uses second-order critically-damped springs per bone channel (natural acceleration,
 * follow-through, and inertia), anatomically coupled shoulder/hip pivots, layered
 * asymmetric breathing, and non-periodic randomized fidgets so animations feel
 * fluid and alive rather than robotic or cyclical.
 */
public final class ZeldaIdleSystem {
	public enum IdleScenario {
		NEUTRAL,
		LOW_HEALTH,
		HOSTILE_ALERT,
		CLIFF_EDGE,
		COLD,
		HOT,
		RAIN
	}

	private enum NeutralFidget {
		NONE,
		LOOK_AROUND,
		BELT_ADJUST,
		STRETCH_YAWN,
		WEIGHT_SHIFT
	}

	private enum HotAction {
		NONE,
		WIPE_BROW,
		FAN_FACE
	}

	/** Target bone angles & torso drop for one pose evaluation. */
	private static final class Pose {
		float headX, headY, headZ;
		float bodyX, bodyY, bodyZ;
		float bodyPosY;
		float rArmX, rArmY, rArmZ;
		float lArmX, lArmY, lArmZ;
		float rLegX, rLegY, rLegZ;
		float lLegX, lLegY, lLegZ;
	}

	/** Second-order critically-damped spring state for every bone channel. */
	private static final class SpringPose {
		final float[] v = new float[19];
		final float[] vel = new float[19];

		void snapTo(Pose p) {
			readPose(p, v);
			for (int i = 0; i < vel.length; i++) {
				vel[i] = 0.0f;
			}
		}

		void step(Pose target, float headOmega, float bodyOmega, float armOmega, float legOmega, float dt) {
			float[] t = new float[19];
			readPose(target, t);
			for (int i = 0; i < 3; i++) stepChannel(i, t[i], headOmega, dt);
			for (int i = 3; i < 7; i++) stepChannel(i, t[i], bodyOmega, dt);
			for (int i = 7; i < 13; i++) stepChannel(i, t[i], armOmega, dt);
			for (int i = 13; i < 19; i++) stepChannel(i, t[i], legOmega, dt);
		}

		private void stepChannel(int i, float target, float omega, float dt) {
			float diff = v[i] - target;
			float j = vel[i] + omega * diff;
			float e = (float) Math.exp(-omega * dt);
			v[i] = target + (diff + j * dt) * e;
			vel[i] = (vel[i] - j * omega * dt) * e;
		}

		void writeTo(Pose out) {
			out.headX = v[0]; out.headY = v[1]; out.headZ = v[2];
			out.bodyX = v[3]; out.bodyY = v[4]; out.bodyZ = v[5];
			out.bodyPosY = v[6];
			out.rArmX = v[7]; out.rArmY = v[8]; out.rArmZ = v[9];
			out.lArmX = v[10]; out.lArmY = v[11]; out.lArmZ = v[12];
			out.rLegX = v[13]; out.rLegY = v[14]; out.rLegZ = v[15];
			out.lLegX = v[16]; out.lLegY = v[17]; out.lLegZ = v[18];
		}

		private static void readPose(Pose p, float[] arr) {
			arr[0] = p.headX; arr[1] = p.headY; arr[2] = p.headZ;
			arr[3] = p.bodyX; arr[4] = p.bodyY; arr[5] = p.bodyZ;
			arr[6] = p.bodyPosY;
			arr[7] = p.rArmX; arr[8] = p.rArmY; arr[9] = p.rArmZ;
			arr[10] = p.lArmX; arr[11] = p.lArmY; arr[12] = p.lArmZ;
			arr[13] = p.rLegX; arr[14] = p.rLegY; arr[15] = p.rLegZ;
			arr[16] = p.lLegX; arr[17] = p.lLegY; arr[18] = p.lLegZ;
		}
	}

	// In-world idle state.
	private static final SpringPose worldSpring = new SpringPose();
	private static final Pose worldTarget = new Pose();
	private static final Pose worldResolved = new Pose();
	private static boolean worldInitialized = false;
	private static float idleWeight = 0.0f;
	private static float idleClock = 0.0f;
	private static long lastWorldNanos = 0L;

	// Equipment menu preview idle state.
	private static final SpringPose menuSpring = new SpringPose();
	private static final Pose menuTarget = new Pose();
	private static final Pose menuResolved = new Pose();
	private static boolean menuInitialized = false;
	private static float menuClock = 0.0f;
	private static long lastMenuNanos = 0L;
	private static float menuShiftCooldown = 4.2f;
	private static float menuShiftTimer = 0.0f;
	private static float menuShiftDir = 1.0f;

	// Cached world context sensing (refreshed periodically while idle).
	private static long lastSenseNanos = 0L;
	private static IdleScenario activeScenario = IdleScenario.NEUTRAL;
	private static IdleScenario prevScenario = IdleScenario.NEUTRAL;
	private static float targetRelYawRad = 0.0f;
	private static float targetRelPitchRad = 0.0f;

	// Non-periodic randomized fidget state (in-world).
	private static NeutralFidget neutralFidget = NeutralFidget.NONE;
	private static float neutralFidgetTime = 0.0f;
	private static float neutralFidgetDuration = 0.0f;
	private static float neutralNextCooldown = 2.8f;
	private static int neutralSign = 1;
	private static int lastNeutralPick = -1;

	private static boolean coldSneezing = false;
	private static float coldSneezeTime = 0.0f;
	private static float coldSneezeCooldown = 4.8f;

	private static HotAction hotAction = HotAction.NONE;
	private static float hotActionTime = 0.0f;
	private static float hotActionDuration = 0.0f;
	private static float hotActionCooldown = 3.2f;

	private static boolean rainChecking = false;
	private static float rainCheckTime = 0.0f;
	private static float rainCheckCooldown = 3.6f;

	private static float cliffGlanceCooldown = 2.5f;
	private static float cliffGlanceTime = 0.0f;
	private static float cliffGlanceSign = 1.0f;
	private static boolean cliffGlancing = false;

	private static float alertShiftCooldown = 3.8f;
	private static float alertShiftTime = 0.0f;
	private static boolean alertShifting = false;

	private ZeldaIdleSystem() {}

	public static IdleScenario currentScenario() {
		return activeScenario;
	}

	/** Resets in-world idle blend immediately (e.g. when a chest cutscene takes over). */
	public static void resetWorldBlend() {
		idleWeight = 0.0f;
		idleClock = 0.0f;
		worldInitialized = false;
		activeScenario = IdleScenario.NEUTRAL;
		prevScenario = IdleScenario.NEUTRAL;
		resetFidgetTimers();
	}

	public static void applyWorldIdle(HumanoidModel<?> model, LocalPlayer player, float limbSwingAmount) {
		long now = System.nanoTime();
		float dt = lastWorldNanos == 0L ? 0.016f : Mth.clamp((now - lastWorldNanos) / 1.0e9f, 0.0f, 0.05f);
		lastWorldNanos = now;

		boolean isPaused = Minecraft.getInstance().isPaused();
		boolean canIdle = isPlayerIdle(player, limbSwingAmount);

		if (canIdle) {
			idleWeight = damp(idleWeight, 1.0f, 6.5f, dt);
			if (!isPaused) {
				idleClock += dt;
			}
		} else {
			idleWeight = damp(idleWeight, 0.0f, 16.0f, dt);
			if (idleWeight < 0.04f) {
				idleClock = 0.0f;
				resetFidgetTimers();
			}
		}

		if (idleWeight <= 0.002f) {
			if (worldInitialized) {
				resetResidualModelPivots(model);
				worldInitialized = false;
			}
			return;
		}

		if (!isPaused && (now - lastSenseNanos > 65_000_000L || !worldInitialized)) {
			lastSenseNanos = now;
			senseContext(player);
			if (activeScenario != prevScenario) {
				prevScenario = activeScenario;
				resetFidgetTimers();
			}
		}

		if (!isPaused) {
			advanceTimers(dt);
		}

		computeScenarioPose(activeScenario, idleClock, worldTarget);

		if (!worldInitialized) {
			worldSpring.snapTo(worldTarget);
			worldInitialized = true;
		} else if (!isPaused) {
			float headOmega = (activeScenario == IdleScenario.HOSTILE_ALERT || coldSneezing) ? 14.0f : 9.5f;
			float bodyOmega = coldSneezing ? 13.0f : 7.2f;
			float armOmega = (coldSneezing || hotAction == HotAction.WIPE_BROW) ? 13.5f : 8.4f;
			float legOmega = 8.6f;
			worldSpring.step(worldTarget, headOmega, bodyOmega, armOmega, legOmega, dt);
		}

		worldSpring.writeTo(worldResolved);
		blendOntoModel(model, worldResolved, idleWeight, false);
	}

	/**
	 * Applies Link's living Ocarina of Time 3D hero stance to the Equipment menu preview,
	 * preserving mouse-following head tracking while adding smooth spring-damped breathing
	 * and subtle weight shifts even while singleplayer is paused.
	 */
	public static void applyMenuIdle(HumanoidModel<?> model) {
		long now = System.nanoTime();
		float dt = lastMenuNanos == 0L ? 0.016f : Mth.clamp((now - lastMenuNanos) / 1.0e9f, 0.0f, 0.05f);
		lastMenuNanos = now;
		menuClock += dt;

		if (menuShiftTimer > 0.0f) {
			menuShiftTimer = Math.max(0.0f, menuShiftTimer - dt);
		} else {
			menuShiftCooldown -= dt;
			if (menuShiftCooldown <= 0.0f) {
				menuShiftTimer = rand(2.6f, 3.6f);
				menuShiftCooldown = rand(4.5f, 8.5f);
				menuShiftDir = -menuShiftDir;
			}
		}

		float breath = asymBreath(menuClock * 1.48f);
		float armFollow = asymBreath(menuClock * 1.48f - 0.35f);
		float drift = 0.65f * Mth.sin(menuClock * 0.43f + 0.4f) + 0.35f * Mth.sin(menuClock * 0.79f + 1.9f);
		float shiftW = menuShiftTimer > 0.0f ? smoothWindow(1.0f - menuShiftTimer / 3.1f) : 0.0f;

		menuTarget.headX = 0.018f * breath;
		menuTarget.headY = 0.0f;
		menuTarget.headZ = 0.028f * drift + 0.03f * shiftW * menuShiftDir;

		menuTarget.bodyX = 0.022f + 0.018f * breath;
		menuTarget.bodyY = 0.085f + 0.03f * drift + 0.06f * shiftW * menuShiftDir;
		menuTarget.bodyZ = 0.014f * drift + 0.035f * shiftW * menuShiftDir;
		menuTarget.bodyPosY = 0.14f * (1.0f - breath);

		menuTarget.rArmX = -0.10f + 0.028f * armFollow + 0.04f * shiftW;
		menuTarget.rArmY = -0.11f + 0.025f * drift;
		menuTarget.rArmZ = 0.11f + 0.022f * armFollow;

		menuTarget.lArmX = 0.05f - 0.022f * armFollow - 0.03f * shiftW;
		menuTarget.lArmY = 0.12f - 0.025f * drift;
		menuTarget.lArmZ = -0.11f - 0.022f * armFollow;

		menuTarget.rLegX = -0.045f + 0.04f * shiftW * menuShiftDir;
		menuTarget.rLegY = 0.09f;
		menuTarget.rLegZ = 0.028f;

		menuTarget.lLegX = 0.055f - 0.04f * shiftW * menuShiftDir;
		menuTarget.lLegY = -0.06f;
		menuTarget.lLegZ = -0.028f;

		if (!menuInitialized) {
			menuSpring.snapTo(menuTarget);
			menuInitialized = true;
		} else {
			menuSpring.step(menuTarget, 8.0f, 6.8f, 7.6f, 8.0f, dt);
		}
		menuSpring.writeTo(menuResolved);
		blendOntoModel(model, menuResolved, 1.0f, true);
	}

	private static void resetFidgetTimers() {
		neutralFidget = NeutralFidget.NONE;
		neutralFidgetTime = 0.0f;
		neutralNextCooldown = rand(2.2f, 4.8f);
		coldSneezing = false;
		coldSneezeCooldown = rand(3.5f, 7.5f);
		hotAction = HotAction.NONE;
		hotActionCooldown = rand(2.5f, 6.0f);
		rainChecking = false;
		rainCheckCooldown = rand(2.8f, 6.5f);
		cliffGlancing = false;
		cliffGlanceCooldown = rand(2.0f, 4.5f);
		alertShifting = false;
		alertShiftCooldown = rand(3.0f, 6.5f);
	}

	private static void advanceTimers(float dt) {
		switch (activeScenario) {
			case NEUTRAL -> {
				if (neutralFidget != NeutralFidget.NONE) {
					neutralFidgetTime += dt;
					if (neutralFidgetTime >= neutralFidgetDuration) {
						neutralFidget = NeutralFidget.NONE;
						neutralFidgetTime = 0.0f;
						neutralNextCooldown = rand(3.8f, 8.2f);
					}
				} else {
					neutralNextCooldown -= dt;
					if (neutralNextCooldown <= 0.0f) {
						pickNextNeutralFidget();
					}
				}
			}
			case COLD -> {
				if (coldSneezing) {
					coldSneezeTime += dt;
					if (coldSneezeTime >= 2.25f) {
						coldSneezing = false;
						coldSneezeTime = 0.0f;
						coldSneezeCooldown = rand(5.2f, 10.8f);
					}
				} else {
					coldSneezeCooldown -= dt;
					if (coldSneezeCooldown <= 0.0f) {
						coldSneezing = true;
						coldSneezeTime = 0.0f;
					}
				}
			}
			case HOT -> {
				if (hotAction != HotAction.NONE) {
					hotActionTime += dt;
					if (hotActionTime >= hotActionDuration) {
						hotAction = HotAction.NONE;
						hotActionTime = 0.0f;
						hotActionCooldown = rand(4.0f, 8.8f);
					}
				} else {
					hotActionCooldown -= dt;
					if (hotActionCooldown <= 0.0f) {
						hotAction = ThreadLocalRandom.current().nextBoolean() ? HotAction.WIPE_BROW : HotAction.FAN_FACE;
						hotActionTime = 0.0f;
						hotActionDuration = (hotAction == HotAction.WIPE_BROW) ? 2.55f : 2.35f;
					}
				}
			}
			case RAIN -> {
				if (rainChecking) {
					rainCheckTime += dt;
					if (rainCheckTime >= 2.8f) {
						rainChecking = false;
						rainCheckTime = 0.0f;
						rainCheckCooldown = rand(4.5f, 9.5f);
					}
				} else {
					rainCheckCooldown -= dt;
					if (rainCheckCooldown <= 0.0f) {
						rainChecking = true;
						rainCheckTime = 0.0f;
					}
				}
			}
			case CLIFF_EDGE -> {
				if (cliffGlancing) {
					cliffGlanceTime += dt;
					if (cliffGlanceTime >= 2.6f) {
						cliffGlancing = false;
						cliffGlanceTime = 0.0f;
						cliffGlanceCooldown = rand(2.4f, 5.8f);
					}
				} else {
					cliffGlanceCooldown -= dt;
					if (cliffGlanceCooldown <= 0.0f) {
						cliffGlancing = true;
						cliffGlanceTime = 0.0f;
						cliffGlanceSign = ThreadLocalRandom.current().nextBoolean() ? 1.0f : -1.0f;
					}
				}
			}
			case HOSTILE_ALERT -> {
				if (alertShifting) {
					alertShiftTime += dt;
					if (alertShiftTime >= 1.6f) {
						alertShifting = false;
						alertShiftTime = 0.0f;
						alertShiftCooldown = rand(3.5f, 7.2f);
					}
				} else {
					alertShiftCooldown -= dt;
					if (alertShiftCooldown <= 0.0f) {
						alertShifting = true;
						alertShiftTime = 0.0f;
					}
				}
			}
			case LOW_HEALTH -> {}
		}
	}

	private static void pickNextNeutralFidget() {
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		int pick;
		do {
			pick = rng.nextInt(4);
		} while (pick == lastNeutralPick);
		lastNeutralPick = pick;
		neutralSign = rng.nextBoolean() ? 1 : -1;
		neutralFidgetTime = 0.0f;
		switch (pick) {
			case 0 -> {
				neutralFidget = NeutralFidget.LOOK_AROUND;
				neutralFidgetDuration = rand(3.1f, 3.9f);
			}
			case 1 -> {
				neutralFidget = NeutralFidget.BELT_ADJUST;
				neutralFidgetDuration = rand(2.6f, 3.2f);
			}
			case 2 -> {
				neutralFidget = NeutralFidget.STRETCH_YAWN;
				neutralFidgetDuration = 3.3f;
			}
			default -> {
				neutralFidget = NeutralFidget.WEIGHT_SHIFT;
				neutralFidgetDuration = rand(2.4f, 3.0f);
			}
		}
	}

	private static boolean isPlayerIdle(LocalPlayer player, float limbSwingAmount) {
		if (!player.onGround()
				|| player.isInWater()
				|| player.isPassenger()
				|| player.onClimbable()
				|| player.isFallFlying()
				|| player.isUsingItem()
				|| player.swingTime > 0
				|| player.isSleeping()
				|| player.isCrouching()) {
			return false;
		}
		if (player.input != null && (Math.abs(player.input.forwardImpulse) > 0.02f
				|| Math.abs(player.input.leftImpulse) > 0.02f
				|| player.input.jumping)) {
			return false;
		}
		double horizSq = player.getDeltaMovement().horizontalDistanceSqr();
		return horizSq < 0.0012 && limbSwingAmount < 0.08f;
	}

	private static void senseContext(LocalPlayer player) {
		ClientLevel level = player.clientLevel;
		if (level == null) {
			activeScenario = IdleScenario.NEUTRAL;
			return;
		}

		// 1) Critical low health (<= 2 hearts).
		if (player.getHealth() <= 4.0f) {
			activeScenario = IdleScenario.LOW_HEALTH;
			targetRelYawRad = 0.0f;
			targetRelPitchRad = 0.0f;
			return;
		}

		// 2) Hostile monster nearby with line of sight.
		Mob nearestEnemy = findNearestHostile(level, player);
		if (nearestEnemy != null) {
			activeScenario = IdleScenario.HOSTILE_ALERT;
			double dx = nearestEnemy.getX() - player.getX();
			double dz = nearestEnemy.getZ() - player.getZ();
			double dy = nearestEnemy.getEyeY() - player.getEyeY();
			double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
			float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float relYaw = Mth.wrapDegrees(worldYaw - player.yBodyRot);
			targetRelYawRad = Mth.clamp(relYaw * Mth.DEG_TO_RAD, -1.15f, 1.15f);
			targetRelPitchRad = Mth.clamp((float) Math.atan2(-dy, horiz), -0.60f, 0.60f);
			return;
		}

		// 3) Precipice / mountain cliff edge check (>= 4-block drop right beside the player).
		Float cliffYaw = findCliffEdgeYaw(level, player);
		if (cliffYaw != null) {
			activeScenario = IdleScenario.CLIFF_EDGE;
			float relYaw = Mth.wrapDegrees(cliffYaw - player.yBodyRot);
			targetRelYawRad = Mth.clamp(relYaw * Mth.DEG_TO_RAD, -1.15f, 1.15f);
			targetRelPitchRad = 0.58f;
			return;
		}

		// 4) Freezing cold biome / snow / ice.
		BlockPos pos = player.blockPosition();
		Biome biome = level.getBiome(pos).value();
		BlockState under = level.getBlockState(pos.below());
		BlockState atFeet = level.getBlockState(pos);
		boolean coldBiome = biome.coldEnoughToSnow(pos);
		boolean icySurface = under.is(Blocks.SNOW) || under.is(Blocks.SNOW_BLOCK)
				|| under.is(Blocks.ICE) || under.is(Blocks.PACKED_ICE) || under.is(Blocks.BLUE_ICE)
				|| under.is(Blocks.POWDER_SNOW) || atFeet.is(Blocks.SNOW) || atFeet.is(Blocks.POWDER_SNOW);
		if (player.getTicksFrozen() > 0 || coldBiome || icySurface) {
			activeScenario = IdleScenario.COLD;
			return;
		}

		// 5) Intense heat (Nether, Desert/Badlands, or standing near lava/fire).
		if (level.dimensionType().ultraWarm() || biome.getBaseTemperature() >= 1.8f || isNearHeatSource(level, pos)) {
			activeScenario = IdleScenario.HOT;
			return;
		}

		// 6) Exposed rain.
		if (level.isRainingAt(pos)) {
			activeScenario = IdleScenario.RAIN;
			return;
		}

		activeScenario = IdleScenario.NEUTRAL;
	}

	private static Mob findNearestHostile(ClientLevel level, LocalPlayer player) {
		var list = level.getEntitiesOfClass(
				Mob.class,
				player.getBoundingBox().inflate(9.5, 4.0, 9.5),
				mob -> mob instanceof Enemy && mob.isAlive() && !mob.isInvisible()
		);
		Mob best = null;
		double bestDist = 90.25;
		for (Mob mob : list) {
			double d = mob.distanceToSqr(player);
			if (d < bestDist && player.hasLineOfSight(mob)) {
				bestDist = d;
				best = mob;
			}
		}
		return best;
	}

	private static Float findCliffEdgeYaw(ClientLevel level, LocalPlayer player) {
		BlockPos feetBelow = BlockPos.containing(player.getX(), player.getY() - 0.25, player.getZ());
		if (level.getBlockState(feetBelow).getCollisionShape(level, feetBelow).isEmpty()) {
			return null;
		}

		int bestDrop = 0;
		float bestYaw = 0.0f;
		for (int i = 0; i < 8; i++) {
			double angle = i * (Math.PI / 4.0);
			double dx = -Math.sin(angle);
			double dz = Math.cos(angle);
			int drop = Math.max(
					measureDrop(level, player.getX() + dx * 0.78, player.getY(), player.getZ() + dz * 0.78),
					measureDrop(level, player.getX() + dx * 1.15, player.getY(), player.getZ() + dz * 1.15)
			);
			if (drop >= 4 && drop > bestDrop) {
				bestDrop = drop;
				bestYaw = (float) Math.toDegrees(angle);
			}
		}
		return bestDrop >= 4 ? bestYaw : null;
	}

	private static int measureDrop(ClientLevel level, double x, double y, double z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int startY = Mth.floor(y - 0.1);
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);

		cursor.set(bx, startY + 1, bz);
		if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
			return 0;
		}

		int drop = 0;
		for (int dy = 0; dy < 8; dy++) {
			cursor.set(bx, startY - dy, bz);
			if (level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
				drop++;
			} else {
				break;
			}
		}
		return drop;
	}

	private static boolean isNearHeatSource(ClientLevel level, BlockPos center) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -2; dy <= 1; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState s = level.getBlockState(m);
					if (s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
							|| s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.MAGMA_BLOCK)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void computeScenarioPose(IdleScenario scenario, float t, Pose p) {
		switch (scenario) {
			case LOW_HEALTH -> buildLowHealthPose(t, p);
			case HOSTILE_ALERT -> buildHostileAlertPose(t, p);
			case CLIFF_EDGE -> buildCliffEdgePose(t, p);
			case COLD -> buildColdPose(t, p);
			case HOT -> buildHotPose(t, p);
			case RAIN -> buildRainPose(t, p);
			case NEUTRAL -> buildNeutralPose(t, p);
		}
	}

	private static void buildLowHealthPose(float t, Pose p) {
		// Heavy asymmetrical panting: sharp inhale, slow exhausted exhale.
		float pant = asymBreath(t * 4.4f);
		float armLag = asymBreath(t * 4.4f - 0.35f);
		float sway = 0.6f * Mth.sin(t * 0.72f) + 0.4f * Mth.sin(t * 1.18f + 1.3f);

		p.headX = 0.30f + 0.055f * pant;
		p.headY = 0.08f * sway;
		p.headZ = 0.045f * sway;

		p.bodyX = 0.36f + 0.045f * pant;
		p.bodyY = 0.04f * sway;
		p.bodyZ = 0.025f * sway;
		p.bodyPosY = 0.92f + 0.24f * (1.0f - pant);

		p.rArmX = -0.44f + 0.055f * armLag;
		p.rArmY = -0.20f;
		p.rArmZ = -0.05f;

		p.lArmX = -0.34f + 0.045f * armLag;
		p.lArmY = 0.16f;
		p.lArmZ = 0.08f;

		p.rLegX = -0.18f;
		p.rLegY = 0.08f;
		p.rLegZ = 0.05f;

		p.lLegX = 0.14f;
		p.lLegY = -0.06f;
		p.lLegZ = -0.04f;
	}

	private static void buildHostileAlertPose(float t, Pose p) {
		float breath = asymBreath(t * 2.8f);
		float guardPulse = alertShifting ? smoothWindow(alertShiftTime / 1.6f) : 0.0f;
		float torsoYaw = Mth.clamp(targetRelYawRad * 0.38f, -0.38f, 0.38f);

		p.headX = targetRelPitchRad + 0.02f * breath;
		p.headY = targetRelYawRad;
		p.headZ = -0.055f * Mth.clamp(targetRelYawRad, -0.55f, 0.55f);

		p.bodyX = 0.14f + 0.020f * breath;
		p.bodyY = torsoYaw;
		p.bodyZ = -0.025f * Mth.clamp(targetRelYawRad, -0.5f, 0.5f);
		p.bodyPosY = 0.38f + 0.08f * (1.0f - breath);

		// Right sword arm poised, left shield arm braced defensively; occasional subtle guard tighten.
		p.rArmX = -0.76f + 0.04f * breath - 0.14f * guardPulse;
		p.rArmY = -0.24f;
		p.rArmZ = 0.17f + 0.04f * guardPulse;

		p.lArmX = -0.94f + 0.03f * breath - 0.18f * guardPulse;
		p.lArmY = 0.48f + 0.10f * guardPulse;
		p.lArmZ = -0.10f;

		p.rLegX = 0.23f;
		p.rLegY = 0.18f;
		p.rLegZ = 0.055f;

		p.lLegX = -0.21f;
		p.lLegY = -0.12f;
		p.lLegZ = -0.055f;
	}

	private static void buildCliffEdgePose(float t, Pose p) {
		float breath = asymBreath(t * 2.1f);
		float drift = 0.6f * Mth.sin(t * 0.85f) + 0.4f * Mth.sin(t * 1.45f + 1.1f);

		// Nervous downward peer with occasional anxious glances over left/right shoulder.
		float lookSweep = 0.0f;
		float lean = 0.0f;
		if (cliffGlancing) {
			float u = cliffGlanceTime / 2.6f;
			lookSweep = 0.36f * cliffGlanceSign * smoothWindow(u);
			lean = 0.05f * cliffGlanceSign * smoothWindow(u);
		}

		p.headX = 0.56f + 0.04f * breath;
		p.headY = Mth.clamp(targetRelYawRad * 0.82f + lookSweep + 0.08f * drift, -1.15f, 1.15f);
		p.headZ = 0.07f * drift + 0.06f * lean;

		p.bodyX = 0.19f + 0.025f * breath;
		p.bodyY = Mth.clamp(targetRelYawRad * 0.35f + 0.15f * lookSweep, -0.40f, 0.40f);
		p.bodyZ = 0.025f * drift + lean;
		p.bodyPosY = 0.24f;

		// Asymmetric cautious edge balance (right arm slightly out, left arm slightly back).
		p.rArmX = -0.24f + 0.05f * drift;
		p.rArmY = -0.10f;
		p.rArmZ = 0.34f + 0.08f * drift + 0.10f * Math.abs(lean);

		p.lArmX = 0.08f - 0.05f * drift;
		p.lArmY = 0.12f;
		p.lArmZ = -0.32f + 0.08f * drift - 0.10f * Math.abs(lean);

		p.rLegX = -0.10f + 0.03f * drift;
		p.rLegY = 0.10f;
		p.rLegZ = 0.06f;

		p.lLegX = 0.12f - 0.03f * drift;
		p.lLegY = -0.08f;
		p.lLegZ = -0.06f;
	}

	private static void buildColdPose(float t, Pose p) {
		float shiverBurst = 0.35f + 0.65f * Mth.clamp(0.5f + 0.5f * Mth.sin(t * 1.9f + 0.7f * Mth.sin(t * 0.6f)), 0.0f, 1.0f);
		float shiver = Mth.sin(t * 27.0f) * shiverBurst;
		float rub = 0.5f * Mth.sin(t * 7.8f) + 0.5f * Mth.sin(t * 4.2f);

		p.headX = 0.18f + 0.02f * Mth.sin(t * 4.5f);
		p.headY = 0.035f * shiver + 0.05f * Mth.sin(t * 0.9f);
		p.headZ = 0.025f * shiver;

		p.bodyX = 0.11f;
		p.bodyY = 0.02f * shiver;
		p.bodyZ = 0.015f * Mth.sin(t * 1.3f);
		p.bodyPosY = 0.32f;

		p.rArmX = -0.78f + 0.06f * rub;
		p.rArmY = -0.58f;
		p.rArmZ = -0.05f + 0.03f * shiver;

		p.lArmX = -0.74f - 0.06f * rub;
		p.lArmY = 0.58f;
		p.lArmZ = 0.05f - 0.03f * shiver;

		p.rLegX = -0.05f;
		p.rLegY = -0.05f;
		p.rLegZ = 0.02f;

		p.lLegX = 0.05f;
		p.lLegY = 0.05f;
		p.lLegZ = -0.02f;

		if (coldSneezing) {
			float st = coldSneezeTime;
			if (st < 0.75f) {
				// Slow inhale anticipation: head tilts back, shoulders rise.
				float u = easeInOut(st / 0.75f);
				p.headX = Mth.lerp(u, p.headX, -0.38f);
				p.bodyX = Mth.lerp(u, p.bodyX, -0.09f);
				p.rArmX = Mth.lerp(u, p.rArmX, -1.02f);
				p.lArmX = Mth.lerp(u, p.lArmX, -1.02f);
			} else if (st < 1.12f) {
				// Sharp sneeze recoil forward!
				float k = (st - 0.75f) / 0.37f;
				float snap = (float) Math.sin(k * Math.PI);
				p.headX = Mth.lerp(snap, -0.15f, 0.58f);
				p.bodyX = Mth.lerp(snap, 0.0f, 0.32f);
				p.rArmX = -0.45f;
				p.rArmZ = 0.28f * snap;
				p.lArmX = -0.45f;
				p.lArmZ = -0.28f * snap;
			} else if (st < 1.85f) {
				// Quick sniffle / wipe nose with right wrist.
				float u = smoothWindow((st - 1.12f) / 0.73f);
				p.rArmX = Mth.lerp(u, p.rArmX, -1.30f);
				p.rArmY = Mth.lerp(u, p.rArmY, -0.46f);
				p.rArmZ = Mth.lerp(u, p.rArmZ, 0.08f);
				p.headX = Mth.lerp(u, p.headX, 0.24f);
			}
		}
	}

	private static void buildHotPose(float t, Pose p) {
		float breath = asymBreath(t * 1.85f);
		float armLag = asymBreath(t * 1.85f - 0.35f);
		float drift = 0.6f * Mth.sin(t * 0.45f) + 0.4f * Mth.sin(t * 0.92f + 1.4f);

		p.headX = 0.09f + 0.03f * breath;
		p.headY = 0.045f * drift;
		p.headZ = 0.03f * drift;

		p.bodyX = 0.07f + 0.022f * breath;
		p.bodyY = 0.055f + 0.025f * drift;
		p.bodyZ = 0.018f * drift;
		p.bodyPosY = 0.24f * (1.0f - breath);

		p.rArmX = -0.06f + 0.025f * armLag;
		p.rArmY = -0.07f;
		p.rArmZ = 0.13f;

		p.lArmX = 0.06f - 0.02f * armLag;
		p.lArmY = 0.09f;
		p.lArmZ = -0.13f;

		p.rLegX = -0.05f;
		p.rLegY = 0.09f;
		p.rLegZ = 0.03f;

		p.lLegX = 0.06f;
		p.lLegY = -0.06f;
		p.lLegZ = -0.03f;

		if (hotAction == HotAction.WIPE_BROW) {
			float u = hotActionTime;
			if (u < 1.80f) {
				float w = smoothWindow(u / 1.80f);
				float sweep = Mth.sin((u / 1.80f) * Mth.PI);
				p.rArmX = Mth.lerp(w, p.rArmX, -1.84f);
				p.rArmY = Mth.lerp(w, p.rArmY, -0.52f + 0.56f * sweep);
				p.rArmZ = Mth.lerp(w, p.rArmZ, 0.22f);
				p.headX = Mth.lerp(w, p.headX, -0.12f);
				p.headY = Mth.lerp(w, p.headY, 0.16f - 0.14f * sweep);
			} else {
				// Quick downward wrist flick off sweat.
				float flick = smoothWindow((u - 1.80f) / 0.75f);
				p.rArmX = Mth.lerp(flick, -0.12f, -0.72f);
				p.rArmZ = Mth.lerp(flick, 0.13f, 0.42f);
			}
		} else if (hotAction == HotAction.FAN_FACE) {
			float w = smoothWindow(hotActionTime / 2.35f);
			float wave = Mth.sin(hotActionTime * 11.5f);
			p.lArmX = Mth.lerp(w, p.lArmX, -1.14f + 0.18f * wave);
			p.lArmY = Mth.lerp(w, p.lArmY, 0.48f);
			p.lArmZ = Mth.lerp(w, p.lArmZ, -0.08f);
			p.headX = Mth.lerp(w, p.headX, -0.04f);
		}
	}

	private static void buildRainPose(float t, Pose p) {
		buildBaseHeroStance(t, p);
		if (rainChecking) {
			float w = smoothWindow(rainCheckTime / 2.8f);
			float scan = Mth.sin(rainCheckTime * 1.8f);
			p.headX = Mth.lerp(w, p.headX, -0.42f);
			p.headY = Mth.lerp(w, p.headY, 0.18f * scan);
			p.rArmX = Mth.lerp(w, p.rArmX, -1.16f);
			p.rArmY = Mth.lerp(w, p.rArmY, -0.22f);
			p.rArmZ = Mth.lerp(w, p.rArmZ, 0.28f);
		}
	}

	private static void buildNeutralPose(float t, Pose p) {
		buildBaseHeroStance(t, p);

		switch (neutralFidget) {
			case LOOK_AROUND -> {
				float u = Mth.clamp(neutralFidgetTime / neutralFidgetDuration, 0.0f, 1.0f);
				// Pause briefly at peak left/right instead of a uniform sine sweep.
				float glanceA = smoothPulse(u, 0.00f, 0.18f, 0.36f, 0.50f);
				float glanceB = smoothPulse(u, 0.46f, 0.62f, 0.80f, 1.00f);
				float yaw = (0.50f * glanceA - 0.42f * glanceB) * neutralSign;
				p.headY += yaw;
				p.headX += -0.07f * (glanceA + glanceB);
				p.headZ += 0.06f * (glanceA - glanceB) * neutralSign;
				p.bodyY += 0.16f * yaw;
			}
			case BELT_ADJUST -> {
				float u = Mth.clamp(neutralFidgetTime / neutralFidgetDuration, 0.0f, 1.0f);
				float w = smoothWindow(u);
				float tap = (float) Math.pow(Math.abs(Math.sin(u * Math.PI * 2.0)), 1.5);
				p.bodyZ = Mth.lerp(w, p.bodyZ, -0.045f * neutralSign);
				p.headX = Mth.lerp(w, p.headX, 0.12f);
				p.rArmX = Mth.lerp(w, p.rArmX, -0.48f);
				p.rArmY = Mth.lerp(w, p.rArmY, -0.34f);
				p.lArmX = Mth.lerp(w, p.lArmX, -0.40f);
				p.lArmY = Mth.lerp(w, p.lArmY, 0.32f);
				if (neutralSign > 0) {
					p.lLegX = Mth.lerp(w, p.lLegX, -0.15f * tap);
				} else {
					p.rLegX = Mth.lerp(w, p.rLegX, -0.15f * tap);
				}
			}
			case STRETCH_YAWN -> {
				float st = neutralFidgetTime;
				if (st < 0.48f) {
					// Anticipation: slight drop before stretching upward.
					float a = easeInOut(st / 0.48f);
					p.bodyX += 0.07f * a;
					p.bodyPosY += 0.25f * a;
					p.rArmX -= 0.18f * a;
					p.lArmX -= 0.18f * a;
				} else if (st < 2.25f) {
					float rise = easeOut(Mth.clamp((st - 0.48f) / 0.72f, 0.0f, 1.0f));
					float yawn = Mth.sin((st - 0.48f) * 2.2f) * 0.03f;
					p.bodyX = Mth.lerp(rise, p.bodyX, -0.10f);
					p.headX = Mth.lerp(rise, p.headX, -0.24f + yawn);
					p.rArmX = Mth.lerp(rise, p.rArmX, -2.44f);
					p.rArmZ = Mth.lerp(rise, p.rArmZ, 0.30f);
					p.lArmX = Mth.lerp(rise, p.lArmX, -2.44f);
					p.lArmZ = Mth.lerp(rise, p.lArmZ, -0.30f);
				} else {
					float settle = easeOut(Mth.clamp((st - 2.25f) / 1.05f, 0.0f, 1.0f));
					p.bodyX = Mth.lerp(settle, -0.10f, p.bodyX);
					p.headX = Mth.lerp(settle, -0.24f, p.headX);
					p.rArmX = Mth.lerp(settle, -2.44f, p.rArmX);
					p.rArmZ = Mth.lerp(settle, 0.30f, p.rArmZ);
					p.lArmX = Mth.lerp(settle, -2.44f, p.lArmX);
					p.lArmZ = Mth.lerp(settle, -0.30f, p.lArmZ);
				}
			}
			case WEIGHT_SHIFT -> {
				float u = Mth.clamp(neutralFidgetTime / neutralFidgetDuration, 0.0f, 1.0f);
				float w = smoothWindow(u);
				p.bodyZ = Mth.lerp(w, p.bodyZ, 0.055f * neutralSign);
				p.bodyY = Mth.lerp(w, p.bodyY, 0.075f + 0.09f * neutralSign);
				p.headZ = Mth.lerp(w, p.headZ, -0.045f * neutralSign);
				p.headX = Mth.lerp(w, p.headX, 0.16f);
				p.rLegX = Mth.lerp(w, p.rLegX, -0.08f * neutralSign);
				p.lLegX = Mth.lerp(w, p.lLegX, 0.08f * neutralSign);
			}
			case NONE -> {}
		}
	}

	private static void buildBaseHeroStance(float t, Pose p) {
		float breath = asymBreath(t * 1.45f);
		float armLag = asymBreath(t * 1.45f - 0.35f);
		float drift = 0.62f * Mth.sin(t * 0.43f + 0.4f) + 0.38f * Mth.sin(t * 0.77f + 1.8f);

		p.headX = 0.022f * breath;
		p.headY = 0.035f * drift;
		p.headZ = 0.026f * drift;

		p.bodyX = 0.022f + 0.018f * breath;
		p.bodyY = 0.080f + 0.028f * drift;
		p.bodyZ = 0.014f * drift;
		p.bodyPosY = 0.14f * (1.0f - breath);

		p.rArmX = -0.08f + 0.028f * armLag;
		p.rArmY = -0.10f + 0.025f * drift;
		p.rArmZ = 0.105f + 0.022f * armLag;

		p.lArmX = 0.045f - 0.022f * armLag;
		p.lArmY = 0.11f - 0.025f * drift;
		p.lArmZ = -0.105f - 0.022f * armLag;

		p.rLegX = -0.045f;
		p.rLegY = 0.085f;
		p.rLegZ = 0.025f;

		p.lLegX = 0.055f;
		p.lLegY = -0.06f;
		p.lLegZ = -0.025f;
	}

	private static void blendOntoModel(HumanoidModel<?> m, Pose p, float w, boolean preserveHeadLook) {
		if (preserveHeadLook) {
			m.head.xRot += p.headX;
			m.head.zRot = p.headZ;
			m.head.y = p.bodyPosY;
		} else {
			m.head.xRot = Mth.lerp(w, m.head.xRot, p.headX);
			m.head.yRot = Mth.lerp(w, m.head.yRot, p.headY);
			m.head.zRot = Mth.lerp(w, m.head.zRot, p.headZ);
			m.head.y = Mth.lerp(w, m.head.y, p.bodyPosY);
		}

		m.body.xRot = Mth.lerp(w, m.body.xRot, p.bodyX);
		m.body.yRot = Mth.lerp(w, m.body.yRot, p.bodyY);
		m.body.zRot = Mth.lerp(w, m.body.zRot, p.bodyZ);
		m.body.y = Mth.lerp(w, m.body.y, p.bodyPosY);

		// Anatomically couple shoulders & hips to torso twist/lean so limbs never detach.
		float bx = m.body.xRot;
		float by = m.body.yRot;
		float bz = m.body.zRot;
		float rShoulderX = -5.0f * Mth.cos(by);
		float rShoulderY = 2.0f * Mth.cos(bx) + m.body.y - 5.0f * Mth.sin(bz);
		float rShoulderZ = 5.0f * Mth.sin(by) + 2.0f * Mth.sin(bx);

		float lShoulderX = 5.0f * Mth.cos(by);
		float lShoulderY = 2.0f * Mth.cos(bx) + m.body.y + 5.0f * Mth.sin(bz);
		float lShoulderZ = -5.0f * Mth.sin(by) + 2.0f * Mth.sin(bx);

		float hipZ = 11.2f * Mth.sin(bx);

		m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, p.rArmX);
		m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, p.rArmY);
		m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, p.rArmZ);
		m.rightArm.x = Mth.lerp(w, -5.0f, rShoulderX);
		m.rightArm.y = Mth.lerp(w, 2.0f, rShoulderY);
		m.rightArm.z = Mth.lerp(w, 0.0f, rShoulderZ);

		m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, p.lArmX);
		m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, p.lArmY);
		m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, p.lArmZ);
		m.leftArm.x = Mth.lerp(w, 5.0f, lShoulderX);
		m.leftArm.y = Mth.lerp(w, 2.0f, lShoulderY);
		m.leftArm.z = Mth.lerp(w, 0.0f, lShoulderZ);

		m.rightLeg.xRot = Mth.lerp(w, m.rightLeg.xRot, p.rLegX);
		m.rightLeg.yRot = Mth.lerp(w, m.rightLeg.yRot, p.rLegY);
		m.rightLeg.zRot = Mth.lerp(w, m.rightLeg.zRot, p.rLegZ);
		m.rightLeg.z = Mth.lerp(w, 0.0f, hipZ + 1.8f * Mth.sin(by));

		m.leftLeg.xRot = Mth.lerp(w, m.leftLeg.xRot, p.lLegX);
		m.leftLeg.yRot = Mth.lerp(w, m.leftLeg.yRot, p.lLegY);
		m.leftLeg.zRot = Mth.lerp(w, m.leftLeg.zRot, p.lLegZ);
		m.leftLeg.z = Mth.lerp(w, 0.0f, hipZ - 1.8f * Mth.sin(by));

		m.hat.copyFrom(m.head);
	}

	private static void resetResidualModelPivots(HumanoidModel<?> m) {
		m.head.zRot = 0.0f;
		m.head.y = 0.0f;
		m.body.zRot = 0.0f;
		m.body.y = 0.0f;
		m.rightArm.x = -5.0f;
		m.rightArm.y = 2.0f;
		m.rightArm.z = 0.0f;
		m.leftArm.x = 5.0f;
		m.leftArm.y = 2.0f;
		m.leftArm.z = 0.0f;
		m.rightLeg.zRot = 0.0f;
		m.rightLeg.z = 0.0f;
		m.leftLeg.zRot = 0.0f;
		m.leftLeg.z = 0.0f;
		m.hat.copyFrom(m.head);
	}

	/** Asymmetric lung curve: slightly brisker inhale, softer resting exhale (0..1). */
	private static float asymBreath(float phaseRad) {
		float raw = 0.5f - 0.5f * Mth.cos(phaseRad);
		return (float) Math.pow(Mth.clamp(raw, 0.0f, 1.0f), 0.72);
	}

	private static float smoothPulse(float u, float t0, float t1, float t2, float t3) {
		if (u <= t0 || u >= t3) return 0.0f;
		if (u < t1) return easeInOut((u - t0) / Math.max(0.001f, t1 - t0));
		if (u <= t2) return 1.0f;
		return 1.0f - easeInOut((u - t2) / Math.max(0.001f, t3 - t2));
	}

	private static float smoothWindow(float u) {
		float x = Mth.clamp(u, 0.0f, 1.0f);
		if (x < 0.24f) {
			return easeInOut(x / 0.24f);
		}
		if (x > 0.76f) {
			return easeInOut((1.0f - x) / 0.24f);
		}
		return 1.0f;
	}

	private static float easeInOut(float t) {
		float x = Mth.clamp(t, 0.0f, 1.0f);
		return x * x * (3.0f - 2.0f * x);
	}

	private static float easeOut(float t) {
		float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
		return 1.0f - u * u * u;
	}

	private static float damp(float current, float target, float speed, float dt) {
		return Mth.lerp(1.0f - (float) Math.exp(-speed * dt), current, target);
	}

	private static float rand(float min, float max) {
		return min + ThreadLocalRandom.current().nextFloat() * (max - min);
	}
}
