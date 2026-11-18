package com.ocarinaengine.client.combat;

import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import com.ocarinaengine.combat.ZeldaCombatServer;
import com.ocarinaengine.network.ZeldaLandingRollPayload;
import com.ocarinaengine.network.ZeldaShieldStatePayload;
import com.ocarinaengine.network.ZeldaSwordAttackPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Ocarina of Time Switch 2 Remake sword combat & evasion controller:
 * - 3-hit ground combo (Horizontal -> Backhand -> Vertical Finisher) with responsive buffering & dodge-cancels
 * - Z-Target + Forward + Attack: Forward Thrust
 * - Airborne + Attack: Downward Jump Attack cleave + heavy ground slam shockwave
 * - Hold Attack: Mobile crouch-walk Spin Attack charge -> 360-degree Spin Attack
 * - Z-Target + Side/Back + Jump: 360-degree Side-Rolls & Backflips with cushioned landings
 * - Interact (A / Xbox B): Athletic forward Roll with continuous jog handoff
 * - Frame-accurate Hitstop freeze, directional camera recoil/micro-zoom, 3D starburst & crimson enemy blinks
 */
public final class ZeldaCombatSystem {
	public enum SwordMove {
		NONE(0, 0.0f),
		SLASH_HORIZ(ZeldaCombatServer.MOVE_SLASH_HORIZ, 0.40f),
		SLASH_BACKHAND(ZeldaCombatServer.MOVE_SLASH_BACKHAND, 0.40f),
		SLASH_VERTICAL(ZeldaCombatServer.MOVE_SLASH_VERTICAL, 0.54f),
		THRUST(ZeldaCombatServer.MOVE_THRUST, 0.44f),
		JUMP_ATTACK(ZeldaCombatServer.MOVE_JUMP_ATTACK, 0.64f),
		SPIN_ATTACK(ZeldaCombatServer.MOVE_SPIN_ATTACK, 0.56f),
		CHARGING_SPIN(0, 0.0f);

		public final int serverOrdinal;
		public final float duration;

		SwordMove(int serverOrdinal, float duration) {
			this.serverOrdinal = serverOrdinal;
			this.duration = duration;
		}
	}

	public enum DodgeMove {
		NONE(0.0f),
		SIDE_HOP_LEFT(0.56f),
		SIDE_HOP_RIGHT(0.56f),
		BACKFLIP(0.68f),
		ROLL(0.46f),
		SIDE_ROLL_LEFT(0.44f),
		SIDE_ROLL_RIGHT(0.44f);

		public final float duration;

		DodgeMove(float duration) {
			this.duration = duration;
		}
	}

	private static final float SPIN_CHARGE_REQUIRED = 0.42f;
	private static final float HOLD_TO_CHARGE_DELAY = 0.20f;
	private static final float COMBO_CHAIN_WINDOW = 0.42f;
	private static final float JUMP_SLAM_DURATION = 0.34f;
	private static final long BLINK_TOTAL_NANOS = 360_000_000L;
	private static final float MIN_LANDING_ROLL_FALL_BLOCKS = 4.0f;
	private static final float MAX_LANDING_ROLL_FALL_BLOCKS = 20.25f;

	private static SwordMove currentMove = SwordMove.NONE;
	private static float moveElapsed = 0.0f;
	private static float moveYawDeg = 0.0f;
	private static int comboStage = 0;
	private static float comboResetTimer = 0.0f;
	private static boolean queuedNextCombo = false;
	private static float lastInputForward = 0.0f;

	private static boolean queuedRoll = false;
	private static DodgeMove queuedRollMove = DodgeMove.ROLL;
	private static float queuedRollYaw = 0.0f;
	private static boolean queuedDodge = false;
	private static float queuedDodgeSx = 0.0f;
	private static float queuedDodgeSy = 0.0f;

	private static boolean attackHeld = false;
	private static boolean attackWasHeld = false;
	private static float attackHoldTime = 0.0f;
	private static boolean chargeReadyPlayed = false;

	private static boolean jumpAttackDescending = false;
	private static float jumpSlamTimer = 0.0f;
	private static boolean hitSweptThisMove = false;

	private static DodgeMove currentDodge = DodgeMove.NONE;
	private static float dodgeElapsed = 0.0f;
	private static float dodgeYawDeg = 0.0f;
	private static boolean rollFinishFxPlayed = false;
	private static int airAdvanceGraceTicks = 0;
	private static float airAdvanceYawDeg = 0.0f;

	private static float swordDrawnTimer = 0.0f;
	private static boolean swordWasDrawn = false;
	private static boolean manuallySheathed = false;
	private static boolean suppressSpinUntilAttackRelease = false;

	private static final Set<Integer> hitTargetsThisSwing = new HashSet<>();
	private static final Map<Integer, Long> enemyHitAtNanos = new HashMap<>();
	private static long lastStepNanos = 0L;

	// Impact frames (snappy full-game hitstop freeze + directional camera recoil + 3D starburst + crimson blink).
	private static float hitstopTimer = 0.0f;
	private static float hitstopDuration = 0.0f;
	private static long hitstopStartNanos = 0L;
	private static float impactBurstTimer = 0.0f;
	private static float impactBurstDuration = 0.0f;
	private static float impactShakeTimer = 0.0f;
	private static float impactShakeDuration = 0.0f;
	private static float impactShakeStrength = 0.0f;
	private static float impactZoomPush = 0.0f;
	private static SwordMove lastImpactMove = SwordMove.NONE;
	private static float lastImpactCutAngleRad = 0.0f;
	private static Vec3 lastImpactPos = Vec3.ZERO;
	private static boolean lastImpactHeavy = false;
	private static int impactHitSign = 1;

	// Ocarina of Time shield state, Hylian 360-degree crouch-turtle, and block spark burst.
	private static boolean shieldHeld = false;
	private static boolean shieldCrouchTurtling = false;
	private static float shieldReadyTimer = 0.0f;
	private static float shieldImpactTimer = 0.0f;
	private static final float SHIELD_IMPACT_DURATION = 0.22f;
	private static boolean lastSentGuarding = false;
	private static boolean lastSentCrouching = false;
	private static String lastSentShieldId = "";
	private static float shieldBurstTimer = 0.0f;
	private static float shieldBurstDuration = 0.0f;
	private static Vec3 lastShieldBurstPos = Vec3.ZERO;
	private static boolean lastShieldReflect = false;

