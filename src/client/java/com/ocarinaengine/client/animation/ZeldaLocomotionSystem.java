package com.ocarinaengine.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Procedural Ocarina of Time 3D inspired Walk & Jog animation system.
 *
 * Key features:
 * - Analog stick tilt (0..1) and real ground velocity continuously blend between a
 *   relaxed upright Walk gait and an athletic forward-leaning Zelda Jog gait.
 * - Stride phase advances from real horizontal displacement to avoid foot sliding.
 * - Dynamic turning banks the torso inward into curves, leads with the head, and
 *   counter-balances the arms.
 * - Peripheral enemy awareness: if Link spots an enemy while walking or jogging, his
 *   head (and subtle upper posture) tracks the enemy smoothly while legs and steering
 *   follow the player's exact movement direction.
 */
public final class ZeldaLocomotionSystem {
	private static final class SpringChannel {
		float v;
		float vel;

		void snap(float target) {
			v = target;
			vel = 0.0f;
		}

		float step(float target, float omega, float dt) {
			float diff = v - target;
			float j = vel + omega * diff;
			float e = (float) Math.exp(-omega * dt);
			v = target + (diff + j * dt) * e;
			vel = (vel - j * omega * dt) * e;
			return v;
		}
	}

	private static final int CHANNELS = 19;
	private static final SpringChannel[] springs = new SpringChannel[CHANNELS];
	static {
		for (int i = 0; i < CHANNELS; i++) {
			springs[i] = new SpringChannel();
		}
	}

	private static boolean initialized = false;
	private static float moveWeight = 0.0f;
	private static float gaitPhase = 0.0f;
	private static float smoothSpeedNorm = 0.0f;
	private static float smoothTurnRate = 0.0f;
	private static float prevBodyYaw = 0.0f;
	private static boolean hasPrevYaw = false;
	private static long lastNanos = 0L;

	// Input signal reported by KeyboardInputMixin (0..1 analog stick or keyboard magnitude).
	private static float inputMagnitude = 0.0f;
	private static float inputTurnStepDeg = 0.0f;

	// Peripheral enemy glance while moving.
	private static long lastEnemySenseNanos = 0L;
	private static boolean enemyVisible = false;
	private static float enemyYawRad = 0.0f;
	private static float enemyPitchRad = 0.0f;
	private static float enemyLookBlend = 0.0f;

	private ZeldaLocomotionSystem() {}

	/** Called each client movement tick from KeyboardInputMixin. */
	public static void recordInput(float magnitude, float turnStepDeg) {
		inputMagnitude = Mth.clamp(magnitude, 0.0f, 1.0f);
		inputTurnStepDeg = turnStepDeg;
	}

	public static float currentMoveWeight() {
		return moveWeight;
	}

	public static void reset() {
		moveWeight = 0.0f;
		smoothSpeedNorm = 0.0f;
		smoothTurnRate = 0.0f;
		enemyVisible = false;
		enemyLookBlend = 0.0f;
		hasPrevYaw = false;
		initialized = false;
	}

