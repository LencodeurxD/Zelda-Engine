package com.ocarinaengine.client.animation;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Extra procedural layers that complete the Zelda animation set on top of {@link ZeldaAnimationEngine}:
 *
 * - Hit flinch: head snap, torso recoil, flared arms and knee give when the player is struck.
 * - Start / stop follow-through: anticipation lean when the stick is pushed, braking lean when released,
 *   with the arms trailing behind the body (driven by the stick, so it never reacts to stride pulses).
 * - Ladder / vine climbing: hand-over-hand reach with contralateral foot steps, phase driven by real vertical travel.
 * - Water: treading water (scissor kick + sculling arms) that turns into a breaststroke while moving.
 *
 * The engine itself only animates states it fully controls; climbing and swimming used to fall back to the
 * vanilla pose, and being hit had no reaction at all.
 */
public final class ZeldaActionAnimations {
	/** Vanilla damage tint / knockback window in ticks. */
	private static final float FLINCH_TICKS = 10.0f;
	private static final float FLINCH_LEAN_RAD = 0.11f;
	private static final float ACCEL_LEAN = 0.13f;
	/** Vertical blocks travelled for one full hand-over-hand cycle (two rungs per hand). */
	private static final float CLIMB_BLOCKS_PER_CYCLE = 1.15f;

	private static final class Target {
		float headX;
		float bodyX, bodyY, bodyZ;
		float rArmX, rArmY, rArmZ;
		float lArmX, lArmY, lArmZ;
		float rForeX, lForeX;
		float rLegX, rLegZ;
		float lLegX, lLegZ;
		float rShinX, lShinX;
	}

	private static final Target target = new Target();

	private static long lastFrame = -1L;

	// Hit flinch.
	private static float flinch = 0.0f;

	// Start / stop follow-through.
	private static float inputSmooth = 0.0f;
	private static float accelLean = 0.0f;

	// Climbing.
	private static boolean hasPrevY = false;
	private static double prevY = 0.0;
	private static float climbBlend = 0.0f;
	private static float climbCycle = 0.0f;
	private static float climbSpeed = 0.0f;
	private static float climbAmp = 0.0f;

	// Water.
	private static float waterBlend = 0.0f;
	private static float waterClock = 0.0f;
	private static float waterMove = 0.0f;

	private ZeldaActionAnimations() {}

	// =========================================================================
	// Per-frame state update (frame-gated, safe to call several times per frame)
	// =========================================================================

