package com.ocarinaengine.client.combat;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.input.ZeldaKeys;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * Ocarina of Time Switch 2 Z-Targeting & ZL Focus system:
 * - Hold Z / ZL to lock onto the best nearby enemy or NPC within range with LOS occlusion grace;
 * - When no target is nearby, holding ZL locks Link's facing direction & camera angle so he can
 *   strafe laterally and backpedal freely while ZL stays held;
 * - Provides crisp reticle snap-in progress & candidate indicators.
 */
public final class ZeldaTargetingSystem {
	private static final double ACQUIRE_RANGE = 18.0;
	private static final double KEEP_RANGE = 23.0;
	private static final double CANDIDATE_RANGE = 15.5;
	private static final int LOS_GRACE_TICKS = 6;

	private static LivingEntity lockedTarget = null;
	private static LivingEntity candidateTarget = null;
	private static boolean lockKeyWasHeld = false;
	private static boolean focusHoldActive = false;
	private static float focusHoldYawDeg = 0.0f;
	private static float focusHoldPitchDeg = EngineState.DEFAULT_PITCH;
	private static float cameraCenterTimer = 0.0f;
	private static float targetBlend = 0.0f;
	private static int losGraceTicks = 0;
	private static long lockAcquireNanos = 0L;
	private static int candidateScanTick = 0;
	private static long lastFrameNanos = 0L;