	public static void applyWorldLocomotion(HumanoidModel<?> model, LocalPlayer player, float limbSwingAmount) {
		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 0.016f : Mth.clamp((now - lastNanos) / 1.0e9f, 0.0f, 0.05f);
		lastNanos = now;

		boolean isPaused = Minecraft.getInstance().isPaused();
		boolean groundedMove = isMovingOnGround(player, limbSwingAmount);

		double vx = player.getDeltaMovement().x;
		double vz = player.getDeltaMovement().z;
		float horizSpeed = (float) Math.sqrt(vx * vx + vz * vz);
		// Standard Minecraft walk/jog at full stick (~0.215 blocks/tick ~= 4.3 blocks/s).
		float velNorm = Mth.clamp(horizSpeed / 0.215f, 0.0f, 1.15f);
		float targetSpeedNorm = groundedMove
				? Math.max(velNorm, inputMagnitude * 0.85f)
				: 0.0f;

		if (!isPaused) {
			smoothSpeedNorm = damp(smoothSpeedNorm, targetSpeedNorm, groundedMove ? 9.5f : 12.0f, dt);
		}

		boolean activeMove = groundedMove || (smoothSpeedNorm > 0.06f && player.onGround() && !player.isCrouching());
		if (activeMove) {
			moveWeight = damp(moveWeight, 1.0f, 11.5f, dt);
		} else {
			moveWeight = damp(moveWeight, 0.0f, 14.0f, dt);
		}

		if (moveWeight <= 0.003f) {
			if (initialized) {
				resetResidualModelPivots(model);
				initialized = false;
			}
			hasPrevYaw = false;
			enemyLookBlend = 0.0f;
			return;
		}

		// Track smooth turning angular velocity for inward curve banking and head lead.
		float bodyYaw = player.yBodyRot;
		if (!hasPrevYaw) {
			prevBodyYaw = bodyYaw;
			hasPrevYaw = true;
		}
		if (!isPaused && dt > 1.0e-4f) {
			float rawDegDelta = Mth.wrapDegrees(bodyYaw - prevBodyYaw);
			prevBodyYaw = bodyYaw;
			float instTurnDegPerSec = (rawDegDelta / dt) * 0.65f + (inputTurnStepDeg * 20.0f) * 0.35f;
			smoothTurnRate = damp(smoothTurnRate, Mth.clamp(instTurnDegPerSec, -540.0f, 540.0f), 11.0f, dt);

			// Advance stride phase proportionally to real speed so footsteps stay planted.
			float effectiveBlocksPerSec = Math.max(horizSpeed * 20.0f, smoothSpeedNorm * 4.1f);
			float jogFactor = smoothstep(0.32f, 0.72f, smoothSpeedNorm);
			float radPerBlock = Mth.lerp(jogFactor, 2.55f, 2.05f);
			gaitPhase = (gaitPhase + effectiveBlocksPerSec * radPerBlock * dt) % (Mth.TWO_PI);

			// Periodically check for nearby visible enemies to glance at while running.
			if (now - lastEnemySenseNanos > 70_000_000L) {
				lastEnemySenseNanos = now;
				sensePeripheralEnemy(player);
			}
			enemyLookBlend = damp(enemyLookBlend, enemyVisible ? 1.0f : 0.0f, enemyVisible ? 8.5f : 5.5f, dt);
		}

		// Compute blended Walk -> Jog target channels.
		float jog = smoothstep(0.32f, 0.74f, smoothSpeedNorm);
		float strideScale = Mth.clamp(smoothSpeedNorm / 0.25f, 0.35f, 1.0f);

		float rLegWave = strideWave(gaitPhase);
		float lLegWave = strideWave(gaitPhase + Mth.PI);
		float armPhase = gaitPhase - 0.14f; // Subtle natural shoulder lag behind hips.
		float rArmWave = strideWave(armPhase);
		float lArmWave = strideWave(armPhase + Mth.PI);
		float bounce = 0.5f - 0.5f * Mth.cos(2.0f * gaitPhase - 0.35f);

		// Banking into turns (negative body.zRot leans torso RIGHT when turnRate > 0).
		float bank = Mth.clamp(-smoothTurnRate * 0.00058f * Mth.clamp(smoothSpeedNorm, 0.2f, 1.0f), -0.28f, 0.28f);
		float turnHeadLead = Mth.clamp(smoothTurnRate * 0.00125f, -0.45f, 0.45f);

		// Base Walk vs Jog parameters.
		float legAmp = Mth.lerp(jog, 0.50f, 0.90f) * strideScale;
		float armBaseX = Mth.lerp(jog, -0.10f, -0.62f);
		float armAmp = Mth.lerp(jog, 0.44f, 0.62f) * strideScale;
		float bodyLeanX = Mth.lerp(jog, 0.06f, 0.24f) * strideScale;
		float torsoTwistAmp = Mth.lerp(jog, 0.08f, 0.155f) * strideScale;
		float torsoRollAmp = Mth.lerp(jog, 0.024f, 0.045f) * strideScale;
		float bounceAmp = Mth.lerp(jog, 0.36f, 1.05f) * strideScale;
		float leanDrop = Mth.lerp(jog, 0.05f, 0.38f) * strideScale;

		// Target channels.
		float tBodyX = bodyLeanX + 0.03f * bounce * jog;
		float tBodyY = -rLegWave * torsoTwistAmp + enemyYawRad * 0.14f * enemyLookBlend;
		float tBodyZ = bank + Mth.cos(gaitPhase) * torsoRollAmp;
		float tBodyPosY = leanDrop + bounceAmp * bounce;

		// Head: stabilize slightly against body pitch, lead turns, or track spotted enemy!
		float neutralHeadX = -bodyLeanX * 0.55f + 0.035f * bounce;
		float neutralHeadY = turnHeadLead;
		float neutralHeadZ = bank * 0.42f;

		float tHeadX = Mth.lerp(enemyLookBlend, neutralHeadX, enemyPitchRad);
		float tHeadY = Mth.lerp(enemyLookBlend, neutralHeadY, enemyYawRad);
		float tHeadZ = Mth.lerp(enemyLookBlend, neutralHeadZ, -0.06f * Mth.clamp(enemyYawRad, -0.6f, 0.6f));

		// Arms: relaxed opposite swing (Walk) -> forward athletic chest-cross swing (Jog).
		float armInwardY = Mth.lerp(jog, 0.08f, 0.26f);
		float armOutwardZ = Mth.lerp(jog, 0.10f, 0.17f);

		float tRArmX = armBaseX - rArmWave * armAmp;
		float tRArmY = -armInwardY * Mth.clamp(-rArmWave, 0.0f, 1.0f);
		float tRArmZ = armOutwardZ + 0.06f * Math.max(0.0f, rArmWave) - bank * 0.55f;

		float tLArmX = armBaseX - lArmWave * armAmp;
		float tLArmY = armInwardY * Mth.clamp(-lArmWave, 0.0f, 1.0f);
		float tLArmZ = -armOutwardZ - 0.06f * Math.max(0.0f, lArmWave) - bank * 0.55f;

		// Subtle defensive shield-arm poise if running past a visible threat.
		if (enemyLookBlend > 0.05f) {
			tLArmX = Mth.lerp(enemyLookBlend * 0.45f, tLArmX, -0.92f);
			tLArmY = Mth.lerp(enemyLookBlend * 0.45f, tLArmY, 0.42f);
		}

		// Legs: asymmetric heel-toe drive with slight natural outward toe angle during turns.
		float tRLegX = rLegWave * legAmp;
		float tRLegY = 0.04f + turnHeadLead * 0.10f;
		float tRLegZ = 0.025f + bank * 0.18f;

		float tLLegX = lLegWave * legAmp;
		float tLLegY = -0.04f + turnHeadLead * 0.10f;
		float tLLegZ = -0.025f + bank * 0.18f;

		float[] targets = {
				tHeadX, tHeadY, tHeadZ,
				tBodyX, tBodyY, tBodyZ, tBodyPosY,
				tRArmX, tRArmY, tRArmZ,
				tLArmX, tLArmY, tLArmZ,
				tRLegX, tRLegY, tRLegZ,
				tLLegX, tLLegY, tLLegZ
		};

		if (!initialized) {
			for (int i = 0; i < CHANNELS; i++) {
				springs[i].snap(targets[i]);
			}
			initialized = true;
		} else if (!isPaused) {
			for (int i = 0; i < 3; i++) springs[i].step(targets[i], 13.5f, dt);   // Head
			for (int i = 3; i < 7; i++) springs[i].step(targets[i], 14.0f, dt);   // Torso + bounce
			for (int i = 7; i < 13; i++) springs[i].step(targets[i], 16.0f, dt);  // Arms
			for (int i = 13; i < 19; i++) springs[i].step(targets[i], 18.0f, dt); // Legs
		}

		applySpringsToModel(model, moveWeight);
	}