	public static void update(LocalPlayer player, float partialTick) {
		long frame = EngineState.frameCounter;
		if (frame != 0L && frame == lastFrame) {
			return;
		}
		lastFrame = frame;
		float dt = Mth.clamp(EngineState.frameDt, 0.001f, 0.05f);

		boolean blocked = player.isDeadOrDying() || player.isSleeping() || player.isPassenger() || player.isFallFlying();

		// ---- Hit flinch: snap in fast, relax slowly -------------------------------------------------
		if (!blocked && player.hurtTime > 0) {
			float p = Mth.clamp(1.0f - (player.hurtTime - partialTick) / FLINCH_TICKS, 0.0f, 1.0f);
			flinch = p < 0.18f ? easeOut(p / 0.18f) : 1.0f - easeInOut((p - 0.18f) / 0.82f);
		} else {
			flinch = damp(flinch, 0.0f, 22.0f, dt);
		}

		// ---- Vertical travel for climbing ---------------------------------------------------------
		double ry = Mth.lerp(partialTick, player.yo, player.getY());
		float dy = 0.0f;
		if (hasPrevY) {
			dy = (float) (ry - prevY);
			if (Math.abs(dy) > 1.0f) {
				dy = 0.0f;
			}
		}
		prevY = ry;
		hasPrevY = true;

		boolean climbing = !blocked && player.onClimbable() && !ZeldaLedgeSystem.isActive();
		climbBlend = damp(climbBlend, climbing ? 1.0f : 0.0f, climbing ? 16.0f : 11.0f, dt);
		if (climbing) {
			climbCycle = wrap01(climbCycle + dy / CLIMB_BLOCKS_PER_CYCLE);
			climbSpeed = damp(climbSpeed, Math.abs(dy) / dt, 12.0f, dt);
		} else {
			climbSpeed = damp(climbSpeed, 0.0f, 12.0f, dt);
		}
		climbAmp = smoothstep(0.05f, 1.10f, climbSpeed);

		// ---- Water: tread / breaststroke ------------------------------------------------------------
		boolean tread = !blocked && !climbing
				&& player.isInWater()
				&& !player.isSwimming()
				&& !player.onGround()
				&& !player.getAbilities().flying;
		waterBlend = damp(waterBlend, tread ? 1.0f : 0.0f, tread ? 10.0f : 9.0f, dt);
		float horizBps = (float) (player.getDeltaMovement().horizontalDistance() * 20.0);
		waterMove = damp(waterMove, tread ? Mth.clamp(horizBps / 2.8f, 0.0f, 1.0f) : 0.0f, 6.0f, dt);
		if (waterBlend > 0.01f) {
			waterClock = wrap01(waterClock + dt * Mth.lerp(waterMove, 0.85f, 1.25f));
		}

		// ---- Start / stop follow-through -------------------------------------------------------------
		float input = ZeldaAnimationEngine.getInputMagnitude();
		inputSmooth = damp(inputSmooth, input, input >= inputSmooth ? 6.5f : 9.0f, dt);
		boolean accelOk = !blocked
				&& ZeldaAnimationEngine.getEngineWeight() > 0.5f
				&& player.onGround()
				&& !ZeldaCombatSystem.isActiveCombat()
				&& !ZeldaCombatSystem.isGuarding()
				&& !ZeldaTargetingSystem.isFocusActive();
		float targetLean = accelOk ? Mth.clamp((input - inputSmooth) * ACCEL_LEAN, -0.14f, 0.14f) : 0.0f;
		accelLean = damp(accelLean, targetLean, 14.0f, dt);
	}

	/** Extra forward lean (radians, positive = forward) applied to the whole body by the renderer. */
	public static float getWholeLeanForwardRad() {
		return accelLean - FLINCH_LEAN_RAD * flinch;
	}

	// =========================================================================
	// Model application (runs after the engine pose, before held-item poses)
	// =========================================================================

	public static void apply(HumanoidModel<?> m, LocalPlayer player, float partialTick) {
		update(player, partialTick);

		boolean any = false;

		if (Math.abs(accelLean) > 0.004f) {
			applyAcceleration(m);
			any = true;
		}
		if (waterBlend > 0.004f) {
			computeWater(target);
			applyTarget(m, target, smoothstep(0.0f, 1.0f, waterBlend));
			any = true;
		}
		if (climbBlend > 0.004f) {
			computeClimb(target);
			applyTarget(m, target, smoothstep(0.0f, 1.0f, climbBlend));
			any = true;
		}
		if (flinch > 0.004f) {
			applyFlinch(m);
			any = true;
		}

		if (any) {
			m.hat.copyFrom(m.head);
		}
	}

	// ---- Additive layers --------------------------------------------------------------------------

	private static void applyAcceleration(HumanoidModel<?> m) {
		float lean = accelLean;
		float bx0 = m.body.xRot;
		float by0 = m.body.yRot;
		float bz0 = m.body.zRot;

		m.body.xRot += lean * 0.35f;
		// Keep the gaze on the horizon while the torso tips.
		m.head.xRot -= lean * 0.50f;

		// Accelerating leaves the arms behind; braking throws them forward.
		m.rightArm.xRot += lean * 1.2f;
		m.leftArm.xRot += lean * 1.2f;
		float bend = -Math.abs(lean) * 1.1f;
		addForearm(m, bend, bend);

		shiftPivots(m, bx0, by0, bz0);
	}

