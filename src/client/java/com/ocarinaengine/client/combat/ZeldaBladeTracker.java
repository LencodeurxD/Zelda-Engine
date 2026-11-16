package com.ocarinaengine.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.animation.ZeldaPlayerAnimator;
import com.ocarinaengine.client.camera.EngineState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Follows the REAL sword blade in world space, once per rendered frame.
 *
 * The position is rebuilt from exactly the same transform chain the renderer uses for the hand-held sword
 * (body yaw -> whole-body transform -> model flip -> right arm -> right forearm -> hand -> wrist), so everything
 * that is driven from here (blade particles, slash ribbon, impact sparks, charge sparks) is locked to what the
 * player sees, at any FPS.
 */
public final class ZeldaBladeTracker {
	/** One recorded blade position (world space). */
	public static final class Sample {
		public final Vec3 base;
		public final Vec3 tip;
		public final long nanos;
		/** 0..1, how strongly the ribbon is drawn for this sample (blade speed). */
		public final float intensity;
		/** 0 = normal slash, 1 = heavy (vertical / jump), 2 = spin. */
		public final int kind;

		Sample(Vec3 base, Vec3 tip, long nanos, float intensity, int kind) {
			this.base = base;
			this.tip = tip;
			this.nanos = nanos;
			this.intensity = intensity;
			this.kind = kind;
		}
	}

	// ---- Tunables -------------------------------------------------------------------------------------------
	/** Blade geometry in the item frame, measured along the blade from the sprite centre (blocks). */
	private static final float BLADE_BASE_ALONG = 0.02f;
	private static final float BLADE_TIP_ALONG = 0.52f;
	/** Vanilla handheld display transform (third_person_righthand): translation (0,4,0.5)/16 and blade axis. */
	private static final float DISPLAY_TRANS_Y = 0.25f;
	private static final float DISPLAY_TRANS_Z = 0.03125f;
	private static final float BLADE_AXIS_Y = 0.985f;
	private static final float BLADE_AXIS_Z = -0.173f;

	/** Blade speed (blocks/s) mapped to ribbon intensity 0 .. 1. */
	private static final float TRAIL_SPEED_LOW = 10.0f;
	private static final float TRAIL_SPEED_HIGH = 30.0f;
	private static final long TRAIL_LIFE_NANOS = 190_000_000L;
	private static final long MAX_GAP_NANOS = 70_000_000L;
	private static final long STALE_NANOS = 100_000_000L;
	private static final int MAX_SAMPLES = 64;

	private static final List<Sample> samples = new ArrayList<>();
	private static final List<Sample> samplesView = Collections.unmodifiableList(samples);

	private static boolean valid = false;
	private static long lastFrameId = -1L;
	private static long nanos = 0L;
	private static Vec3 tip = Vec3.ZERO;
	private static Vec3 base = Vec3.ZERO;
	private static Vec3 tipVelocity = Vec3.ZERO;

	private ZeldaBladeTracker() {}

	public static void reset() {
		valid = false;
		lastFrameId = -1L;
		samples.clear();
		tipVelocity = Vec3.ZERO;
	}

	/** Called when a new attack starts so the ribbon never links two different swings. */
	public static void onSwingStart() {
		samples.clear();
		valid = false;
	}

	/** True when the blade position is from the last few frames (safe to use from the client tick). */
	public static boolean hasTip() {
		return valid && (System.nanoTime() - nanos) < STALE_NANOS;
	}

	public static Vec3 tip() {
		return tip;
	}

	public static Vec3 base() {
		return base;
	}

	/** Current blade tip velocity in blocks per second. */
	public static Vec3 tipVelocity() {
		return tipVelocity;
	}

	/** Normalised direction of travel of the blade tip, or null if it is (almost) not moving. */
	public static Vec3 motionDir() {
		double len = tipVelocity.length();
		if (len < 1.0e-3) {
			return null;
		}
		return tipVelocity.scale(1.0 / len);
	}