	private ZeldaCombatSystem() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ZeldaCombatSystem::tick);
	}

	public static void reset() {
		currentMove = SwordMove.NONE;
		moveElapsed = 0.0f;
		comboStage = 0;
		comboResetTimer = 0.0f;
		queuedNextCombo = false;
		queuedRoll = false;
		queuedRollMove = DodgeMove.ROLL;
		queuedDodge = false;
		attackHeld = false;
		attackWasHeld = false;
		attackHoldTime = 0.0f;
		chargeReadyPlayed = false;
		jumpAttackDescending = false;
		jumpSlamTimer = 0.0f;
		currentDodge = DodgeMove.NONE;
		dodgeElapsed = 0.0f;
		rollFinishFxPlayed = false;
		airAdvanceGraceTicks = 0;
		airAdvanceYawDeg = 0.0f;
		swordDrawnTimer = 0.0f;
		swordWasDrawn = false;
		manuallySheathed = false;
		suppressSpinUntilAttackRelease = false;
		hitTargetsThisSwing.clear();
		enemyHitAtNanos.clear();
		lastStepNanos = 0L;
		hitstopTimer = 0.0f;
		hitstopDuration = 0.0f;
		hitstopStartNanos = 0L;
		impactBurstTimer = 0.0f;
		impactBurstDuration = 0.0f;
		impactShakeTimer = 0.0f;
		impactShakeDuration = 0.0f;
		impactShakeStrength = 0.0f;
		impactZoomPush = 0.0f;
		lastImpactMove = SwordMove.NONE;
		lastImpactPos = Vec3.ZERO;
		lastImpactHeavy = false;
		shieldHeld = false;
		shieldCrouchTurtling = false;
		shieldReadyTimer = 0.0f;
		shieldImpactTimer = 0.0f;
		lastSentGuarding = false;
		lastSentCrouching = false;
		lastSentShieldId = "";
		shieldBurstTimer = 0.0f;
		shieldBurstDuration = 0.0f;
		lastShieldBurstPos = Vec3.ZERO;
		lastShieldReflect = false;
		ZeldaBladeTracker.reset();
	}

	/** Clears any lingering charge or queued combat state when Link catches a ledge. */
	public static void cancelForLedgeGrab() {
		currentMove = SwordMove.NONE;
		moveElapsed = 0.0f;
		queuedNextCombo = false;
		queuedRoll = false;
		queuedRollMove = DodgeMove.ROLL;
		queuedDodge = false;
		jumpAttackDescending = false;
		currentDodge = DodgeMove.NONE;
		dodgeElapsed = 0.0f;
		attackHoldTime = 0.0f;
		chargeReadyPlayed = false;
		shieldHeld = false;
		shieldCrouchTurtling = false;
		shieldReadyTimer = 0.0f;
		shieldImpactTimer = 0.0f;
	}

	public static SwordMove getCurrentMove() {
		return currentMove;
	}

	/**
	 * Phase (0..1 of the move) where the blade starts its real cutting arc. Derived from the keyframes in
	 * ZeldaAnimationEngine: the wind-up peak is BEFORE this value, so damage, hitstop and sparks no longer
	 * fire while the arm is still being drawn back.
	 */
	public static float getStrikeStart(SwordMove move) {
		return switch (move) {
			case SLASH_HORIZ -> 0.38f;
			case SLASH_BACKHAND -> 0.42f;
			case SLASH_VERTICAL -> 0.52f;
			case THRUST -> 0.38f;
			case SPIN_ATTACK -> 0.30f;
			case JUMP_ATTACK -> 0.22f;
			default -> 0.22f;
		};
	}

	/** Phase (0..1 of the move) where the cutting arc is over and the blade is only following through. */
	public static float getStrikeEnd(SwordMove move) {
		return switch (move) {
			case SLASH_HORIZ -> 0.72f;
			case SLASH_BACKHAND -> 0.78f;
			case SLASH_VERTICAL -> 0.80f;
			case THRUST -> 0.64f;
			case SPIN_ATTACK -> 0.82f;
			case JUMP_ATTACK -> 1.0f;
			default -> 0.78f;
		};
	}

	public static float getMoveProgress() {
		if (currentMove == SwordMove.NONE || currentMove.duration <= 0.0f) {
			return 0.0f;
		}
		float raw = Mth.clamp(moveElapsed / currentMove.duration, 0.0f, 1.0f);
		if (currentMove == SwordMove.JUMP_ATTACK && jumpAttackDescending) {
			// Hold poised downward plunge in midair; never finish the slam before touching ground.
			return Math.min(raw, 0.78f);
		}
		return raw;
	}

	public static DodgeMove getCurrentDodge() {
		return currentDodge;
	}

	public static float getDodgeProgress() {
		if (currentDodge == DodgeMove.NONE || currentDodge.duration <= 0.0f) {
			return 0.0f;
		}
		return Mth.clamp(dodgeElapsed / currentDodge.duration, 0.0f, 1.0f);
	}

	public static boolean isChargingSpin() {
		return currentMove == SwordMove.CHARGING_SPIN;
	}

	public static boolean isSpinFullyCharged() {
		return currentMove == SwordMove.CHARGING_SPIN && attackHoldTime >= SPIN_CHARGE_REQUIRED;
	}

	public static float getChargeProgress() {
		if (currentMove != SwordMove.CHARGING_SPIN) {
			return 0.0f;
		}
		return Mth.clamp(attackHoldTime / SPIN_CHARGE_REQUIRED, 0.0f, 1.0f);
	}

	public static boolean isJumpSlamming() {
		return jumpSlamTimer > 0.0f;
	}

	public static float getJumpSlamProgress() {
		return jumpSlamTimer > 0.0f ? Mth.clamp(1.0f - jumpSlamTimer / JUMP_SLAM_DURATION, 0.0f, 1.0f) : 0.0f;
	}

	public static boolean isSwinging() {
		return currentMove != SwordMove.NONE && currentMove != SwordMove.CHARGING_SPIN;
	}

	public static boolean isDodging() {
		return currentDodge != DodgeMove.NONE;
	}

	public static boolean isAirDodge() {
		return currentDodge == DodgeMove.SIDE_HOP_LEFT
				|| currentDodge == DodgeMove.SIDE_HOP_RIGHT
				|| currentDodge == DodgeMove.BACKFLIP;
	}

	public static boolean isRolling() {
		return currentDodge == DodgeMove.ROLL
				|| currentDodge == DodgeMove.SIDE_ROLL_LEFT
				|| currentDodge == DodgeMove.SIDE_ROLL_RIGHT;
	}

	public static boolean isSideRolling() {
		return currentDodge == DodgeMove.SIDE_ROLL_LEFT || currentDodge == DodgeMove.SIDE_ROLL_RIGHT;
	}

	public static boolean isActiveCombat() {
		return currentMove != SwordMove.NONE || currentDodge != DodgeMove.NONE || jumpSlamTimer > 0.0f;
	}

	/** Advances frame-accurate impact frame, hitstop, and camera punch timers from EngineState. */
	public static void stepImpactEffects(float dt) {
		if (!OcarinaGameTypeClient.isZelda() || dt <= 0.0f) {
			return;
		}
		if (hitstopTimer > 0.0f) {
			hitstopTimer = Math.max(0.0f, hitstopTimer - dt);
			if (hitstopTimer <= 0.0f) {
				// Prevent the resumed client tick from jumping forward by wall-clock freeze time.
				lastStepNanos = System.nanoTime() - 48_000_000L;
			}
		}
		if (impactBurstTimer > 0.0f) {
			impactBurstTimer = Math.max(0.0f, impactBurstTimer - dt);
		}
		if (impactShakeTimer > 0.0f) {
			impactShakeTimer = Math.max(0.0f, impactShakeTimer - dt);
		}
		if (shieldBurstTimer > 0.0f) {
			shieldBurstTimer = Math.max(0.0f, shieldBurstTimer - dt);
		}
		if (shieldImpactTimer > 0.0f) {
			shieldImpactTimer = Math.max(0.0f, shieldImpactTimer - dt);
		}
	}

	/** Advances combat, dodge, roll, jump-slam, and charge clocks at full 60+ FPS render rate. */
	public static void stepFrameAnimations(float dt) {
		if (dt <= 0.0f) {
			return;
		}
		// Same clamp as the animation engine so the pose clock and the combat clock never drift apart on lag.
		dt = Math.min(dt, 0.05f);
		Minecraft mc = Minecraft.getInstance();
		if (mc.isPaused() || mc.player == null || !EngineState.isActive()
				|| ZeldaChestCutscene.isActive() || ZeldaLedgeSystem.isActive() || mc.player.isDeadOrDying()) {
			return;
		}
		if (isInHitstop()) {
			return;
		}
		if (currentDodge != DodgeMove.NONE) {
			dodgeElapsed += dt;
		}
		if (jumpSlamTimer > 0.0f) {
			jumpSlamTimer = Math.max(0.0f, jumpSlamTimer - dt);
		}
		if (comboResetTimer > 0.0f) {
			comboResetTimer = Math.max(0.0f, comboResetTimer - dt);
		}
		if (!shieldHeld && !ZeldaTargetingSystem.isFocusActive() && shieldReadyTimer > 0.0f) {
			shieldReadyTimer = Math.max(0.0f, shieldReadyTimer - dt);
		}
		if (!ZeldaTargetingSystem.isTargeting() && swordDrawnTimer > 0.0f && currentMove == SwordMove.NONE) {
			swordDrawnTimer = Math.max(0.0f, swordDrawnTimer - dt);
			if (swordDrawnTimer <= 0.0f) {
				swordWasDrawn = false;
			}
		}
		if (currentMove == SwordMove.CHARGING_SPIN && attackHeld && !suppressSpinUntilAttackRelease) {
			attackHoldTime += dt;
		}
		if (isSwinging()) {
			moveElapsed += dt;
			sweepStrikeThisFrame(mc);
		}
	}

	/**
	 * Hit detection runs on the render-frame clock (same clock as the pose), inside the strike window of the
	 * move, so contact, hitstop, shake and sparks line up with the blade instead of waiting for the next
	 * 20 Hz tick and firing during the wind-up.
	 */
	private static void sweepStrikeThisFrame(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || isInHitstop() || !isSwinging()) {
			return;
		}
		if (currentMove == SwordMove.JUMP_ATTACK && (player.isInWater() || player.isInLava() || player.onClimbable())) {
			return;
		}
		float u = getMoveProgress();
		float start = getStrikeStart(currentMove);
		float end = getStrikeEnd(currentMove);
		boolean inSweep = (currentMove == SwordMove.JUMP_ATTACK)
				? (u >= start)
				: ((u >= start && u <= end) || (!hitSweptThisMove && u >= start));
		if (!inSweep) {
			return;
		}
		hitSweptThisMove = true;
		double reach = (currentMove == SwordMove.THRUST) ? 4.30
				: (currentMove == SwordMove.SPIN_ATTACK ? 3.90 : 3.60);
		boolean omnidirectional = (currentMove == SwordMove.SPIN_ATTACK);
		performHitSweep(player, level, currentMove, reach, omnidirectional);
	}

	public static boolean isInHitstop() {
		if (!OcarinaGameTypeClient.isZelda() || hitstopTimer <= 0.0f) {
			return false;
		}
		if (System.nanoTime() - hitstopStartNanos > 150_000_000L) {
			hitstopTimer = 0.0f;
			return false;
		}
		return true;
	}

	/** Fast alternating vibration during hitstop frames to convey blade impact weight. */
	public static float getHitstopJitter() {
		if (!OcarinaGameTypeClient.isZelda() || hitstopTimer <= 0.0f || hitstopDuration <= 1.0e-4f) {
			return 0.0f;
		}
		float env = hitstopTimer / hitstopDuration;
		float wave = Mth.sin((System.nanoTime() % 1_000_000_000L) * 1.1e-7f);
		return wave * env * (lastImpactHeavy ? 1.35f : 1.0f);
	}

	/** Rapid crimson-red damage flash & pulse (0..1) calibrated to the Remake combat video. */
	public static float getEnemyRedBlinkIntensity(int entityId) {
		if (!OcarinaGameTypeClient.isZelda()) {
			return 0.0f;
		}
		Long hitAt = enemyHitAtNanos.get(entityId);
		if (hitAt == null) {
			return 0.0f;
		}
		long elapsed = System.nanoTime() - hitAt;
		if (elapsed < 0L || elapsed >= BLINK_TOTAL_NANOS) {
			enemyHitAtNanos.remove(entityId);
			return 0.0f;
		}
		// Initial solid crimson hold (0..130ms), then 3 crisp red strobe beats up to 360ms.
		if (elapsed < 130_000_000L) {
			return 1.0f;
		}
		long post = elapsed - 130_000_000L;
		float rem = 1.0f - (float) post / (float) (BLINK_TOTAL_NANOS - 130_000_000L);
		long period = 76_000_000L;
		long phase = post % period;
		if (phase >= 42_000_000L) {
			return 0.0f;
		}
		float pulse = Mth.sin((phase / 42_000_000.0f) * Mth.PI);
		return Mth.clamp((0.40f + 0.60f * rem) * (0.60f + 0.40f * pulse), 0.0f, 1.0f);
	}

	public static float getLastImpactCutAngleRad() {
		return lastImpactCutAngleRad;
	}

	/** Progress (0..1) of the 3D hit impact burst, or -1 if inactive. */
	public static float getImpactBurstProgress() {
		if (impactBurstTimer <= 0.0f || impactBurstDuration <= 1.0e-4f) {
			return -1.0f;
		}
		return Mth.clamp(1.0f - (impactBurstTimer / impactBurstDuration), 0.0f, 1.0f);
	}

	public static Vec3 getLastImpactPos() {
		return lastImpactPos;
	}

	public static boolean isLastImpactHeavy() {
		return lastImpactHeavy;
	}

	/** Brief Switch 2 Remake micro-zoom punch toward Link on blade impact (blocks). */
	public static float getImpactCameraZoomPush() {
		if (impactShakeTimer <= 0.0f || impactShakeDuration <= 1.0e-4f || impactZoomPush <= 0.0f) {
			return 0.0f;
		}
		float elapsed = impactShakeDuration - impactShakeTimer;
		float attackRamp = Mth.clamp(elapsed / 0.022f, 0.0f, 1.0f);
		float u = impactShakeTimer / impactShakeDuration;
		return impactZoomPush * attackRamp * u * u;
	}

	public static float getImpactCameraShakeYaw() {
		if (impactShakeTimer <= 0.0f || impactShakeDuration <= 1.0e-4f) {
			return 0.0f;
		}
		float u = impactShakeTimer / impactShakeDuration;
		float t = impactShakeDuration - impactShakeTimer;
		float dirBias = (lastImpactMove == SwordMove.SLASH_BACKHAND) ? -1.0f : 1.0f;
		float horizMult = (lastImpactMove == SwordMove.SLASH_VERTICAL || lastImpactMove == SwordMove.JUMP_ATTACK) ? 0.45f : 1.12f;
		return dirBias * impactHitSign * impactShakeStrength * horizMult * u * u * Mth.sin(t * 92.0f);
	}

	public static float getImpactCameraShakePitch() {
		if (impactShakeTimer <= 0.0f || impactShakeDuration <= 1.0e-4f) {
			return 0.0f;
		}
		float u = impactShakeTimer / impactShakeDuration;
		float t = impactShakeDuration - impactShakeTimer;
		boolean verticalHeavy = (lastImpactMove == SwordMove.SLASH_VERTICAL || lastImpactMove == SwordMove.JUMP_ATTACK);
		float vertMult = verticalHeavy ? 1.25f : 0.58f;
		float kickDown = verticalHeavy ? (1.1f * u * u * (1.0f - Mth.clamp(t / 0.04f, 0.0f, 0.6f))) : 0.0f;
		return kickDown + impactShakeStrength * vertMult * u * u * Mth.cos(t * 108.0f + 0.5f);
	}

	/** True while Link is actively holding his equipped shield in front of him in OoT Guard stance. */
	public static boolean isGuarding() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || !OcarinaGameTypeClient.isZelda() || !ZeldaEquipment.hasEquippedShield()) {
			return false;
		}
		if (ZeldaChestCutscene.isActive() || ZeldaLedgeSystem.isActive() || mc.screen != null) {
			return false;
		}
		if (isSwinging() || isDodging() || isChargingSpin()) {
			return false;
		}
		if (player.isSwimming() || player.isFallFlying() || player.isSleeping() || player.isPassenger() || player.onClimbable()) {
			return false;
		}
		return shieldHeld;
	}

	/** Classic OoT Hylian Shield turtle-crouch when holding Guard while standing still on ground. */
	public static boolean isShieldCrouchTurtling() {
		return isGuarding() && shieldCrouchTurtling && ZeldaEquipment.isHylianShieldEquipped();
	}

	/** In OoT, Link keeps his shield on his left arm while guarding or in sword-drawn combat stance, and on his back when sheathed. */
	public static boolean isShieldVisibleInHand() {
		return OcarinaGameTypeClient.isZelda() && ZeldaEquipment.hasEquippedShield() && !ZeldaLedgeSystem.isActive()
				&& (isGuarding() || isSwordDrawn() || ZeldaTargetingSystem.isFocusActive() || shieldReadyTimer > 0.0f);
	}

	/** Called per tick from ZeldaLockdown to update OoT shield guard state and sync to the server. */
	public static void updateShieldInput(Minecraft mc, boolean heldNow) {
		LocalPlayer player = mc.player;
		boolean valid = player != null && EngineState.isActive() && !ZeldaChestCutscene.isActive()
				&& !ZeldaLedgeSystem.isActive() && mc.screen == null && ZeldaEquipment.hasEquippedShield();
		boolean prevGuarding = shieldHeld;
		shieldHeld = valid && heldNow;
		if (shieldHeld && !prevGuarding && !isSwinging() && !isDodging()) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.15f));
		}
		boolean moving = player != null && isMovementHeld(mc);
		shieldCrouchTurtling = shieldHeld && player != null && player.onGround() && !moving;

		if (isGuarding() || (ZeldaEquipment.hasEquippedShield() && ZeldaTargetingSystem.isFocusActive())) {
			shieldReadyTimer = 3.5f;
		}
		boolean guardingNow = isGuarding();
		boolean crouchingNow = isShieldCrouchTurtling();
		String shieldIdNow = ZeldaEquipment.equippedShieldId();
		if (player != null && mc.getConnection() != null
				&& (guardingNow != lastSentGuarding || crouchingNow != lastSentCrouching || !shieldIdNow.equals(lastSentShieldId))) {
			lastSentGuarding = guardingNow;
			lastSentCrouching = crouchingNow;
			lastSentShieldId = shieldIdNow;
			ClientPlayNetworking.send(new ZeldaShieldStatePayload(guardingNow, shieldIdNow, crouchingNow));
		}
	}

	/** Called client-side when the server confirms a shield block, Deku Shield burn, or Mirror reflection. */
	public static void onShieldBlockedClient(Vec3 pos, boolean burnedDeku, boolean reflectedMirror) {
		Minecraft mc = Minecraft.getInstance();
		hitstopDuration = 0.055f;
		hitstopTimer = hitstopDuration;
		hitstopStartNanos = System.nanoTime();
		shieldBurstDuration = reflectedMirror ? 0.22f : 0.14f;
		shieldBurstTimer = shieldBurstDuration;
		shieldImpactTimer = SHIELD_IMPACT_DURATION;
		lastShieldBurstPos = pos;
		lastShieldReflect = reflectedMirror;
		impactShakeDuration = 0.095f;
		impactShakeTimer = impactShakeDuration;
		impactShakeStrength = reflectedMirror ? 1.65f : 1.05f;
		impactZoomPush = 0.12f;

		if (burnedDeku) {
			ZeldaEquipment.burnDekuShield();
			shieldHeld = false;
			shieldCrouchTurtling = false;
			lastSentGuarding = false;
			lastSentCrouching = false;
			lastSentShieldId = "";
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.FIRE_EXTINGUISH, 0.92f));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_BREAK, 0.85f));
		} else if (reflectedMirror) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.35f));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SHIELD_BLOCK, 1.45f));
		} else {
			float pitch = ZeldaEquipment.isDekuShieldEquipped() ? 0.78f : 1.12f;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SHIELD_BLOCK, pitch));
		}
	}

	/** Progress (0..1) of the shield block arm/model recoil, or -1 if inactive. */
	public static float getShieldImpactProgress() {
		if (shieldImpactTimer <= 0.0f) {
			return -1.0f;
		}
		return Mth.clamp(1.0f - (shieldImpactTimer / SHIELD_IMPACT_DURATION), 0.0f, 1.0f);
	}

	/** Bell-curve (0..1..0) arm recoil impulse on shield impact. */
	public static float getShieldImpactRecoil() {
		float p = getShieldImpactProgress();
		if (p < 0.0f) {
			return 0.0f;
		}
		return Mth.sin(p * Mth.PI) * (1.0f - 0.35f * p);
	}

	/** Progress (0..1) of the 3D shield block spark burst, or -1 if inactive. */
	public static float getShieldBurstProgress() {
		if (shieldBurstTimer <= 0.0f || shieldBurstDuration <= 1.0e-4f) {
			return -1.0f;
		}
		return Mth.clamp(1.0f - (shieldBurstTimer / shieldBurstDuration), 0.0f, 1.0f);
	}

	public static Vec3 getLastShieldBurstPos() {
		return lastShieldBurstPos;
	}

	public static boolean isLastShieldReflect() {
		return lastShieldReflect;
	}

	/** True when the equipped Wooden Sword should be rendered in Link's hand rather than on his back. */
	public static boolean isSwordDrawn() {
		if (!OcarinaGameTypeClient.isZelda() || !ZeldaEquipment.hasEquippedSword() || ZeldaLedgeSystem.isActive()) {
			return false;
		}
		if (currentMove != SwordMove.NONE || jumpSlamTimer > 0.0f) {
			return true;
		}
		if (manuallySheathed) {
			return false;
		}
		return ZeldaTargetingSystem.isTargeting() || swordDrawnTimer > 0.0f;
	}

	/**
	 * Called when the player presses Cancel (Switch B / Xbox A):
	 * cancels Spin Attack charging and sheathes drawn weapons (sword/shield).
	 */
	public static boolean onCancelPressed(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive() || mc.screen != null) {
			return false;
		}
		if (isSwinging() || isDodging()) {
			return false;
		}

		boolean acted = false;
		if (currentMove == SwordMove.CHARGING_SPIN) {
			currentMove = SwordMove.NONE;
			moveElapsed = 0.0f;
			attackHoldTime = 0.0f;
			chargeReadyPlayed = false;
			suppressSpinUntilAttackRelease = true;
			acted = true;
		}
		if (ZeldaEquipment.hasEquippedSword() && (swordDrawnTimer > 0.0f || swordWasDrawn || ZeldaTargetingSystem.isTargeting())) {
			if (!manuallySheathed || swordDrawnTimer > 0.0f || swordWasDrawn) {
				swordDrawnTimer = 0.0f;
				swordWasDrawn = false;
				manuallySheathed = true;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.85f));
				acted = true;
			}
		}
		if (shieldReadyTimer > 0.0f && !isGuarding()) {
			shieldReadyTimer = 0.0f;
			acted = true;
		}
		return acted;
	}

	public static float getLockedSwingYawDeg() {
		return moveYawDeg;
	}

	public static float getDodgeYawDeg() {
		return dodgeYawDeg;
	}

	/** World travel heading during a dodge/roll (differs by +-90 deg from facing yaw during Z-Target side rolls). */
	public static float getDodgeMotionYawDeg() {
		if (currentDodge == DodgeMove.SIDE_ROLL_RIGHT) {
			return Mth.wrapDegrees(dodgeYawDeg + 90.0f);
		}
		if (currentDodge == DodgeMove.SIDE_ROLL_LEFT) {
			return Mth.wrapDegrees(dodgeYawDeg - 90.0f);
		}
		return dodgeYawDeg;
	}

	/** Called per tick from KeyboardInputMixin to capture forward input relative to Link's target. */
	public static void recordCombatDirectionalInput(float forwardRelative) {
		lastInputForward = forwardRelative;
	}

	/** Called from ZeldaLockdown on attack input polling each client tick. */
	public static void updateAttackInput(Minecraft mc, boolean pressedEdge, boolean heldNow) {
		LocalPlayer player = mc.player;
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive()
				|| ZeldaLedgeSystem.isActive() || mc.screen != null || !ZeldaEquipment.hasEquippedSword()) {
			attackHeld = false;
			attackWasHeld = false;
			return;
		}

		if (!heldNow) {
			suppressSpinUntilAttackRelease = false;
		}
		attackHeld = heldNow;
		if (pressedEdge) {
			onAttackPressed(mc, player);
		}
		if (!heldNow && attackWasHeld) {
			onAttackReleased(mc, player);
		}
		attackWasHeld = heldNow;
	}

	/**
	 * Triggers a grounded Ocarina-style forward roll (A / Xbox B) toward the active
	 * movement direction, or straight ahead in Link's facing direction when standing still.
	 * Buffers cleanly during late sword swing frames to cancel recovery!
	 */
	public static boolean tryStartRoll(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive()
				|| ZeldaLedgeSystem.isActive() || mc.screen != null) {
			return false;
		}
		if (!player.onGround() || isDodging() || isChargingSpin()) {
			return false;
		}
		if (player.isInWater() || player.isInLava() || player.isFallFlying() || player.isPassenger() || player.onClimbable()) {
			return false;
		}

		Gamepad.poll();
		float sx;
		float sy;
		if (Gamepad.leftStickActive()) {
			sx = Gamepad.leftX;
			sy = Gamepad.leftY;
		} else {
			sy = (mc.options.keyUp.isDown() ? 1.0f : 0.0f) - (mc.options.keyDown.isDown() ? 1.0f : 0.0f);
			sx = (mc.options.keyRight.isDown() ? 1.0f : 0.0f) - (mc.options.keyLeft.isDown() ? 1.0f : 0.0f);
		}

		DodgeMove rollKind;
		float rollYaw;
		if (ZeldaTargetingSystem.isFocusActive() && Math.abs(sx) > 0.42f && Math.abs(sx) >= Math.abs(sy) * 0.85f) {
			rollKind = (sx > 0.0f) ? DodgeMove.SIDE_ROLL_RIGHT : DodgeMove.SIDE_ROLL_LEFT;
			rollYaw = ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f);
		} else {
			rollKind = DodgeMove.ROLL;
			rollYaw = resolveStickOrFacingYaw(mc, player, sx, sy);
		}

		if (isSwinging()) {
			if (currentMove != SwordMove.JUMP_ATTACK && currentMove != SwordMove.SPIN_ATTACK && getMoveProgress() >= 0.14f) {
				queuedRoll = true;
				queuedRollMove = rollKind;
				queuedRollYaw = rollYaw;
				queuedDodge = false;
				queuedNextCombo = false;
			}
			return false;
		}

		executeRoll(mc, player, rollKind, rollYaw);
		return true;
	}

	/** Records whether Link is advancing in the air just before landing so drops <= 20 blocks can auto-roll. */
	public static void recordAirborneAdvanceInput(LocalPlayer player, boolean advancingNow, float advanceYawDeg) {
		if (player == null || !EngineState.isActive() || ZeldaLedgeSystem.isActive()
				|| player.isInWater() || player.isInLava() || player.isFallFlying()
				|| player.isPassenger() || player.onClimbable() || player.getAbilities().flying) {
			airAdvanceGraceTicks = 0;
			return;
		}
		if (player.onGround()) {
			airAdvanceGraceTicks = 0;
			return;
		}
		if (advancingNow) {
			airAdvanceGraceTicks = 3;
			airAdvanceYawDeg = advanceYawDeg;
		} else if (airAdvanceGraceTicks > 0) {
			airAdvanceGraceTicks--;
		}
	}

	/**
	 * Ocarina of Time landing roll: on falls between 4 and 20 blocks, advancing just before
	 * touching the ground triggers a forward roll and negates all fall damage.
	 */
	public static boolean tryTriggerLandingRoll(Minecraft mc, LocalPlayer player, float fallDistance) {
		if (player == null || !EngineState.isActive() || ZeldaChestCutscene.isActive()
				|| ZeldaLedgeSystem.isActive() || mc.screen != null) {
			return false;
		}
		if (fallDistance < MIN_LANDING_ROLL_FALL_BLOCKS || fallDistance > MAX_LANDING_ROLL_FALL_BLOCKS) {
			return false;
		}
		if (airAdvanceGraceTicks <= 0 || isRolling() || isChargingSpin()
				|| currentMove == SwordMove.JUMP_ATTACK || currentMove == SwordMove.SPIN_ATTACK) {
			return false;
		}
		if (player.isInWater() || player.isInLava() || !player.level().getFluidState(player.blockPosition()).isEmpty()
				|| player.isFallFlying() || player.isPassenger() || player.onClimbable() || player.getAbilities().flying) {
			return false;
		}
		airAdvanceGraceTicks = 0;
		ZeldaAnimationEngine.clearLandingImpact();
		executeRoll(mc, player, DodgeMove.ROLL, airAdvanceYawDeg);
		player.resetFallDistance();
		if (mc.getConnection() != null) {
			ClientPlayNetworking.send(new ZeldaLandingRollPayload(fallDistance));
		}
		return true;
	}

	private static float resolveStickOrFacingYaw(Minecraft mc, LocalPlayer player, float sx, float sy) {
		if (sx * sx + sy * sy > 0.02f) {
			float stickAngle = (float) Math.toDegrees(Math.atan2(sx, sy));
			float baseYaw = ZeldaTargetingSystem.isFocusActive()
					? ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f)
					: EngineState.camYaw;
			return Mth.wrapDegrees(baseYaw + stickAngle);
		}
		return Mth.wrapDegrees(player.getYRot());
	}

	private static void executeRoll(Minecraft mc, LocalPlayer player, DodgeMove rollKind, float rollYaw) {
		currentMove = SwordMove.NONE;
		moveElapsed = 0.0f;
		queuedNextCombo = false;
		queuedRoll = false;
		queuedRollMove = DodgeMove.ROLL;
		queuedDodge = false;
		jumpSlamTimer = 0.0f;

		currentDodge = rollKind;
		dodgeElapsed = 0.0f;
		dodgeYawDeg = rollYaw;
		rollFinishFxPlayed = false;
		if (rollKind != DodgeMove.ROLL && ZeldaEquipment.hasEquippedSword() && !manuallySheathed) {
			swordDrawnTimer = 4.5f;
		}
		player.setYRot(rollYaw);
		player.setYHeadRot(rollYaw);

		float moveYaw = getDodgeMotionYawDeg();
		float rad = moveYaw * Mth.DEG_TO_RAD;
		double speed = (rollKind == DodgeMove.ROLL) ? 0.42 : 0.46;
		Vec3 dm = player.getDeltaMovement();
		player.setDeltaMovement(-Mth.sin(rad) * speed, dm.y, Mth.cos(rad) * speed);
		float pitch = (rollKind == DodgeMove.ROLL) ? 1.28f : 1.36f;
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_SWEEP, pitch));
	}

	/** Attempts a Z-Target / ZL-Focus Side-Roll or Backflip instead of a plain jump. Returns true if triggered. */
	public static boolean tryStartDodge(LocalPlayer player, float sx, float sy) {
		if (player == null || !player.onGround() || !ZeldaTargetingSystem.isFocusActive() || ZeldaLedgeSystem.isActive()) {
			return false;
		}
		if (isDodging() || isChargingSpin()) {
			return false;
		}
		if (player.isInWater() || player.isInLava() || player.isFallFlying() || player.onClimbable()) {
			return false;
		}

		DodgeMove kind = classifyDodge(sx, sy);
		if (kind == DodgeMove.NONE) {
			return false;
		}
		if (isSwinging()) {
			if (currentMove != SwordMove.JUMP_ATTACK && currentMove != SwordMove.SPIN_ATTACK && getMoveProgress() >= 0.14f) {
				queuedDodge = true;
				queuedDodgeSx = sx;
				queuedDodgeSy = sy;
				queuedRoll = false;
				queuedNextCombo = false;
			}
			return true;
		}

		return executeDodge(Minecraft.getInstance(), player, sx, sy);
	}

	private static DodgeMove classifyDodge(float sx, float sy) {
		float absX = Math.abs(sx);
		float absY = Math.abs(sy);
		if (sy < -0.45f && absY >= absX) {
			return DodgeMove.BACKFLIP;
		}
		if (absX > 0.45f) {
			return (sx < 0.0f) ? DodgeMove.SIDE_HOP_LEFT : DodgeMove.SIDE_HOP_RIGHT;
		}
		return DodgeMove.NONE;
	}

	private static boolean executeDodge(Minecraft mc, LocalPlayer player, float sx, float sy) {
		DodgeMove nextDodge = classifyDodge(sx, sy);
		if (nextDodge == DodgeMove.NONE) {
			return false;
		}

		currentMove = SwordMove.NONE;
		moveElapsed = 0.0f;
		queuedNextCombo = false;
		queuedRoll = false;
		queuedDodge = false;
		jumpSlamTimer = 0.0f;

		float lockYaw = ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f);
		float moveYaw;
		double speed;
		double upVel;
		if (nextDodge == DodgeMove.BACKFLIP) {
			moveYaw = lockYaw + 180.0f;
			speed = 0.45;
			upVel = 0.44;
		} else {
			moveYaw = lockYaw + (sx > 0.0f ? 90.0f : -90.0f);
			speed = 0.51;
			upVel = 0.35;
		}

		currentDodge = nextDodge;
		dodgeElapsed = 0.0f;
		dodgeYawDeg = lockYaw;
		rollFinishFxPlayed = false;
		if (ZeldaEquipment.hasEquippedSword() && !manuallySheathed) {
			swordDrawnTimer = 4.5f;
		}

		player.setYRot(lockYaw);
		player.setYHeadRot(lockYaw);
		float rad = moveYaw * Mth.DEG_TO_RAD;
		player.setDeltaMovement(-Mth.sin(rad) * speed, upVel, Mth.cos(rad) * speed);
		player.setOnGround(false);
		player.hasImpulse = true;
		mc.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_SWEEP, nextDodge == DodgeMove.BACKFLIP ? 0.76f : 1.08f));
		return true;
	}

	/** World horizontal velocity per tick (blocks/tick) driven by sword lunges & rolls. */
	public static Vec3 stepCombatRootMotion(LocalPlayer player) {
		if (isRolling()) {
			float u = getDodgeProgress();
			boolean sideRoll = isSideRolling();
			float peakBps = sideRoll ? 9.8f : 9.2f;
			float startBps = sideRoll ? 8.6f : 8.2f;
			float midEndBps = sideRoll ? 5.6f : 5.4f;
			float speedBps;
			if (u < 0.12f) {
				speedBps = Mth.lerp(u / 0.12f, startBps, peakBps);
			} else if (u < 0.68f) {
				speedBps = Mth.lerp((u - 0.12f) / 0.56f, peakBps, midEndBps);
			} else if (u < 0.92f) {
				boolean stickHeld = isMovementHeld(Minecraft.getInstance());
				float exitTarget = stickHeld ? (sideRoll ? 3.6f : 3.5f) : 1.4f;
				speedBps = Mth.lerp((u - 0.68f) / 0.24f, midEndBps, exitTarget);
			} else {
				speedBps = isMovementHeld(Minecraft.getInstance()) ? (sideRoll ? 3.4f : 3.2f) : 0.0f;
			}
			float rad = getDodgeMotionYawDeg() * Mth.DEG_TO_RAD;
			double tickDs = speedBps * 0.05D;
			return new Vec3(-Mth.sin(rad) * tickDs, 0.0D, Mth.cos(rad) * tickDs);
		}
		if (currentMove == SwordMove.NONE || currentMove == SwordMove.CHARGING_SPIN) {
			return Vec3.ZERO;
		}
		float u = getMoveProgress();
		float speedBps = switch (currentMove) {
			case SLASH_HORIZ, SLASH_BACKHAND -> (u < 0.44f) ? Mth.lerp(u / 0.44f, 3.2f, 0.7f) : 0.0f;
			case SLASH_VERTICAL -> (u >= 0.16f && u < 0.58f) ? Mth.lerp((u - 0.16f) / 0.42f, 4.6f, 0.8f) : 0.0f;
			case THRUST -> (u >= 0.18f && u < 0.56f) ? Mth.lerp((u - 0.18f) / 0.38f, 6.6f, 1.0f) : 0.0f;
			case SPIN_ATTACK -> (u < 0.60f) ? 2.4f * (1.0f - u / 0.60f) : 0.0f;
			default -> 0.0f;
		};
		if (speedBps <= 0.01f) {
			return Vec3.ZERO;
		}

		// Magnetic Z-Target spacing step: close mid-range gaps cleanly without clipping inside enemies.
		LivingEntity locked = ZeldaTargetingSystem.getLockedTarget();
		if (locked != null && currentMove != SwordMove.SPIN_ATTACK) {
			double dx = locked.getX() - player.getX();
			double dz = locked.getZ() - player.getZ();
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (dist <= 1.25D) {
				speedBps *= 0.16f;
			} else if (dist <= 4.4D) {
				float closeBoost = Mth.clamp((float) dist / 2.25f, 0.90f, 1.32f);
				speedBps *= closeBoost;
			}
		}

		float rad = moveYawDeg * Mth.DEG_TO_RAD;
		double tickDs = speedBps * 0.05D;
		return new Vec3(-Mth.sin(rad) * tickDs, 0.0D, Mth.cos(rad) * tickDs);
	}

	private static boolean isMovementHeld(Minecraft mc) {
		Gamepad.poll();
		if (Gamepad.leftStickActive()) {
			return true;
		}
		return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
				|| mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
	}

	public static boolean overridesLocomotionRootMotion() {
		return isRolling() || (isSwinging() && currentMove != SwordMove.JUMP_ATTACK);
	}

	private static void onAttackPressed(Minecraft mc, LocalPlayer player) {
		if (player.isSwimming() || player.isPassenger() || player.onClimbable()) {
			return;
		}
		if (isDodging()) {
			// Switch 2 Roll-Cancel: interrupt late roll slowdown directly into a forward sword slash.
			if (isRolling() && getDodgeProgress() >= 0.52f && player.onGround()) {
				currentDodge = DodgeMove.NONE;
				dodgeElapsed = 0.0f;
				drawSwordWithChime(mc);
				attackHoldTime = 0.0f;
				chargeReadyPlayed = false;
				comboStage = 1;
				startMove(mc, player, SwordMove.SLASH_HORIZ);
			}
			return;
		}
		drawSwordWithChime(mc);
		attackHoldTime = 0.0f;
		chargeReadyPlayed = false;

		if (!player.onGround() && !player.isInWater()) {
			if (currentMove != SwordMove.JUMP_ATTACK) {
				startMove(mc, player, SwordMove.JUMP_ATTACK);
				Vec3 dm = player.getDeltaMovement();
				float rad = moveYawDeg * Mth.DEG_TO_RAD;
				player.setDeltaMovement(dm.x - Mth.sin(rad) * 0.16, Math.max(dm.y, 0.25), dm.z + Mth.cos(rad) * 0.16);
				jumpAttackDescending = true;
			}
			return;
		}

		if (isSwinging()) {
			// Responsive early combo input buffer (u >= 0.12) across 3-hit combo.
			if (getMoveProgress() >= 0.12f
					&& currentMove != SwordMove.SPIN_ATTACK
					&& currentMove != SwordMove.SLASH_VERTICAL
					&& currentMove != SwordMove.THRUST) {
				queuedNextCombo = true;
				queuedRoll = false;
				queuedDodge = false;
			}
			return;
		}

		if (ZeldaTargetingSystem.isFocusActive() && lastInputForward > 0.45f) {
			startMove(mc, player, SwordMove.THRUST);
			comboStage = 0;
			return;
		}

		SwordMove next;
		if (comboResetTimer > 0.0f) {
			next = switch (comboStage) {
				case 1 -> SwordMove.SLASH_BACKHAND;
				case 2 -> SwordMove.SLASH_VERTICAL;
				default -> SwordMove.SLASH_HORIZ;
			};
		} else {
			next = SwordMove.SLASH_HORIZ;
		}
		comboStage = (next == SwordMove.SLASH_HORIZ) ? 1 : (next == SwordMove.SLASH_BACKHAND ? 2 : 0);
		startMove(mc, player, next);
	}

	private static void onAttackReleased(Minecraft mc, LocalPlayer player) {
		if (currentMove == SwordMove.CHARGING_SPIN) {
			if (attackHoldTime >= SPIN_CHARGE_REQUIRED) {
				startMove(mc, player, SwordMove.SPIN_ATTACK);
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_SWEEP, 0.72f));
			} else {
				currentMove = SwordMove.NONE;
				moveElapsed = 0.0f;
			}
		}
		attackHoldTime = 0.0f;
		chargeReadyPlayed = false;
	}

	private static void startMove(Minecraft mc, LocalPlayer player, SwordMove move) {
		currentMove = move;
		moveElapsed = 0.0f;
		hitSweptThisMove = false;
		ZeldaBladeTracker.onSwingStart();
		queuedNextCombo = false;
		queuedRoll = false;
		queuedDodge = false;
		hitTargetsThisSwing.clear();
		swordDrawnTimer = 4.5f;

		if (ZeldaTargetingSystem.isFocusActive()) {
			moveYawDeg = ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f);
			player.setYRot(moveYawDeg);
			player.setYHeadRot(moveYawDeg);
		} else {
			moveYawDeg = player.getYRot();
		}

		float pitch = switch (move) {
			case SLASH_HORIZ -> 1.16f;
			case SLASH_BACKHAND -> 1.26f;
			case SLASH_VERTICAL -> 0.88f;
			case THRUST -> 1.38f;
			case JUMP_ATTACK -> 0.84f;
			case SPIN_ATTACK -> 0.76f;
			default -> 1.0f;
		};
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_SWEEP, pitch));
	}

	private static void drawSwordWithChime(Minecraft mc) {
		manuallySheathed = false;
		if (!swordWasDrawn) {
			swordWasDrawn = true;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.25f));
		}
		swordDrawnTimer = 4.5f;
	}

	private static void tick(Minecraft mc) {
		long now = System.nanoTime();
		float dt = lastStepNanos == 0L ? 0.05f : Mth.clamp((now - lastStepNanos) / 1.0e9f, 0.0f, 0.05f);
		lastStepNanos = now;

		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || !EngineState.isActive() || ZeldaChestCutscene.isActive() || player.isDeadOrDying()) {
			currentMove = SwordMove.NONE;
			moveElapsed = 0.0f;
			queuedNextCombo = false;
			queuedRoll = false;
			queuedDodge = false;
			jumpAttackDescending = false;
			currentDodge = DodgeMove.NONE;
			dodgeElapsed = 0.0f;
			return;
		}

		// Track Dodge & Roll state completions (elapsed timer itself advances at 60+ FPS in stepFrameAnimations).
		if (currentDodge != DodgeMove.NONE) {
			if (isRolling() && !rollFinishFxPlayed && getDodgeProgress() >= 0.72f) {
				rollFinishFxPlayed = true;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.72f));
			}
			boolean landedEarly = !isRolling() && dodgeElapsed > 0.14f && player.onGround();
			if (dodgeElapsed >= currentDodge.duration
					|| landedEarly
					|| player.isInWater() || player.isInLava() || player.onClimbable()) {
				if (landedEarly) {
					ZeldaAnimationEngine.triggerDodgeLanding(currentDodge == DodgeMove.BACKFLIP ? 0.68f : 0.56f);
				}
				currentDodge = DodgeMove.NONE;
				dodgeElapsed = 0.0f;
			}
		}

		if (!ZeldaEquipment.hasEquippedSword()) {
			currentMove = SwordMove.NONE;
			moveElapsed = 0.0f;
			queuedNextCombo = false;
			queuedRoll = false;
			queuedDodge = false;
			jumpAttackDescending = false;
			swordDrawnTimer = 0.0f;
			swordWasDrawn = false;
			return;
		}

		if (ZeldaTargetingSystem.isTargeting()) {
			if (!manuallySheathed) {
				drawSwordWithChime(mc);
			}
		} else {
			manuallySheathed = false;
		}

		if (currentMove == SwordMove.CHARGING_SPIN
				&& (!player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable())) {
			currentMove = SwordMove.NONE;
			moveElapsed = 0.0f;
			attackHoldTime = 0.0f;
			chargeReadyPlayed = false;
		}

		// Handle Spin Attack Charge transition when holding Attack on the ground.
		if (attackHeld && !suppressSpinUntilAttackRelease && player.onGround() && !isDodging()) {
			if (currentMove == SwordMove.NONE) {
				attackHoldTime += dt;
				if (attackHoldTime >= HOLD_TO_CHARGE_DELAY) {
					currentMove = SwordMove.CHARGING_SPIN;
					moveElapsed = 0.0f;
				}
			} else if (currentMove == SwordMove.CHARGING_SPIN) {
				if (attackHoldTime >= SPIN_CHARGE_REQUIRED && !chargeReadyPlayed) {
					chargeReadyPlayed = true;
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.55f));
				}
			}
		}

		// Perform hit sweeps & grounded slam transitions (moveElapsed advances at 60+ FPS in stepFrameAnimations).
		if (isSwinging()) {
			if (isInHitstop()) {
				return;
			}

			if (currentMove == SwordMove.JUMP_ATTACK && (player.isInWater() || player.isInLava() || player.onClimbable())) {
				currentMove = SwordMove.NONE;
				moveElapsed = 0.0f;
				jumpAttackDescending = false;
				return;
			}

			if (currentMove == SwordMove.JUMP_ATTACK && jumpAttackDescending) {
				Vec3 dm = player.getDeltaMovement();
				if (moveElapsed > 0.08f && dm.y < 0.06) {
					player.setDeltaMovement(dm.x, Math.max(-1.15, dm.y - 0.042), dm.z);
				}
				if (moveElapsed > 0.08f && player.onGround()) {
					// Heavy landing shockwave with sword planted into the ground!
					jumpAttackDescending = false;
					jumpSlamTimer = JUMP_SLAM_DURATION;
					boolean hitAny = performHitSweep(player, level, SwordMove.JUMP_ATTACK, 3.7, true);
					if (!hitAny) {
						triggerGroundSlamFeedback();
					}
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 0.86f));
					currentMove = SwordMove.NONE;
					moveElapsed = 0.0f;
					comboResetTimer = 0.0f;
					return;
				}
			}

			float u = getMoveProgress();
			// (Hit sweeps now run per rendered frame in stepFrameAnimations -> sweepStrikeThisFrame.)

			// Responsive Switch 2 dodge/roll recovery cancel once active blade frames finish!
			if (u >= 0.60f && player.onGround()
					&& currentMove != SwordMove.JUMP_ATTACK && currentMove != SwordMove.SPIN_ATTACK) {
				if (queuedDodge) {
					executeDodge(mc, player, queuedDodgeSx, queuedDodgeSy);
					return;
				}
				if (queuedRoll) {
					executeRoll(mc, player, queuedRollMove, queuedRollYaw);
					return;
				}
			}

			boolean moveDone = (currentMove == SwordMove.JUMP_ATTACK)
					? (player.onGround() && moveElapsed > 0.14f)
					: (moveElapsed >= currentMove.duration);

			if (moveDone) {
				SwordMove finished = currentMove;
				currentMove = SwordMove.NONE;
				moveElapsed = 0.0f;
				jumpAttackDescending = false;
				comboResetTimer = (finished == SwordMove.SLASH_HORIZ || finished == SwordMove.SLASH_BACKHAND)
						? COMBO_CHAIN_WINDOW : 0.0f;

				if (queuedNextCombo && player.onGround() && comboStage > 0) {
					SwordMove chain = (comboStage == 1) ? SwordMove.SLASH_BACKHAND : SwordMove.SLASH_VERTICAL;
					comboStage = (chain == SwordMove.SLASH_BACKHAND) ? 2 : 0;
					startMove(mc, player, chain);
				} else if (attackHeld && !suppressSpinUntilAttackRelease && player.onGround()) {
					currentMove = SwordMove.CHARGING_SPIN;
					attackHoldTime = HOLD_TO_CHARGE_DELAY;
				}
			}
		}
	}

	private static boolean performHitSweep(LocalPlayer player, ClientLevel level, SwordMove move, double reach, boolean omni) {
		var list = level.getEntitiesOfClass(
				LivingEntity.class,
				player.getBoundingBox().inflate(reach, 1.85, reach),
				e -> e != player && e.isAlive() && !e.isRemoved() && !(e instanceof ArmorStand)
		);
		float yawRad = moveYawDeg * Mth.DEG_TO_RAD;
		double fwdX = -Mth.sin(yawRad);
		double fwdZ = Mth.cos(yawRad);
		boolean hitAny = false;

		for (LivingEntity target : list) {
			if (hitTargetsThisSwing.contains(target.getId())) {
				continue;
			}
			double dist = Math.sqrt(player.distanceToSqr(target));
			if (dist > reach) {
				continue;
			}
			if (!omni) {
				double dx = target.getX() - player.getX();
				double dz = target.getZ() - player.getZ();
				double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
				double dot = (dx * fwdX + dz * fwdZ) / horiz;
				double minDot = (move == SwordMove.THRUST) ? 0.50 : 0.06;
				if (horiz > 0.9 && dot < minDot) {
					continue;
				}
			}
			hitAny = true;
			hitTargetsThisSwing.add(target.getId());
			enemyHitAtNanos.put(target.getId(), System.nanoTime());
			ClientPlayNetworking.send(new ZeldaSwordAttackPayload(target.getId(), move.serverOrdinal));
			triggerImpactFrame(target.getBoundingBox().getCenter(), move);
		}
		return hitAny;
	}

	private static void triggerGroundSlamFeedback() {
		impactShakeDuration = 0.115f;
		impactShakeTimer = impactShakeDuration;
		impactShakeStrength = 1.45f;
		impactZoomPush = 0.14f;
		lastImpactMove = SwordMove.JUMP_ATTACK;
		lastImpactHeavy = true;
		impactHitSign = -impactHitSign;
	}

	private static void triggerImpactFrame(Vec3 hitCenter, SwordMove move) {
		boolean heavy = (move == SwordMove.JUMP_ATTACK || move == SwordMove.SPIN_ATTACK || move == SwordMove.SLASH_VERTICAL);
		hitstopDuration = heavy ? 0.100f : 0.067f;
		hitstopTimer = hitstopDuration;
		hitstopStartNanos = System.nanoTime();
		impactBurstDuration = heavy ? 0.175f : 0.130f;
		impactBurstTimer = impactBurstDuration;
		impactShakeDuration = heavy ? 0.145f : 0.100f;
		impactShakeTimer = impactShakeDuration;
		impactShakeStrength = heavy ? 2.25f : 1.18f;
		impactZoomPush = heavy ? 0.28f : 0.15f;
		lastImpactMove = move;
		lastImpactCutAngleRad = switch (move) {
			case SLASH_HORIZ -> 0.18f;
			case SLASH_BACKHAND -> -0.32f;
			case SLASH_VERTICAL, JUMP_ATTACK -> Mth.HALF_PI;
			case THRUST -> 0.52f;
			default -> 0.05f;
		};
		lastImpactPos = hitCenter;
		lastImpactHeavy = heavy;
		impactHitSign = -impactHitSign;
		Minecraft.getInstance().getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_CRIT, heavy ? 0.86f : 1.16f));
	}
}