	private static void applySpringsToModel(HumanoidModel<?> m, float w) {
		float hX = springs[0].v, hY = springs[1].v, hZ = springs[2].v;
		float bX = springs[3].v, bY = springs[4].v, bZ = springs[5].v, bPosY = springs[6].v;
		float rAX = springs[7].v, rAY = springs[8].v, rAZ = springs[9].v;
		float lAX = springs[10].v, lAY = springs[11].v, lAZ = springs[12].v;
		float rLX = springs[13].v, rLY = springs[14].v, rLZ = springs[15].v;
		float lLX = springs[16].v, lLY = springs[17].v, lLZ = springs[18].v;

		m.head.xRot = Mth.lerp(w, m.head.xRot, hX);
		m.head.yRot = Mth.lerp(w, m.head.yRot, hY);
		m.head.zRot = Mth.lerp(w, m.head.zRot, hZ);
		m.head.y = Mth.lerp(w, m.head.y, bPosY);

		m.body.xRot = Mth.lerp(w, m.body.xRot, bX);
		m.body.yRot = Mth.lerp(w, m.body.yRot, bY);
		m.body.zRot = Mth.lerp(w, m.body.zRot, bZ);
		m.body.y = Mth.lerp(w, m.body.y, bPosY);

		// Couple shoulder & hip pivots to torso lean and stride twist.
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

		m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, rAX);
		m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, rAY);
		m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, rAZ);
		m.rightArm.x = Mth.lerp(w, -5.0f, rShoulderX);
		m.rightArm.y = Mth.lerp(w, 2.0f, rShoulderY);
		m.rightArm.z = Mth.lerp(w, 0.0f, rShoulderZ);

		m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, lAX);
		m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, lAY);
		m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, lAZ);
		m.leftArm.x = Mth.lerp(w, 5.0f, lShoulderX);
		m.leftArm.y = Mth.lerp(w, 2.0f, lShoulderY);
		m.leftArm.z = Mth.lerp(w, 0.0f, lShoulderZ);

		m.rightLeg.xRot = Mth.lerp(w, m.rightLeg.xRot, rLX);
		m.rightLeg.yRot = Mth.lerp(w, m.rightLeg.yRot, rLY);
		m.rightLeg.zRot = Mth.lerp(w, m.rightLeg.zRot, rLZ);
		m.rightLeg.z = Mth.lerp(w, 0.0f, hipZ + 1.8f * Mth.sin(by));

		m.leftLeg.xRot = Mth.lerp(w, m.leftLeg.xRot, lLX);
		m.leftLeg.yRot = Mth.lerp(w, m.leftLeg.yRot, lLY);
		m.leftLeg.zRot = Mth.lerp(w, m.leftLeg.zRot, lLZ);
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

	private static boolean isMovingOnGround(LocalPlayer player, float limbSwingAmount) {
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
		boolean hasInput = player.input != null && (Math.abs(player.input.forwardImpulse) > 0.02f
				|| Math.abs(player.input.leftImpulse) > 0.02f);
		double horizSq = player.getDeltaMovement().horizontalDistanceSqr();
		return hasInput || horizSq >= 0.0012 || limbSwingAmount >= 0.08f;
	}

	/** Detects the nearest enemy within peripheral sight (+/- 125 deg) while running. */
	private static void sensePeripheralEnemy(LocalPlayer player) {
		ClientLevel level = player.clientLevel;
		if (level == null) {
			enemyVisible = false;
			return;
		}
		var list = level.getEntitiesOfClass(
				Mob.class,
				player.getBoundingBox().inflate(11.0, 4.0, 11.0),
				mob -> mob instanceof Enemy && mob.isAlive() && !mob.isInvisible()
		);
		Mob best = null;
		double bestDist = 121.0;
		float bestRelYaw = 0.0f;
		float bestPitch = 0.0f;

		for (Mob mob : list) {
			double d = mob.distanceToSqr(player);
			if (d >= bestDist) {
				continue;
			}
			double dx = mob.getX() - player.getX();
			double dz = mob.getZ() - player.getZ();
			float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float relYawDeg = Mth.wrapDegrees(worldYaw - player.yBodyRot);
			// Only glance at enemies within forward/peripheral field of view.
			if (Math.abs(relYawDeg) <= 125.0f && player.hasLineOfSight(mob)) {
				bestDist = d;
				best = mob;
				double dy = mob.getEyeY() - player.getEyeY();
				double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
				bestRelYaw = Mth.clamp(relYawDeg * Mth.DEG_TO_RAD, -1.22f, 1.22f);
				bestPitch = Mth.clamp((float) Math.atan2(-dy, horiz), -0.55f, 0.55f);
			}
		}

		if (best != null) {
			enemyVisible = true;
			enemyYawRad = bestRelYaw;
			enemyPitchRad = bestPitch;
		} else {
			enemyVisible = false;
		}
	}

	/** Asymmetric stride curve: brisk forward knee swing, grounded rear push-off. */
	private static float strideWave(float phase) {
		return Mth.sin(phase) + 0.18f * Mth.sin(2.0f * phase - 0.35f);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / Math.max(0.001f, edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}

	private static float damp(float current, float target, float speed, float dt) {
		return Mth.lerp(1.0f - (float) Math.exp(-speed * dt), current, target);
	}
}