	private ZeldaTargetingSystem() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ZeldaTargetingSystem::tick);
	}

	public static void reset() {
		lockedTarget = null;
		candidateTarget = null;
		lockKeyWasHeld = false;
		focusHoldActive = false;
		cameraCenterTimer = 0.0f;
		targetBlend = 0.0f;
		losGraceTicks = 0;
		lockAcquireNanos = 0L;
		lastFrameNanos = 0L;
	}

	/** True when locked onto an enemy or NPC target. */
	public static boolean isTargeting() {
		return EngineState.isActive() && !ZeldaChestCutscene.isActive() && lockedTarget != null;
	}

	/** True when holding ZL out of combat to lock facing direction and camera angle. */
	public static boolean isFreeFocusing() {
		return EngineState.isActive() && !ZeldaChestCutscene.isActive() && lockedTarget == null && focusHoldActive;
	}

	/** True whenever Z-Target or ZL direction-lock is active (strafing + fixed camera). */
	public static boolean isFocusActive() {
		return isTargeting() || isFreeFocusing();
	}

	public static LivingEntity getLockedTarget() {
		return isTargeting() ? lockedTarget : null;
	}

	public static LivingEntity getCandidateTarget() {
		if (isTargeting()) {
			return null;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || candidateTarget == null) {
			return null;
		}
		return isValidTarget(player, candidateTarget, CANDIDATE_RANGE, true) ? candidateTarget : null;
	}

	public static float getTargetBlend() {
		return targetBlend;
	}

	/** Progress (0..1 over 160ms) since the current target was locked on, for reticle snap-in animation. */
	public static float getLockSnapProgress() {
		if (lockedTarget == null || lockAcquireNanos == 0L) {
			return 1.0f;
		}
		float elapsed = (System.nanoTime() - lockAcquireNanos) / 1.0e9f;
		return Mth.clamp(elapsed / 0.16f, 0.0f, 1.0f);
	}

	public static boolean isTargetHostile(LivingEntity entity) {
		return entity instanceof Enemy;
	}

	/** World yaw (in degrees, MC convention) from Link directly toward the locked target. */
	public static float getLockYawDeg(LocalPlayer player, float partialTick) {
		LivingEntity target = getLockedTarget();
		if (player == null || target == null) {
			return player != null ? player.getYRot() : 0.0f;
		}
		double px = Mth.lerp(partialTick, player.xo, player.getX());
		double pz = Mth.lerp(partialTick, player.zo, player.getZ());
		double tx = Mth.lerp(partialTick, target.xo, target.getX());
		double tz = Mth.lerp(partialTick, target.zo, target.getZ());
		return (float) Math.toDegrees(Math.atan2(-(tx - px), tz - pz));
	}

	/** Active focus yaw: points toward locked target, or preserves Link's fixed ZL direction out of combat. */
	public static float getFocusYawDeg(LocalPlayer player, float partialTick) {
		if (isTargeting()) {
			return getLockYawDeg(player, partialTick);
		}
		if (isFreeFocusing()) {
			return focusHoldYawDeg;
		}
		return player != null ? player.getYRot() : 0.0f;
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			reset();
			return;
		}
		if (!EngineState.isActive() || ZeldaChestCutscene.isActive() || mc.screen != null || player.isDeadOrDying()) {
			lockedTarget = null;
			candidateTarget = null;
			lockKeyWasHeld = false;
			focusHoldActive = false;
			cameraCenterTimer = 0.0f;
			losGraceTicks = 0;
			return;
		}

		Gamepad.poll();
		boolean inGame = mc.isWindowActive();
		boolean lockHeld = inGame && (ZeldaKeys.isTargetLockHeld() || Gamepad.leftTrigger);

		if (lockHeld && !lockKeyWasHeld) {
			// Rising edge of Z / ZL: lock onto enemy if present, otherwise lock Link's direction & camera angle.
			LivingEntity found = findBestTarget(player, level, ACQUIRE_RANGE);
			if (found != null) {
				lockedTarget = found;
				lockAcquireNanos = System.nanoTime();
				losGraceTicks = 0;
				focusHoldActive = false;
				candidateTarget = null;
				cameraCenterTimer = 0.0f;
				float chimePitch = isTargetHostile(found) ? 1.55f : 1.25f;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), chimePitch));
			} else {
				lockedTarget = null;
				losGraceTicks = 0;
				focusHoldActive = true;
				focusHoldYawDeg = Mth.wrapDegrees(player.getYRot());
				focusHoldPitchDeg = EngineState.DEFAULT_PITCH;
				cameraCenterTimer = 0.24f;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.38f));
			}
		} else if (!lockHeld && lockKeyWasHeld) {
			// Released Z / ZL: release target or direction lock cleanly.
			if (lockedTarget != null) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.92f));
			}
			lockedTarget = null;
			losGraceTicks = 0;
			focusHoldActive = false;
		} else if (lockHeld && lockedTarget != null) {
			// Maintain or auto-chain target while Z / ZL stays held, allowing brief obstacle LOS grace.
			boolean basicValid = isValidTarget(player, lockedTarget, KEEP_RANGE, false);
			boolean hasLos = basicValid && player.hasLineOfSight(lockedTarget);
			if (hasLos) {
				losGraceTicks = 0;
			} else if (basicValid && losGraceTicks < LOS_GRACE_TICKS) {
				losGraceTicks++;
			} else {
				LivingEntity next = findBestTarget(player, level, 13.5);
				if (next != null) {
					lockedTarget = next;
					lockAcquireNanos = System.nanoTime();
					losGraceTicks = 0;
					mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.55f));
				} else {
					lockedTarget = null;
					losGraceTicks = 0;
					focusHoldActive = true;
					focusHoldYawDeg = Mth.wrapDegrees(player.getYRot());
					focusHoldPitchDeg = EngineState.DEFAULT_PITCH;
				}
			}
		}
		lockKeyWasHeld = lockHeld;

		// Periodic scan for a subtle Navi-style lock indicator while not locked on.
		candidateScanTick++;
		if (lockedTarget == null && (candidateScanTick % 2 == 0)) {
			candidateTarget = findBestTarget(player, level, CANDIDATE_RANGE);
		}
	}

	/** Called per frame from EngineState to drive Z-Target framing and ZL direction/camera lock. */
	public static void updateCamera(LocalPlayer player, float dt) {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
		}
		float safeDt = dt > 0.0f ? dt : Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, 0.05f);
		lastFrameNanos = now;

		LivingEntity target = getLockedTarget();
		boolean freeFocus = isFreeFocusing();
		boolean focusVisual = (target != null) || freeFocus;
		targetBlend = Mth.lerp(1.0f - (float) Math.exp((focusVisual ? -14.0f : -12.0f) * safeDt),
				targetBlend, focusVisual ? 1.0f : 0.0f);

		if (target != null) {
			Vec3 pEye = player.getEyePosition();
			Vec3 tMid = target.getBoundingBox().getCenter().add(0.0, target.getBbHeight() * 0.14, 0.0);
			Vec3 delta = tMid.subtract(pEye);
			double horiz = Math.max(0.15, Math.sqrt(delta.x * delta.x + delta.z * delta.z));

			float targetYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
			// Slight over-the-right-shoulder offset keeps Link's back clear of the enemy silhouette.
			float desiredCamYaw = Mth.wrapDegrees(targetYaw + 11.5f);

			float rawPitch = (float) Math.toDegrees(Math.atan2(-delta.y, horiz));
			float distFactor = Mth.clamp((float) ((10.0 - horiz) / 10.0), 0.0f, 1.0f);
			float desiredCamPitch = Mth.clamp(rawPitch * 0.58f + 13.5f + distFactor * 4.5f, -15.0f, 48.0f);

			float yawStep = Mth.wrapDegrees(desiredCamYaw - EngineState.camYaw);
			float alpha = 1.0f - (float) Math.exp(-12.0f * safeDt);
			EngineState.camYaw = Mth.wrapDegrees(EngineState.camYaw + yawStep * alpha);
			EngineState.camPitch = Mth.lerp(alpha, EngineState.camPitch, desiredCamPitch);
			return;
		}

		if (freeFocus) {
			cameraCenterTimer = 0.0f;
			float yawStep = Mth.wrapDegrees(focusHoldYawDeg - EngineState.camYaw);
			float alpha = 1.0f - (float) Math.exp(-17.5f * safeDt);
			EngineState.camYaw = Math.abs(yawStep) < 0.08f
					? focusHoldYawDeg
					: Mth.wrapDegrees(EngineState.camYaw + yawStep * alpha);
			EngineState.camPitch = Math.abs(focusHoldPitchDeg - EngineState.camPitch) < 0.05f
					? focusHoldPitchDeg
					: Mth.lerp(alpha, EngineState.camPitch, focusHoldPitchDeg);
			return;
		}

		if (cameraCenterTimer > 0.0f) {
			cameraCenterTimer = Math.max(0.0f, cameraCenterTimer - safeDt);
			float yawStep = Mth.wrapDegrees(player.getYRot() - EngineState.camYaw);
			float alpha = 1.0f - (float) Math.exp(-17.5f * safeDt);
			EngineState.camYaw = Mth.wrapDegrees(EngineState.camYaw + yawStep * alpha);
			EngineState.camPitch = Mth.lerp(alpha, EngineState.camPitch, EngineState.DEFAULT_PITCH);
		}
	}

	private static LivingEntity findBestTarget(LocalPlayer player, ClientLevel level, double maxRange) {
		var candidates = level.getEntitiesOfClass(
				LivingEntity.class,
				player.getBoundingBox().inflate(maxRange, 6.0, maxRange),
				e -> isValidTarget(player, e, maxRange, true)
		);
		if (candidates.isEmpty()) {
			return null;
		}
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		float camYaw = EngineState.camYaw;
		float bodyYaw = player.getYRot();

		for (LivingEntity e : candidates) {
			double distSqr = e.distanceToSqr(player);
			double dist = Math.sqrt(distSqr);
			double dx = e.getX() - player.getX();
			double dz = e.getZ() - player.getZ();
			float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float camAngle = Math.abs(Mth.wrapDegrees(worldYaw - camYaw));
			float bodyAngle = Math.abs(Mth.wrapDegrees(worldYaw - bodyYaw));

			// Strongly favor hostile enemies and targets visible in the current camera/player arc.
			double hostileBonus = (e instanceof Enemy) ? 0.55 : 1.35;
			double anglePenalty = (camAngle * 0.09 + bodyAngle * 0.04);
			double score = (dist + anglePenalty) * hostileBonus;
			if (score < bestScore) {
				bestScore = score;
				best = e;
			}
		}
		return best;
	}

	private static boolean isValidTarget(LocalPlayer player, LivingEntity entity, double maxRange, boolean requireLos) {
		if (player == null || entity == null || entity == player) {
			return false;
		}
		if (!entity.isAlive() || entity.isRemoved() || entity.isInvisible() || entity instanceof ArmorStand) {
			return false;
		}
		if (entity.distanceToSqr(player) > maxRange * maxRange) {
			return false;
		}
		return !requireLos || player.hasLineOfSight(entity);
	}
}