	private static void applyFlinch(HumanoidModel<?> m) {
		float e = flinch;
		float armW = ZeldaCombatSystem.isActiveCombat() ? 0.45f : 1.0f;
		float bx0 = m.body.xRot;
		float by0 = m.body.yRot;
		float bz0 = m.body.zRot;

		m.head.xRot -= 0.36f * e;
		m.body.xRot -= 0.15f * e;

		m.rightArm.xRot -= 0.34f * e * armW;
		m.leftArm.xRot -= 0.34f * e * armW;
		m.rightArm.zRot += 0.36f * e * armW;
		m.leftArm.zRot -= 0.36f * e * armW;
		addForearm(m, -0.28f * e * armW, -0.28f * e * armW);

		// Knee give: weight shifts onto one leg as the hit lands.
		m.rightLeg.xRot += 0.10f * e;
		m.leftLeg.xRot -= 0.12f * e;
		addShin(m, 0.22f * e, 0.22f * e);

		shiftPivots(m, bx0, by0, bz0);
	}

	// ---- Full layers (blend toward a complete pose) ---------------------------------------------

	private static void computeClimb(Target t) {
		float a = Mth.TWO_PI * climbCycle;
		float s = Mth.sin(a);
		float amp = climbAmp;

		// Reach: the right hand is highest at s = +1 while the left foot is lifted (contralateral).
		t.rArmX = -2.25f - 0.38f * s * amp;
		t.lArmX = -2.25f + 0.38f * s * amp;
		t.rArmY = -0.05f;
		t.lArmY = 0.05f;
		t.rArmZ = 0.20f;
		t.lArmZ = -0.20f;
		// The elbow folds as the hand pulls down.
		t.rForeX = -(0.30f + 0.45f * (0.5f - 0.5f * s * amp));
		t.lForeX = -(0.30f + 0.45f * (0.5f + 0.5f * s * amp));

		float liftL = Math.max(0.0f, s) * amp;
		float liftR = Math.max(0.0f, -s) * amp;
		t.rLegX = -0.10f - 0.80f * liftR;
		t.lLegX = -0.10f - 0.80f * liftL;
		t.rShinX = 0.15f + 1.00f * liftR;
		t.lShinX = 0.15f + 1.00f * liftL;
		t.rLegZ = 0.05f;
		t.lLegZ = -0.05f;

		t.bodyX = 0.10f;
		t.bodyY = 0.10f * s * amp;
		t.bodyZ = -0.05f * s * amp;
		t.headX = -0.12f;
	}

	private static void computeWater(Target t) {
		float a = Mth.TWO_PI * waterClock;
		float sA = Mth.sin(a);
		float cA = Mth.cos(a);
		float mv = waterMove;
		// 0 = arms extended forward, 1 = arms swept out and back.
		float breast = 0.5f + 0.5f * sA;

		float strokeX = -1.60f + 1.30f * breast;
		float strokeZ = 0.10f + 0.95f * breast;
		t.rArmX = Mth.lerp(mv, -0.30f + 0.18f * cA, strokeX);
		t.lArmX = Mth.lerp(mv, -0.30f - 0.18f * cA, strokeX);
		t.rArmZ = Mth.lerp(mv, 0.95f + 0.22f * sA, strokeZ);
		t.lArmZ = -Mth.lerp(mv, 0.95f - 0.22f * sA, strokeZ);
		t.rArmY = 0.0f;
		t.lArmY = 0.0f;
		t.rForeX = Mth.lerp(mv, -0.50f - 0.25f * cA, -0.20f - 0.70f * breast);
		t.lForeX = Mth.lerp(mv, -0.50f + 0.25f * cA, -0.20f - 0.70f * breast);

		// Scissor kick while treading, frog kick while travelling.
		float frogX = -0.55f + 0.40f * breast;
		float frogZ = 0.10f + 0.32f * breast;
		float frogShin = 1.00f - 0.60f * breast;
		t.rLegX = Mth.lerp(mv, -0.40f + 0.42f * sA, frogX);
		t.lLegX = Mth.lerp(mv, -0.40f - 0.42f * sA, frogX);
		t.rLegZ = Mth.lerp(mv, 0.05f, frogZ);
		t.lLegZ = -Mth.lerp(mv, 0.05f, frogZ);
		t.rShinX = Mth.lerp(mv, 0.70f - 0.35f * sA, frogShin);
		t.lShinX = Mth.lerp(mv, 0.70f + 0.35f * sA, frogShin);

		t.bodyX = 0.04f + 0.16f * mv;
		t.bodyY = 0.05f * sA * (1.0f - mv);
		t.bodyZ = 0.03f * cA * (1.0f - mv);
		t.headX = -0.10f * mv;
	}