	/** Point of the blade (sampled base..tip) that is closest to the given box, clamped onto that box. */
	public static Vec3 contactPoint(AABB bb) {
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i <= 4; i++) {
			Vec3 p = base.lerp(tip, i / 4.0);
			Vec3 c = new Vec3(
					Mth.clamp(p.x, bb.minX, bb.maxX),
					Mth.clamp(p.y, bb.minY, bb.maxY),
					Mth.clamp(p.z, bb.minZ, bb.maxZ));
			double d = c.distanceToSqr(p);
			if (d < bestDist) {
				bestDist = d;
				best = c;
			}
		}
		return best == null ? bb.getCenter() : best;
	}

	/** Recorded blade positions, oldest first. Only use from the render thread. */
	public static List<Sample> samples() {
		return samplesView;
	}

	public static long trailLifeNanos() {
		return TRAIL_LIFE_NANOS;
	}

	public static long maxGapNanos() {
		return MAX_GAP_NANOS;
	}

	/**
	 * Called from the player model animation (once per rendered frame) after the final pose has been applied
	 * to the model.
	 */
	public static void capture(HumanoidModel<?> model, LocalPlayer player, float partialTick) {
		pruneOld(System.nanoTime());

		boolean swinging = ZeldaCombatSystem.isSwinging();
		boolean charging = ZeldaCombatSystem.isChargingSpin();
		if (!swinging && !charging && !ZeldaCombatSystem.isJumpSlamming()) {
			valid = false;
			return;
		}
		if (player.isSwimming() || player.isFallFlying() || player.isSleeping() || player.isAutoSpinAttack()
				|| player.isPassenger() || player.isDeadOrDying()) {
			valid = false;
			return;
		}
		long frameId = EngineState.frameCounter;
		if (frameId != 0L && frameId == lastFrameId) {
			return;
		}
		lastFrameId = frameId;

		// --- Rebuild the exact transform chain used to render the hand-held sword -------------------------
		float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
		PoseStack ps = new PoseStack();
		ps.mulPose(Axis.YP.rotationDegrees(180.0f - bodyYaw));
		ZeldaPlayerAnimator.applyBodyTransform(player, ps, partialTick);
		ps.scale(-1.0f, -1.0f, 1.0f);
		ps.scale(0.9375f, 0.9375f, 0.9375f);
		ps.translate(0.0f, -1.501f, 0.0f);
		model.translateToHand(HumanoidArm.RIGHT, ps);
		ps.mulPose(Axis.XP.rotationDegrees(-90.0f));
		ps.mulPose(Axis.YP.rotationDegrees(180.0f));
		ps.translate(1.0f / 16.0f, 0.125f, -0.625f);

		float wristPitchDeg = ZeldaAnimationEngine.getSwordWristPitchDeg();
		float wristYawDeg = ZeldaAnimationEngine.getSwordWristYawDeg();
		float wristRollDeg = ZeldaAnimationEngine.getSwordWristRollDeg();
		if (Math.abs(wristRollDeg) > 0.1f) {
			ps.mulPose(Axis.ZP.rotationDegrees(wristRollDeg));
		}
		if (Math.abs(wristYawDeg) > 0.1f) {
			ps.mulPose(Axis.YP.rotationDegrees(wristYawDeg));
		}
		if (Math.abs(wristPitchDeg) > 0.1f) {
			ps.mulPose(Axis.XP.rotationDegrees(-wristPitchDeg));
		}

		Matrix4f m = ps.last().pose();
		Vector3f b = new Vector3f(
				0.0f,
				DISPLAY_TRANS_Y + BLADE_BASE_ALONG * BLADE_AXIS_Y,
				DISPLAY_TRANS_Z + BLADE_BASE_ALONG * BLADE_AXIS_Z);
		Vector3f t = new Vector3f(
				0.0f,
				DISPLAY_TRANS_Y + BLADE_TIP_ALONG * BLADE_AXIS_Y,
				DISPLAY_TRANS_Z + BLADE_TIP_ALONG * BLADE_AXIS_Z);
		m.transformPosition(b);
		m.transformPosition(t);

		double ex = Mth.lerp((double) partialTick, player.xo, player.getX());
		double ey = Mth.lerp((double) partialTick, player.yo, player.getY());
		double ez = Mth.lerp((double) partialTick, player.zo, player.getZ());
		Vec3 newBase = new Vec3(ex + b.x, ey + b.y, ez + b.z);
		Vec3 newTip = new Vec3(ex + t.x, ey + t.y, ez + t.z);

		long now = System.nanoTime();
		Vec3 prevTip = tip;
		Vec3 prevBase = base;
		boolean hadPrev = valid && (now - nanos) < STALE_NANOS;
		float dtSec = Math.max(0.0005f, (now - nanos) / 1.0e9f);

		tip = newTip;
		base = newBase;
		long prevNanos = nanos;
		nanos = now;
		valid = true;

		if (!hadPrev) {
			tipVelocity = Vec3.ZERO;
			return;
		}
		tipVelocity = newTip.subtract(prevTip).scale(1.0 / dtSec);
		float speed = (float) tipVelocity.length();

		if (!swinging) {
			return;
		}
		ZeldaCombatSystem.SwordMove move = ZeldaCombatSystem.getCurrentMove();
		float u = ZeldaCombatSystem.getMoveProgress();
		float start = ZeldaCombatSystem.getStrikeStart(move);
		float end = ZeldaCombatSystem.getStrikeEnd(move);
		boolean inFxPhase = (move == ZeldaCombatSystem.SwordMove.JUMP_ATTACK)
				? (u >= 0.15f)
				: (u >= start - 0.10f && u <= end + 0.12f);
		if (!inFxPhase || ZeldaCombatSystem.isInHitstop()) {
			return;
		}

		int kind = (move == ZeldaCombatSystem.SwordMove.SPIN_ATTACK) ? 2
				: ((move == ZeldaCombatSystem.SwordMove.JUMP_ATTACK || move == ZeldaCombatSystem.SwordMove.SLASH_VERTICAL) ? 1 : 0);

		// Ribbon history (thrusts keep a clean look: no ribbon).
		if (move != ZeldaCombatSystem.SwordMove.THRUST) {
			float intensity = Mth.clamp((speed - TRAIL_SPEED_LOW) / (TRAIL_SPEED_HIGH - TRAIL_SPEED_LOW), 0.0f, 1.0f);
			if (samples.isEmpty() || (now - prevNanos) <= MAX_GAP_NANOS) {
				if (samples.isEmpty()) {
					samples.add(new Sample(prevBase, prevTip, prevNanos, 0.0f, kind));
				}
				samples.add(new Sample(newBase, newTip, now, intensity, kind));
				while (samples.size() > MAX_SAMPLES) {
					samples.remove(0);
				}
			}
		}
	}

	private static void pruneOld(long now) {
		while (!samples.isEmpty() && now - samples.get(0).nanos > TRAIL_LIFE_NANOS) {
			samples.remove(0);
		}
	}
}