	private static void applyTarget(HumanoidModel<?> m, Target t, float w) {
		m.head.xRot += t.headX * w;

		m.body.xRot = Mth.lerp(w, m.body.xRot, t.bodyX);
		m.body.yRot = Mth.lerp(w, m.body.yRot, t.bodyY);
		m.body.zRot = Mth.lerp(w, m.body.zRot, t.bodyZ);

		m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, t.rArmX);
		m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, t.rArmY);
		m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, t.rArmZ);
		m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, t.lArmX);
		m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, t.lArmY);
		m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, t.lArmZ);

		m.rightLeg.xRot = Mth.lerp(w, m.rightLeg.xRot, t.rLegX);
		m.rightLeg.yRot = Mth.lerp(w, m.rightLeg.yRot, 0.0f);
		m.rightLeg.zRot = Mth.lerp(w, m.rightLeg.zRot, t.rLegZ);
		m.leftLeg.xRot = Mth.lerp(w, m.leftLeg.xRot, t.lLegX);
		m.leftLeg.yRot = Mth.lerp(w, m.leftLeg.yRot, 0.0f);
		m.leftLeg.zRot = Mth.lerp(w, m.leftLeg.zRot, t.lLegZ);

		if (m instanceof ArticulatedPlayerModel apm) {
			blendPart(apm.ocarina$rightForearm(), t.rForeX, w);
			blendPart(apm.ocarina$leftForearm(), t.lForeX, w);
			blendPart(apm.ocarina$rightShin(), t.rShinX, w);
			blendPart(apm.ocarina$leftShin(), t.lShinX, w);
		}

		syncPivots(m, w);
	}

	// ---- Joint helpers -------------------------------------------------------------------------------

	private static void blendPart(ModelPart part, float xRot, float w) {
		if (part != null) {
			part.xRot = Mth.lerp(w, part.xRot, xRot);
			part.yRot = Mth.lerp(w, part.yRot, 0.0f);
			part.zRot = 0.0f;
		}
	}

	private static void addForearm(HumanoidModel<?> m, float right, float left) {
		if (m instanceof ArticulatedPlayerModel apm) {
			ModelPart r = apm.ocarina$rightForearm();
			ModelPart l = apm.ocarina$leftForearm();
			if (r != null) {
				r.xRot += right;
			}
			if (l != null) {
				l.xRot += left;
			}
		}
	}

	private static void addShin(HumanoidModel<?> m, float right, float left) {
		if (m instanceof ArticulatedPlayerModel apm) {
			ModelPart r = apm.ocarina$rightShin();
			ModelPart l = apm.ocarina$leftShin();
			if (r != null) {
				r.xRot += right;
			}
			if (l != null) {
				l.xRot += left;
			}
		}
	}

	private static float baseShoulderY(HumanoidModel<?> m) {
		return (m instanceof ArticulatedPlayerModel apm && apm.ocarina$isSlim()) ? 2.5f : 2.0f;
	}

	/**
	 * Moves shoulder and hip pivots by the difference between the body rotation before and after an additive
	 * layer, using the same joint geometry as {@link ZeldaAnimationEngine} so the limbs stay attached to the torso.
	 */
	private static void shiftPivots(HumanoidModel<?> m, float bx0, float by0, float bz0) {
		float base = baseShoulderY(m);
		float bx = m.body.xRot;
		float by = m.body.yRot;
		float bz = m.body.zRot;

		m.rightArm.x += -5.0f * (Mth.cos(by) - Mth.cos(by0));
		m.rightArm.y += base * (Mth.cos(bx) - Mth.cos(bx0)) - 5.0f * (Mth.sin(bz) - Mth.sin(bz0));
		m.rightArm.z += 4.6f * (Mth.sin(by) - Mth.sin(by0)) + 2.0f * (Mth.sin(bx) - Mth.sin(bx0));

		m.leftArm.x += 5.0f * (Mth.cos(by) - Mth.cos(by0));
		m.leftArm.y += base * (Mth.cos(bx) - Mth.cos(bx0)) + 5.0f * (Mth.sin(bz) - Mth.sin(bz0));
		m.leftArm.z += -4.6f * (Mth.sin(by) - Mth.sin(by0)) + 2.0f * (Mth.sin(bx) - Mth.sin(bx0));

		float hipDz = 10.5f * (Mth.sin(bx) - Mth.sin(bx0));
		m.rightLeg.z += hipDz + 1.6f * (Mth.sin(by) - Mth.sin(by0));
		m.leftLeg.z += hipDz - 1.6f * (Mth.sin(by) - Mth.sin(by0));
	}

	/** Absolute pivot placement for full layers; also cancels the vanilla crouch offsets. */
	private static void syncPivots(HumanoidModel<?> m, float w) {
		float base = baseShoulderY(m);
		float bx = m.body.xRot;
		float by = m.body.yRot;
		float bz = m.body.zRot;

		m.head.y = Mth.lerp(w, m.head.y, 0.0f);
		m.body.y = Mth.lerp(w, m.body.y, 0.0f);

		m.rightArm.x = Mth.lerp(w, m.rightArm.x, -5.0f * Mth.cos(by));
		m.rightArm.y = Mth.lerp(w, m.rightArm.y, base * Mth.cos(bx) - 5.0f * Mth.sin(bz));
		m.rightArm.z = Mth.lerp(w, m.rightArm.z, 4.6f * Mth.sin(by) + 2.0f * Mth.sin(bx));

		m.leftArm.x = Mth.lerp(w, m.leftArm.x, 5.0f * Mth.cos(by));
		m.leftArm.y = Mth.lerp(w, m.leftArm.y, base * Mth.cos(bx) + 5.0f * Mth.sin(bz));
		m.leftArm.z = Mth.lerp(w, m.leftArm.z, -4.6f * Mth.sin(by) + 2.0f * Mth.sin(bx));

		float pelvisYaw = (m.rightLeg.yRot + m.leftLeg.yRot) * 0.5f;
		float hipZ = 10.5f * Mth.sin(bx);
		m.rightLeg.x = Mth.lerp(w, m.rightLeg.x, -1.9f * Mth.cos(pelvisYaw));
		m.rightLeg.z = Mth.lerp(w, m.rightLeg.z, hipZ + 1.6f * Mth.sin(by) + 1.9f * Mth.sin(pelvisYaw));
		m.leftLeg.x = Mth.lerp(w, m.leftLeg.x, 1.9f * Mth.cos(pelvisYaw));
		m.leftLeg.z = Mth.lerp(w, m.leftLeg.z, hipZ - 1.6f * Mth.sin(by) - 1.9f * Mth.sin(pelvisYaw));
		m.rightLeg.y = Mth.lerp(w, m.rightLeg.y, 12.0f);
		m.leftLeg.y = Mth.lerp(w, m.leftLeg.y, 12.0f);
	}

	// ---- Math ---------------------------------------------------------------------------------------

	private static float damp(float current, float targetValue, float speed, float dt) {
		return current + (targetValue - current) * (1.0f - (float) Math.exp(-speed * dt));
	}

	private static float wrap01(float v) {
		return v - Mth.floor(v);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}

	private static float easeOut(float t) {
		float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
		return 1.0f - u * u * u;
	}

	private static float easeInOut(float t) {
		float x = Mth.clamp(t, 0.0f, 1.0f);
		return x * x * (3.0f - 2.0f * x);
	}
}
