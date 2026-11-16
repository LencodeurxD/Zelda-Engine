package com.ocarinaengine.client.animation;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Pro-grade keyframed procedural animation engine for Ocarina Engine.
 *
 * Architecture:
 * - Foot-lock locomotion: stride cycle advances strictly from interpolated world-ground
 *   displacement so stance feet remain planted with zero "skiing" at any speed or FPS.
 * - Hermite / Catmull-Rom keyframe tracks drive hips, knees (shins), shoulders, and elbows (forearms)
 *   for natural 2-bone contact recoil, swing clearance, athletic running pump, and coiled sword strikes.
 * - Direct keyframe evaluation for cyclic gait paired with critically-damped springs
 *   strictly for smooth state transitions, turning, and gaze.
 */
public final class ZeldaAnimationEngine {
	public enum IdleScenario {
		NEUTRAL,
		LOW_HEALTH,
		HOSTILE_ALERT,
		CLIFF_EDGE,
		COLD,
		HOT,
		RAIN
	}

	private enum NeutralAction {
		NONE,
		LOOK_AROUND,
		STANCE_CHECK,
		STRETCH_SIGH,
		WEIGHT_SHIFT
	}

	private enum HotAction {
		NONE,
		WIPE_BROW,
		FAN_FACE
	}

	/** Compact cubic-Hermite (Catmull-Rom) keyframe track for crafted animation timing. */
	private static final class KeyTrack {
		private final float[] t;
		private final float[] v;
		private final boolean cyclic;

		KeyTrack(boolean cyclic, float... tvPairs) {
			int n = tvPairs.length / 2;
			this.t = new float[n];
			this.v = new float[n];
			this.cyclic = cyclic;
			for (int i = 0; i < n; i++) {
				this.t[i] = tvPairs[i * 2];
				this.v[i] = tvPairs[i * 2 + 1];
			}
		}

		float eval(float u) {
			int n = t.length;
			if (n == 0) return 0.0f;
			if (n == 1) return v[0];

			float x = cyclic ? (u - Mth.floor(u)) : Mth.clamp(u, t[0], t[n - 1]);
			int idx = 0;
			for (int i = 0; i < n - 1; i++) {
				if (x >= t[i] && x <= t[i + 1]) {
					idx = i;
					break;
				}
				if (x > t[i + 1]) {
					idx = i + 1;
				}
			}
			if (idx >= n - 1) return v[n - 1];

			float t0 = t[idx];
			float t1 = t[idx + 1];
			float span = Math.max(1.0e-4f, t1 - t0);
			float s = Mth.clamp((x - t0) / span, 0.0f, 1.0f);

			float p0 = v[idx];
			float p1 = v[idx + 1];
			float d = (p1 - p0) / span;

			float t0Tan = tangent(idx);
			float t1Tan = tangent(idx + 1);
			if (Math.abs(d) < 1.0e-5f) {
				t0Tan = 0.0f;
				t1Tan = 0.0f;
			} else {
				if (t0Tan * d <= 0.0f) t0Tan = 0.0f;
				if (t1Tan * d <= 0.0f) t1Tan = 0.0f;
				float a = t0Tan / d;
				float b = t1Tan / d;
				float h = a * a + b * b;
				if (h > 9.0f) {
					float tau = 3.0f / (float) Math.sqrt(h);
					t0Tan = tau * a * d;
					t1Tan = tau * b * d;
				}
			}

			float m0 = t0Tan * span;
			float m1 = t1Tan * span;

			float s2 = s * s;
			float s3 = s2 * s;
			float h00 = 2.0f * s3 - 3.0f * s2 + 1.0f;
			float h10 = s3 - 2.0f * s2 + s;
			float h01 = -2.0f * s3 + 3.0f * s2;
			float h11 = s3 - s2;
			return h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
		}

		private float tangent(int i) {
			int n = t.length;
			if (cyclic) {
				if (i == 0 || i == n - 1) {
					int prev = n - 2;
					int next = 1;
					float dt = (t[n - 1] - t[prev]) + (t[next] - t[0]);
					return dt > 1.0e-4f ? (v[next] - v[prev]) / dt : 0.0f;
				}
				float dt = t[i + 1] - t[i - 1];
				return dt > 1.0e-4f ? (v[i + 1] - v[i - 1]) / dt : 0.0f;
			} else {
				if (i == 0) {
					float dt = t[1] - t[0];
					return dt > 1.0e-4f ? (v[1] - v[0]) / dt : 0.0f;
				}
				if (i == n - 1) {
					float dt = t[n - 1] - t[n - 2];
					return dt > 1.0e-4f ? (v[n - 1] - v[n - 2]) / dt : 0.0f;
				}
				float dt = t[i + 1] - t[i - 1];
				return dt > 1.0e-4f ? (v[i + 1] - v[i - 1]) / dt : 0.0f;
			}
		}
	}

	// =========================================================================
	// Handcrafted Pro Gait Keyframe Curves (Normalized Cycle u in [0, 1])
	// 0.00 = Right Heel Contact | 0.14 = Weight Recoil | 0.30 = Passing Crest
	// 0.50 = Left Heel Contact  | 0.64 = Weight Recoil | 0.80 = Passing Crest
	// =========================================================================

	/** Walk Knee flexion (radians >= 0, backward bend): impact cushion in stance, clean fold during swing. */
	private static final KeyTrack WALK_KNEE = new KeyTrack(true,
			0.00f, 0.06f,
			0.14f, 0.22f,
			0.32f, 0.07f,
			0.48f, 0.18f,
			0.68f, 0.66f,
			0.88f, 0.14f,
			1.00f, 0.06f
	);

	/** Jog Knee flexion (radians >= 0, backward bend): athletic shock absorption + high swing clearance. */
	private static final KeyTrack JOG_KNEE = new KeyTrack(true,
			0.00f, 0.10f,
			0.14f, 0.36f,
			0.30f, 0.14f,
			0.46f, 0.32f,
			0.68f, 1.18f,
			0.88f, 0.22f,
			1.00f, 0.10f
	);

	/** Walk Elbow flexion (radians <= 0, forward bend): relaxed swing crease peaking on forward pass. */
	private static final KeyTrack WALK_ELBOW = new KeyTrack(true,
			0.00f, -0.22f,
			0.25f, -0.32f,
			0.50f, -0.44f,
			0.75f, -0.28f,
			1.00f, -0.22f
	);

	/** Jog Elbow flexion (radians <= 0, forward bend): crisp Link-style bent-arm athletic pump. */
	private static final KeyTrack JOG_ELBOW = new KeyTrack(true,
			0.00f, -0.78f,
			0.25f, -0.96f,
			0.52f, -1.16f,
			0.78f, -0.92f,
			1.00f, -0.78f
	);

	/**
	 * Root Motion velocity modulation curves (mean ~ 1.0 over [0, 1]).
	 * Drives entity world displacement directly from the stride phase:
	 * slight heel-plant recoil settle at 0.00/0.50 and propulsion surge through push-off at 0.30/0.80.
	 */
	private static final KeyTrack ROOT_PULSE_WALK = new KeyTrack(true,
			0.00f, 0.88f,
			0.13f, 0.91f,
			0.31f, 1.13f,
			0.45f, 1.04f,
			0.50f, 0.88f,
			0.63f, 0.91f,
			0.81f, 1.13f,
			0.95f, 1.04f,
			1.00f, 0.88f
	);

	private static final KeyTrack ROOT_PULSE_JOG = new KeyTrack(true,
			0.00f, 0.86f,
			0.12f, 0.92f,
			0.30f, 1.16f,
			0.44f, 1.04f,
			0.50f, 0.86f,
			0.62f, 0.92f,
			0.80f, 1.16f,
			0.94f, 1.04f,
			1.00f, 0.86f
	);

	// Handcrafted Jump, Apex, Descent & Landing Impact Keyframe Curves (uJump: 0=Launch, 0.48=Apex, 1.0=Deep Fall).
	private static final KeyTrack JUMP_LEAD_LEG = new KeyTrack(false,
			0.00f, -0.82f,
			0.24f, -0.70f,
			0.48f, -0.50f,
			0.74f, -0.40f,
			1.00f, -0.52f
	);

	private static final KeyTrack JUMP_TRAIL_LEG = new KeyTrack(false,
			0.00f, 0.40f,
			0.24f, 0.28f,
			0.48f, 0.12f,
			0.74f, 0.04f,
			1.00f, 0.14f
	);

	private static final KeyTrack JUMP_LEAD_KNEE = new KeyTrack(false,
			0.00f, 0.84f,
			0.24f, 0.72f,
			0.48f, 0.56f,
			0.74f, 0.46f,
			1.00f, 0.58f
	);

	private static final KeyTrack JUMP_TRAIL_KNEE = new KeyTrack(false,
			0.00f, 0.34f,
			0.24f, 0.42f,
			0.48f, 0.32f,
			0.74f, 0.24f,
			1.00f, 0.36f
	);

	private static final KeyTrack JUMP_LEAD_ARM = new KeyTrack(false,
			0.00f, -0.58f,
			0.25f, -0.46f,
			0.48f, -0.30f,
			0.76f, -0.60f,
			1.00f, -1.04f
	);

	private static final KeyTrack JUMP_TRAIL_ARM = new KeyTrack(false,
			0.00f,  0.34f,
			0.25f,  0.18f,
			0.48f, -0.06f,
			0.76f, -0.42f,
			1.00f, -0.96f
	);

	private static final KeyTrack JUMP_ARM_ELBOW = new KeyTrack(false,
			0.00f, -0.54f,
			0.25f, -0.46f,
			0.48f, -0.36f,
			0.76f, -0.30f,
			1.00f, -0.26f
	);

	private static final KeyTrack JUMP_ARM_FLARE = new KeyTrack(false,
			0.00f, 0.16f,
			0.25f, 0.24f,
			0.48f, 0.34f,
			0.76f, 0.42f,
			1.00f, 0.56f
	);

	private static final KeyTrack JUMP_LEAN_X = new KeyTrack(false,
			0.00f, 0.04f,
			0.25f, 0.07f,
			0.48f, 0.09f,
			0.76f, 0.13f,
			1.00f, 0.16f
	);

	private static final KeyTrack LAND_SQUASH = new KeyTrack(false,
			0.00f,  0.00f,
			0.18f,  1.00f,
			0.38f,  0.74f,
			0.68f,  0.18f,
			0.88f, -0.05f,
			1.00f,  0.00f
	);

	// Handcrafted Idle breathing & phrase curves.
	private static final KeyTrack HERO_BREATH = new KeyTrack(true,
			0.00f, 0.00f,
			0.34f, 1.00f,
			0.46f, 0.94f,
			0.86f, 0.04f,
			1.00f, 0.00f
	);

	private static final KeyTrack LOOK_YAW_CURVE = new KeyTrack(false,
			0.00f,  0.00f,
			0.10f,  1.00f,
			0.14f,  0.95f,
			0.40f,  0.95f,
			0.52f, -0.88f,
			0.56f, -0.84f,
			0.82f, -0.84f,
			0.94f,  0.02f,
			1.00f,  0.00f
	);

	private static final KeyTrack LOOK_PITCH_CURVE = new KeyTrack(false,
			0.00f,  0.00f,
			0.12f, -1.00f,
			0.38f, -0.85f,
			0.52f, -0.45f,
			0.80f, -0.90f,
			0.95f,  0.00f,
			1.00f,  0.00f
	);

	// Combo 1/3: High-right windup (elbow cocked) -> diagonal forehand cut (full arm snap) -> low-left follow-through
	private static final KeyTrack SLASH_H_BODY_Y = new KeyTrack(false,
			0.00f, -0.30f, 0.09f, -0.36f, 0.24f, 0.50f,
			0.29f, 0.54f, 0.40f, -0.04f, 0.48f, -0.30f,
			0.66f, -0.60f, 0.78f, -0.54f, 1.00f, -0.30f
	);
	private static final KeyTrack SLASH_H_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.09f, 1.01f, 0.24f, 0.92f,
			0.29f, 0.91f, 0.48f, 1.04f, 0.78f, 0.96f,
			1.00f, 1.00f
	);
	private static final KeyTrack SLASH_H_RARM_X = new KeyTrack(false,
			0.00f, -0.76f, 0.09f, -0.58f, 0.24f, -1.26f,
			0.29f, -1.28f, 0.38f, -1.32f, 0.48f, -0.68f,
			0.66f,  0.02f, 0.78f, -0.06f, 1.00f, -0.76f
	);
	private static final KeyTrack SLASH_H_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.48f, 0.09f, -0.54f, 0.24f, -0.72f,
			0.29f, -0.74f, 0.38f, -0.30f, 0.48f, -0.16f,
			0.66f, -0.18f, 0.78f, -0.22f, 1.00f, -0.48f
	);
	private static final KeyTrack SLASH_H_RARM_Y = new KeyTrack(false,
			0.00f, 0.08f, 0.09f, 0.22f, 0.24f, 1.18f,
			0.29f, 1.24f, 0.38f, 0.86f, 0.48f, 0.16f,
			0.66f, -0.58f, 0.78f, -0.50f, 1.00f, 0.08f
	);
	private static final KeyTrack SLASH_H_RARM_Z = new KeyTrack(false,
			0.00f, -0.09f, 0.09f, 0.04f, 0.24f, 0.62f,
			0.29f, 0.66f, 0.38f, 0.42f, 0.48f, -0.14f,
			0.66f, -0.52f, 0.78f, -0.46f, 1.00f, -0.09f
	);
	private static final KeyTrack SLASH_H_LLEG_X = new KeyTrack(false,
			0.00f, -0.42f, 0.24f, -0.36f, 0.48f, -0.66f, 0.78f, -0.70f, 1.00f, -0.42f
	);
	private static final KeyTrack SLASH_H_RLEG_X = new KeyTrack(false,
			0.00f, 0.24f, 0.24f, 0.30f, 0.48f, 0.40f, 0.78f, 0.42f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_H_LSHIN_X = new KeyTrack(false,
			0.00f, 0.24f, 0.24f, 0.22f, 0.48f, 0.56f, 0.78f, 0.62f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_H_RSHIN_X = new KeyTrack(false,
			0.00f, 0.14f, 0.24f, 0.18f, 0.48f, 0.12f, 0.78f, 0.14f, 1.00f, 0.14f
	);
	private static final KeyTrack SLASH_H_WRIST_PITCH = new KeyTrack(false,
			0.00f, -9.0f, 0.24f, -20.0f, 0.48f, 1.5f, 0.78f, 38.0f, 1.00f, -9.0f
	);
	private static final KeyTrack SLASH_H_WRIST_YAW = new KeyTrack(false,
			0.00f, 6.0f, 0.24f, 1.0f, 0.48f, 17.0f, 0.78f, 10.5f, 1.00f, 6.0f
	);
	private static final KeyTrack SLASH_H_WRIST_ROLL = new KeyTrack(false,
			0.00f, 0.0f, 0.24f, -22.5f, 0.48f, 13.5f, 0.78f, 23.0f, 1.00f, 0.0f
	);

	// Combo 2/3: Starts seamlessly from Combo 1 low-left follow-through -> rising diagonal backhand -> high-right!
	private static final KeyTrack SLASH_B_BODY_Y = new KeyTrack(false,
			0.00f, -0.54f, 0.10f, -0.56f, 0.22f, -0.52f,
			0.27f, -0.50f, 0.38f, -0.10f, 0.48f, 0.22f,
			0.70f, 0.52f, 0.80f, 0.56f, 0.88f, 0.48f,
			1.00f, -0.30f
	);
	private static final KeyTrack SLASH_B_SCALE_Y = new KeyTrack(false,
			0.00f, 0.95f, 0.22f, 0.93f, 0.48f, 1.06f, 0.80f, 1.03f, 1.00f, 1.00f
	);
	private static final KeyTrack SLASH_B_RARM_X = new KeyTrack(false,
			0.00f, -0.02f, 0.10f, -0.04f, 0.22f, -0.12f,
			0.27f, -0.14f, 0.38f, -0.52f, 0.48f, -0.88f,
			0.70f, -1.42f, 0.80f, -1.52f, 0.88f, -1.36f,
			1.00f, -0.76f
	);
	private static final KeyTrack SLASH_B_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.20f, 0.10f, -0.26f, 0.22f, -0.45f,
			0.27f, -0.46f, 0.38f, -0.28f, 0.48f, -0.20f,
			0.70f, -0.20f, 0.80f, -0.26f, 0.88f, -0.30f,
			1.00f, -0.48f
	);
	private static final KeyTrack SLASH_B_RARM_Y = new KeyTrack(false,
			0.00f, -1.12f, 0.10f, -1.22f, 0.22f, -1.20f,
			0.27f, -1.18f, 0.38f, -0.60f, 0.48f, 0.05f,
			0.70f, 0.95f, 0.80f, 1.10f, 0.88f, 1.02f,
			1.00f, 0.08f
	);
	private static final KeyTrack SLASH_B_RARM_Z = new KeyTrack(false,
			0.00f, -0.45f, 0.10f, -0.36f, 0.22f, -0.24f,
			0.27f, -0.22f, 0.38f, 0.00f, 0.48f, 0.22f,
			0.70f, 0.40f, 0.80f, 0.44f, 0.88f, 0.38f,
			1.00f, -0.09f
	);
	private static final KeyTrack SLASH_B_RLEG_X = new KeyTrack(false,
			0.00f, 0.40f, 0.22f, 0.36f, 0.48f, 0.34f, 0.80f, 0.30f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_B_LLEG_X = new KeyTrack(false,
			0.00f, -0.68f, 0.22f, -0.60f, 0.48f, -0.56f, 0.80f, -0.52f, 1.00f, -0.42f
	);
	private static final KeyTrack SLASH_B_LSHIN_X = new KeyTrack(false,
			0.00f, 0.62f, 0.22f, 0.54f, 0.48f, 0.36f, 0.80f, 0.32f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_B_RSHIN_X = new KeyTrack(false,
			0.00f, 0.14f, 0.22f, 0.16f, 0.48f, 0.12f, 0.80f, 0.12f, 1.00f, 0.14f
	);
	private static final KeyTrack SLASH_B_WRIST_PITCH = new KeyTrack(false,
			0.00f, 35.0f, 0.22f, 25.0f, 0.48f, 44.5f, 0.80f, 42.0f, 1.00f, -9.0f
	);
	private static final KeyTrack SLASH_B_WRIST_YAW = new KeyTrack(false,
			0.00f, -8.5f, 0.22f, -12.0f, 0.48f, -29.0f, 0.80f, -24.0f, 1.00f, 6.0f
	);
	private static final KeyTrack SLASH_B_WRIST_ROLL = new KeyTrack(false,
			0.00f, 34.0f, 0.22f, 45.5f, 0.48f, 22.0f, 0.80f, 26.0f, 1.00f, 0.0f
	);

	// Combo 3/3: Heroic leap + overhead windup (bent elbow coil) -> explosive downward cleave -> ground slam!
	private static final KeyTrack SLASH_V_RARM_X = new KeyTrack(false,
			0.00f, -1.46f, 0.12f, -1.84f, 0.30f, -2.32f,
			0.36f, -2.36f, 0.43f, -2.04f, 0.48f, -1.36f,
			0.64f, -0.48f, 0.72f, -0.40f, 1.00f, -0.76f
	);
	private static final KeyTrack SLASH_V_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.30f, 0.12f, -0.46f, 0.30f, -0.58f,
			0.36f, -0.56f, 0.43f, -0.26f, 0.48f, -0.16f,
			0.64f, -0.20f, 0.72f, -0.22f, 1.00f, -0.48f
	);
	private static final KeyTrack SLASH_V_RARM_Y = new KeyTrack(false,
			0.00f, 1.02f, 0.12f, 0.60f, 0.30f, 0.22f,
			0.36f, 0.18f, 0.43f, -0.05f, 0.48f, -0.36f,
			0.64f, -0.62f, 0.72f, -0.58f, 1.00f, 0.08f
	);
	private static final KeyTrack SLASH_V_RARM_Z = new KeyTrack(false,
			0.00f, 0.40f, 0.12f, 0.18f, 0.30f, -0.04f,
			0.36f, -0.06f, 0.43f, 0.18f, 0.48f, 0.36f,
			0.64f, 0.28f, 0.72f, 0.26f, 1.00f, -0.09f
	);
	private static final KeyTrack SLASH_V_LEAN = new KeyTrack(false,
			0.00f, -0.06f, 0.30f, -0.22f, 0.48f, 0.24f, 0.64f, 0.36f, 1.00f, 0.13f
	);
	private static final KeyTrack SLASH_V_OFFSET_Y = new KeyTrack(false,
			0.00f, 0.02f, 0.30f, 0.30f, 0.48f, 0.14f, 0.64f, -0.24f, 1.00f, -0.06f
	);
	private static final KeyTrack SLASH_V_SCALE_Y = new KeyTrack(false,
			0.00f, 1.03f, 0.30f, 1.09f, 0.48f, 1.04f, 0.64f, 0.86f, 1.00f, 1.00f
	);
	private static final KeyTrack SLASH_V_LLEG_X = new KeyTrack(false,
			0.00f, -0.52f, 0.30f, -0.78f, 0.48f, -0.78f, 0.64f, -0.88f, 1.00f, -0.42f
	);
	private static final KeyTrack SLASH_V_RLEG_X = new KeyTrack(false,
			0.00f, 0.32f, 0.30f, 0.34f, 0.48f, 0.40f, 0.64f, 0.46f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_V_LSHIN_X = new KeyTrack(false,
			0.00f, 0.42f, 0.30f, 0.88f, 0.48f, 0.68f, 0.64f, 0.92f, 1.00f, 0.24f
	);
	private static final KeyTrack SLASH_V_RSHIN_X = new KeyTrack(false,
			0.00f, 0.22f, 0.30f, 0.46f, 0.48f, 0.24f, 0.64f, 0.34f, 1.00f, 0.14f
	);
	private static final KeyTrack SLASH_V_WRIST_PITCH = new KeyTrack(false,
			0.00f, 42.0f, 0.30f, -14.0f, 0.48f, 52.0f, 0.64f, 55.0f, 1.00f, -9.0f
	);
	private static final KeyTrack SLASH_V_WRIST_YAW = new KeyTrack(false,
			0.00f, -22.0f, 0.30f, -4.0f, 0.48f, -6.0f, 0.64f, -4.0f, 1.00f, 6.0f
	);
	private static final KeyTrack SLASH_V_WRIST_ROLL = new KeyTrack(false,
			0.00f, 24.0f, 0.30f, -4.0f, 0.48f, -16.0f, 0.64f, -18.0f, 1.00f, 0.0f
	);

	// Thrust: Tight coiled elbow pull-back -> explosive dead-straight full-extension lunge!
	private static final KeyTrack THRUST_RARM_X = new KeyTrack(false,
			0.00f, -0.76f, 0.10f, -0.20f, 0.24f,  0.46f,
			0.30f,  0.50f, 0.38f, -0.45f, 0.46f, -1.12f,
			0.52f, -1.22f, 0.64f, -1.14f, 1.00f, -0.76f
	);
	private static final KeyTrack THRUST_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.48f, 0.10f, -0.84f, 0.24f, -1.12f,
			0.30f, -1.14f, 0.38f, -0.40f, 0.46f, -0.08f,
			0.52f, -0.08f, 0.64f, -0.10f, 1.00f, -0.48f
	);
	private static final KeyTrack THRUST_RARM_Y = new KeyTrack(false,
			0.00f, 0.08f, 0.10f, 0.28f, 0.24f, 0.60f,
			0.30f, 0.64f, 0.38f, 0.05f, 0.46f, -0.52f,
			0.52f, -0.58f, 0.64f, -0.45f, 1.00f, 0.08f
	);
	private static final KeyTrack THRUST_RARM_Z = new KeyTrack(false,
			0.00f, -0.09f, 0.24f, 0.00f, 0.46f, -0.14f, 0.64f, -0.25f, 1.00f, -0.09f
	);
	private static final KeyTrack THRUST_BODY_Y = new KeyTrack(false,
			0.00f, -0.30f, 0.10f, 0.00f, 0.24f, 0.58f,
			0.30f, 0.62f, 0.38f, 0.04f, 0.46f, -0.42f,
			0.52f, -0.46f, 0.64f, -0.38f, 1.00f, -0.30f
	);
	private static final KeyTrack THRUST_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.24f, 0.91f, 0.46f, 1.05f, 0.64f, 0.97f, 1.00f, 1.00f
	);
	private static final KeyTrack THRUST_LLEG_X = new KeyTrack(false,
			0.00f, -0.42f, 0.24f, -0.36f, 0.46f, -0.94f, 0.64f, -0.84f, 1.00f, -0.42f
	);
	private static final KeyTrack THRUST_RLEG_X = new KeyTrack(false,
			0.00f, 0.24f, 0.24f, 0.28f, 0.46f, 0.54f, 0.64f, 0.46f, 1.00f, 0.24f
	);
	private static final KeyTrack THRUST_LSHIN_X = new KeyTrack(false,
			0.00f, 0.24f, 0.24f, 0.30f, 0.46f, 0.88f, 0.64f, 0.76f, 1.00f, 0.24f
	);
	private static final KeyTrack THRUST_RSHIN_X = new KeyTrack(false,
			0.00f, 0.14f, 0.24f, 0.22f, 0.46f, 0.10f, 0.64f, 0.12f, 1.00f, 0.14f
	);
	private static final KeyTrack THRUST_WRIST_PITCH = new KeyTrack(false,
			0.00f, -9.0f, 0.24f, 15.0f, 0.46f, 54.0f, 0.64f, 58.0f, 1.00f, -9.0f
	);
	private static final KeyTrack THRUST_WRIST_YAW = new KeyTrack(false,
			0.00f, 6.0f, 0.24f, 7.5f, 0.46f, 4.0f, 0.64f, 2.5f, 1.00f, 6.0f
	);
	private static final KeyTrack THRUST_WRIST_ROLL = new KeyTrack(false,
			0.00f, 0.0f, 0.24f, -5.0f, 0.46f, -5.0f, 0.64f, -4.0f, 1.00f, 0.0f
	);

	// Spin Attack: Spins FORWARD (+2*PI in PoseStack pre-scale = blade sweeps across front!)
	private static final KeyTrack SPIN_ANGLE = new KeyTrack(false,
			0.00f, 0.00f, 0.20f, 0.38f, 0.52f, 4.45f, 0.80f, 6.18f, 1.00f, 6.2831855f
	);
	private static final KeyTrack SPIN_BODY_Y = new KeyTrack(false,
			0.00f, -0.30f, 0.22f, 0.76f, 0.52f, -0.20f, 0.78f, -0.44f, 1.00f, -0.30f
	);
	private static final KeyTrack SPIN_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.22f, 0.87f, 0.52f, 1.05f, 0.78f, 0.92f, 1.00f, 1.00f
	);
	private static final KeyTrack SPIN_RARM_X = new KeyTrack(false,
			0.00f, -0.76f, 0.22f, -0.38f, 0.52f, -0.82f, 0.78f, -0.42f, 1.00f, -0.76f
	);
	private static final KeyTrack SPIN_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.48f, 0.22f, -0.60f, 0.52f, -0.15f, 0.78f, -0.18f, 1.00f, -0.48f
	);
	private static final KeyTrack SPIN_RARM_Y = new KeyTrack(false,
			0.00f, 0.08f, 0.22f, 1.22f, 0.52f, 0.15f, 0.78f, -0.48f, 1.00f, 0.08f
	);
	private static final KeyTrack SPIN_RARM_Z = new KeyTrack(false,
			0.00f, -0.09f, 0.22f, -0.10f, 0.52f, -0.54f, 0.78f, -0.82f, 1.00f, -0.09f
	);
	private static final KeyTrack SPIN_LLEG_X = new KeyTrack(false,
			0.00f, -0.42f, 0.22f, -0.68f, 0.52f, -0.54f, 0.78f, -0.62f, 1.00f, -0.42f
	);
	private static final KeyTrack SPIN_RLEG_X = new KeyTrack(false,
			0.00f, 0.24f, 0.22f, 0.34f, 0.52f, 0.28f, 0.78f, 0.34f, 1.00f, 0.24f
	);
	private static final KeyTrack SPIN_WRIST_PITCH = new KeyTrack(false,
			0.00f, -9.0f, 0.22f, 4.0f, 0.52f, 58.0f, 0.78f, 56.5f, 1.00f, -9.0f
	);
	private static final KeyTrack SPIN_WRIST_YAW = new KeyTrack(false,
			0.00f, 6.0f, 0.22f, 8.5f, 0.52f, 20.0f, 0.78f, 10.5f, 1.00f, 6.0f
	);
	private static final KeyTrack SPIN_WRIST_ROLL = new KeyTrack(false,
			0.00f, 0.0f, 0.22f, -54.0f, 0.52f, -30.5f, 0.78f, -24.5f, 1.00f, 0.0f
	);

	// Jump Attack: Spring coil -> soaring overhead -> mid-air downward cleave -> slam squash!
	private static final KeyTrack JUMP_ATK_RARM_X = new KeyTrack(false,
			0.00f, -1.24f, 0.26f, -2.36f, 0.60f, -1.38f, 1.00f, -0.35f
	);
	private static final KeyTrack JUMP_ATK_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.42f, 0.26f, -0.56f, 0.60f, -0.16f, 1.00f, -0.28f
	);
	private static final KeyTrack JUMP_ATK_RARM_Y = new KeyTrack(false,
			0.00f, 0.22f, 0.26f, 0.26f, 0.60f, -0.40f, 1.00f, -0.82f
	);
	private static final KeyTrack JUMP_ATK_RARM_Z = new KeyTrack(false,
			0.00f, 0.13f, 0.26f, -0.04f, 0.60f, 0.40f, 1.00f, 0.31f
	);
	private static final KeyTrack JUMP_ATK_LARM_X = new KeyTrack(false,
			0.00f, -0.64f, 0.26f, -1.04f, 0.60f, -0.06f, 1.00f, 0.32f
	);
	private static final KeyTrack JUMP_ATK_LEAN = new KeyTrack(false,
			0.00f, 0.24f, 0.26f, -0.18f, 0.60f, 0.28f, 1.00f, 0.40f
	);
	private static final KeyTrack JUMP_ATK_SCALE_Y = new KeyTrack(false,
			0.00f, 0.88f, 0.26f, 1.09f, 0.60f, 1.04f, 1.00f, 0.86f
	);
	private static final KeyTrack JUMP_ATK_RLEG_X = new KeyTrack(false,
			0.00f, 0.30f, 0.26f, 0.36f, 0.60f, 0.42f, 1.00f, 0.44f
	);
	private static final KeyTrack JUMP_ATK_LLEG_X = new KeyTrack(false,
			0.00f, -0.78f, 0.26f, -0.84f, 0.60f, -0.88f, 1.00f, -0.92f
	);
	private static final KeyTrack JUMP_ATK_WRIST_PITCH = new KeyTrack(false,
			0.00f, -14.0f, 0.26f, -14.5f, 0.60f, 51.5f, 1.00f, 55.0f
	);
	private static final KeyTrack JUMP_ATK_WRIST_ROLL = new KeyTrack(false,
			0.00f, 0.0f, 0.26f, -4.0f, 0.60f, -17.0f, 1.00f, -22.5f
	);

	// Complete full 360-degree air rotation by u = 0.75 so legs spot ground cleanly before touchdown.
	private static final KeyTrack BACKFLIP_PITCH = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, 0.08f, 0.46f, 3.28f, 0.74f, 6.2831855f, 1.00f, 6.2831855f
	);
	private static final KeyTrack BACKFLIP_OFFSET_Y = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.11f, 0.46f, 0.14f, 0.72f, 0.04f, 0.84f, -0.14f, 1.00f, 0.00f
	);
	private static final KeyTrack BACKFLIP_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.14f, 0.85f, 0.46f, 1.05f, 0.72f, 1.06f, 0.84f, 0.85f, 1.00f, 1.00f
	);
	private static final KeyTrack BACKFLIP_RLEG_X = new KeyTrack(false,
			0.00f, 0.24f, 0.14f, -0.72f, 0.46f, -1.48f, 0.72f, 0.20f, 0.84f, 0.34f, 1.00f, 0.24f
	);
	private static final KeyTrack BACKFLIP_LLEG_X = new KeyTrack(false,
			0.00f, -0.42f, 0.14f, -0.82f, 0.46f, -1.42f, 0.72f, -0.54f, 0.84f, -0.76f, 1.00f, -0.42f
	);
	private static final KeyTrack BACKFLIP_KNEE_X = new KeyTrack(false,
			0.00f, 0.20f, 0.14f, 0.68f, 0.46f, 1.38f, 0.72f, 0.42f, 0.84f, 0.72f, 1.00f, 0.20f
	);

	private static final KeyTrack ROLL_PITCH = new KeyTrack(false,
			0.00f, 0.00f, 0.10f, -0.36f, 0.46f, -3.45f, 0.80f, -6.2831855f, 1.00f, -6.2831855f
	);

	// Z-Target / Aim + Lateral Roll: fast 360-degree tucked barrel-roll while keeping gaze locked on target.
	private static final KeyTrack SIDE_ROLL_Z = new KeyTrack(false,
			0.00f, 0.00f, 0.08f, -0.18f, 0.44f, 3.28f, 0.78f, 6.2831855f, 1.00f, 6.2831855f
	);
	private static final KeyTrack SIDE_ROLL_OFFSET_Y = new KeyTrack(false,
			0.00f, 0.00f, 0.12f, -0.14f, 0.44f, -0.26f, 0.72f, -0.10f, 0.85f, -0.15f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDE_ROLL_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.10f, 0.85f, 0.44f, 0.88f, 0.72f, 1.05f, 0.85f, 0.87f, 1.00f, 1.00f
	);
	private static final KeyTrack SIDE_ROLL_LEAN_X = new KeyTrack(false,
			0.00f, 0.10f, 0.14f, 0.22f, 0.44f, 0.30f, 0.74f, 0.24f, 1.00f, 0.12f
	);
	private static final KeyTrack SIDE_ROLL_BODY_Y = new KeyTrack(false,
			0.00f, 0.00f, 0.16f, -0.34f, 0.48f, -0.28f, 0.78f, -0.16f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDE_ROLL_LEAD_LEG_X = new KeyTrack(false,
			0.00f, -0.18f, 0.14f, -1.16f, 0.44f, -1.38f, 0.72f, -0.68f, 0.86f, -0.32f, 1.00f, -0.18f
	);
	private static final KeyTrack SIDE_ROLL_TRAIL_LEG_X = new KeyTrack(false,
			0.00f, 0.22f, 0.14f, -0.96f, 0.44f, -1.24f, 0.72f, -0.34f, 0.86f, 0.26f, 1.00f, 0.22f
	);
	private static final KeyTrack SIDE_ROLL_LEAD_KNEE_X = new KeyTrack(false,
			0.00f, 0.24f, 0.14f, 1.32f, 0.44f, 1.56f, 0.72f, 0.74f, 0.86f, 0.46f, 1.00f, 0.24f
	);
	private static final KeyTrack SIDE_ROLL_TRAIL_KNEE_X = new KeyTrack(false,
			0.00f, 0.20f, 0.14f, 1.24f, 0.44f, 1.50f, 0.72f, 0.62f, 0.86f, 0.34f, 1.00f, 0.20f
	);
	private static final KeyTrack SIDE_ROLL_LEAD_LEG_Z = new KeyTrack(false,
			0.00f, 0.08f, 0.16f, 0.26f, 0.44f, 0.18f, 0.74f, 0.38f, 0.88f, 0.20f, 1.00f, 0.08f
	);
	private static final KeyTrack SIDE_ROLL_TRAIL_LEG_Z = new KeyTrack(false,
			0.00f, 0.06f, 0.16f, 0.12f, 0.44f, 0.06f, 0.74f, 0.20f, 0.88f, 0.12f, 1.00f, 0.06f
	);

	// Iconic Ocarina of Time lateral acrobatic Side-Hop with mid-air scissor switch + squash/stretch.
	private static final KeyTrack SIDEHOP_BANK_Z = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.10f, 0.36f, 0.28f, 0.60f, 0.18f, 0.80f, 0.06f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDEHOP_OFFSET_Y = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.10f, 0.36f, 0.12f, 0.60f, 0.08f, 0.80f, -0.12f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDEHOP_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.14f, 0.84f, 0.36f, 1.09f, 0.60f, 1.05f, 0.80f, 0.84f, 1.00f, 1.00f
	);
	private static final KeyTrack SIDEHOP_TORSO_YAW_MULT = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.24f, 0.36f, 0.32f, 0.60f, 0.20f, 0.80f, 0.12f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDEHOP_LEAD_LEG_X = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.62f, 0.36f, -1.16f, 0.60f, -0.58f, 0.80f, -0.68f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDEHOP_TRAIL_LEG_X = new KeyTrack(false,
			0.00f, 0.00f, 0.14f, -0.66f, 0.36f, 0.16f, 0.60f, -0.90f, 0.80f, 0.26f, 1.00f, 0.00f
	);
	private static final KeyTrack SIDEHOP_LEAD_KNEE_X = new KeyTrack(false,
			0.00f, 0.12f, 0.14f, 0.64f, 0.36f, 1.04f, 0.60f, 0.48f, 0.80f, 0.66f, 1.00f, 0.12f
	);
	private static final KeyTrack SIDEHOP_TRAIL_KNEE_X = new KeyTrack(false,
			0.00f, 0.12f, 0.14f, 0.64f, 0.36f, 0.36f, 0.60f, 0.92f, 0.80f, 0.54f, 1.00f, 0.12f
	);
	private static final KeyTrack SIDEHOP_LEAD_LEG_Z = new KeyTrack(false,
			0.00f, 0.08f, 0.14f, 0.26f, 0.36f, 0.46f, 0.60f, 0.34f, 0.80f, 0.24f, 1.00f, 0.08f
	);
	private static final KeyTrack SIDEHOP_TRAIL_LEG_Z = new KeyTrack(false,
			0.00f, 0.08f, 0.14f, 0.14f, 0.36f, 0.26f, 0.60f, 0.16f, 0.80f, 0.24f, 1.00f, 0.08f
	);

	// Ocarina of Time Ledge Climb-Up: press chest above rim -> tuck knee onto block -> stand recovery.
	private static final KeyTrack CLIMB_LEAN_X = new KeyTrack(false,
			0.00f, 0.07f, 0.28f, 0.32f, 0.58f, 0.42f, 0.82f, 0.16f, 1.00f, 0.02f
	);
	private static final KeyTrack CLIMB_OFFSET_Y = new KeyTrack(false,
			0.00f, 0.00f, 0.28f, -0.06f, 0.58f, -0.15f, 0.82f, -0.05f, 1.00f, 0.00f
	);
	private static final KeyTrack CLIMB_SCALE_Y = new KeyTrack(false,
			0.00f, 1.00f, 0.28f, 0.94f, 0.58f, 0.89f, 0.82f, 0.96f, 1.00f, 1.00f
	);
	private static final KeyTrack CLIMB_HEAD_X = new KeyTrack(false,
			0.00f, -0.26f, 0.28f, 0.06f, 0.58f, 0.14f, 0.82f, 0.04f, 1.00f, 0.01f
	);
	private static final KeyTrack CLIMB_RARM_X = new KeyTrack(false,
			0.00f, -2.62f, 0.28f, -1.16f, 0.58f, 0.38f, 0.82f, 0.18f, 1.00f, 0.03f
	);
	private static final KeyTrack CLIMB_LARM_X = new KeyTrack(false,
			0.00f, -2.62f, 0.28f, -1.28f, 0.58f, 0.34f, 0.82f, 0.14f, 1.00f, 0.04f
	);
	private static final KeyTrack CLIMB_RFOREARM_X = new KeyTrack(false,
			0.00f, -0.46f, 0.28f, -1.24f, 0.58f, -0.42f, 0.82f, -0.24f, 1.00f, -0.14f
	);
	private static final KeyTrack CLIMB_LFOREARM_X = new KeyTrack(false,
			0.00f, -0.46f, 0.28f, -1.18f, 0.58f, -0.40f, 0.82f, -0.22f, 1.00f, -0.12f
	);
	private static final KeyTrack CLIMB_RLEG_X = new KeyTrack(false,
			0.00f, 0.08f, 0.28f, -1.18f, 0.58f, -0.94f, 0.82f, -0.28f, 1.00f, -0.04f
	);
	private static final KeyTrack CLIMB_LLEG_X = new KeyTrack(false,
			0.00f, 0.04f, 0.28f, 0.12f, 0.58f, -0.58f, 0.82f, -0.14f, 1.00f, 0.01f
	);
	private static final KeyTrack CLIMB_RSHIN_X = new KeyTrack(false,
			0.00f, 0.28f, 0.28f, 1.48f, 0.58f, 1.24f, 0.82f, 0.38f, 1.00f, 0.05f
	);
	private static final KeyTrack CLIMB_LSHIN_X = new KeyTrack(false,
			0.00f, 0.34f, 0.28f, 0.56f, 0.58f, 0.96f, 0.82f, 0.26f, 1.00f, 0.06f
	);

	private static final class Pose {
		float headX, headY, headZ;
		float bodyX, bodyY, bodyZ;
		float rArmX, rArmY, rArmZ;
		float lArmX, lArmY, lArmZ;
		float rForearmX, rForearmY;
		float lForearmX, lForearmY;
		float rLegX, rLegY, rLegZ;
		float lLegX, lLegY, lLegZ;
		float rShinX, lShinX;
		float wholeLeanX;
		float wholeBankZ;
		float wholeOffsetY;
		float wholeShiftX;
		float wholeSpinY;
		float wholeFlipX;
		float wholeRollZ;
		float wholeScaleY = 1.0f;
	}

	// Critically-damped spring for smooth NON-CYCLIC state/idle transitions.
	private static final class SpringPose {
		private static final int COUNT = 32;
		final float[] v = new float[COUNT];
		final float[] vel = new float[COUNT];

		void snap(Pose p) {
			pack(p, v);
			for (int i = 0; i < COUNT; i++) vel[i] = 0.0f;
		}

		void step(Pose target, float headOmega, float bodyOmega, float armOmega, float legOmega, float wholeOmega, float dt) {
			float[] t = new float[COUNT];
			pack(target, t);
			for (int i = 0; i < 3; i++) stepOne(i, t[i], headOmega, dt);
			for (int i = 3; i < 6; i++) stepOne(i, t[i], bodyOmega, dt);
			for (int i = 6; i < 16; i++) stepOne(i, t[i], armOmega, dt);
			for (int i = 16; i < 24; i++) stepOne(i, t[i], legOmega, dt);
			for (int i = 24; i < COUNT; i++) stepOne(i, t[i], wholeOmega, dt);
		}

		private void stepOne(int i, float target, float omega, float dt) {
			float diff = v[i] - target;
			float j = vel[i] + omega * diff;
			float e = (float) Math.exp(-omega * dt);
			v[i] = target + (diff + j * dt) * e;
			vel[i] = (vel[i] - j * omega * dt) * e;
		}

		void unpack(Pose out) {
			out.headX = v[0]; out.headY = v[1]; out.headZ = v[2];
			out.bodyX = v[3]; out.bodyY = v[4]; out.bodyZ = v[5];
			out.rArmX = v[6]; out.rArmY = v[7]; out.rArmZ = v[8];
			out.lArmX = v[9]; out.lArmY = v[10]; out.lArmZ = v[11];
			out.rForearmX = v[12]; out.rForearmY = v[13];
			out.lForearmX = v[14]; out.lForearmY = v[15];
			out.rLegX = v[16]; out.rLegY = v[17]; out.rLegZ = v[18];
			out.lLegX = v[19]; out.lLegY = v[20]; out.lLegZ = v[21];
			out.rShinX = v[22]; out.lShinX = v[23];
			out.wholeLeanX = v[24];
			out.wholeBankZ = v[25];
			out.wholeOffsetY = v[26];
			out.wholeShiftX = v[27];
			out.wholeSpinY = v[28];
			out.wholeFlipX = v[29];
			out.wholeRollZ = v[30];
			out.wholeScaleY = v[31];
		}

		private static void pack(Pose p, float[] a) {
			a[0] = p.headX; a[1] = p.headY; a[2] = p.headZ;
			a[3] = p.bodyX; a[4] = p.bodyY; a[5] = p.bodyZ;
			a[6] = p.rArmX; a[7] = p.rArmY; a[8] = p.rArmZ;
			a[9] = p.lArmX; a[10] = p.lArmY; a[11] = p.lArmZ;
			a[12] = p.rForearmX; a[13] = p.rForearmY;
			a[14] = p.lForearmX; a[15] = p.lForearmY;
			a[16] = p.rLegX; a[17] = p.rLegY; a[18] = p.rLegZ;
			a[19] = p.lLegX; a[20] = p.lLegY; a[21] = p.lLegZ;
			a[22] = p.rShinX; a[23] = p.lShinX;
			a[24] = p.wholeLeanX;
			a[25] = p.wholeBankZ;
			a[26] = p.wholeOffsetY;
			a[27] = p.wholeShiftX;
			a[28] = p.wholeSpinY;
			a[29] = p.wholeFlipX;
			a[30] = p.wholeRollZ;
			a[31] = p.wholeScaleY;
		}
	}

	private static final SpringPose idleSpring = new SpringPose();
	private static final Pose idleTarget = new Pose();
	private static final Pose idleSolved = new Pose();
	private static final Pose locoPose = new Pose();
	private static final Pose groundedPose = new Pose();
	private static final Pose jumpPose = new Pose();
	private static final SpringPose locoSpring = new SpringPose();
	private static boolean locoSpringInitialized = false;
	private static final Pose locoSolved = new Pose();
	private static final Pose combatPose = new Pose();
	private static final Pose lastCombatPose = new Pose();
	private static boolean hasLastCombatPose = false;
	private static final Pose combatEntryStartPose = new Pose();
	private static boolean wasActiveCombat = false;
	private static ZeldaCombatSystem.SwordMove prevCombatMove = ZeldaCombatSystem.SwordMove.NONE;
	private static ZeldaCombatSystem.DodgeMove prevCombatDodge = ZeldaCombatSystem.DodgeMove.NONE;
	private static float combatEntryBlend = 1.0f;
	private static final Pose ledgePose = new Pose();
	private static final Pose lastLedgePose = new Pose();
	private static boolean hasLastLedgePose = false;
	private static float ledgeBlend = 0.0f;
	private static float ledgeClock = 0.0f;
	private static final Pose finalPose = new Pose();

	private static long lastSolvedFrame = -1L;
	private static long lastWorldNanos = 0L;
	private static boolean wasInHitstop = false;
	private static float chargeTrembleClock = 0.0f;
	private static float lastLimbSwingAmount = 0.0f;
	private static boolean idleInitialized = false;
	private static boolean hasPrevPos = false;
	private static double prevSmoothX = 0.0;
	private static double prevSmoothZ = 0.0;

	private static float engineWeight = 0.0f;
	private static float moveBlend = 0.0f; // Smooth Idle <-> Locomotion crossfade
	private static float idleClock = 0.0f;

	// True animation-driven Root Motion + world-displacement trackers (0% skiing).
	private static float inputMagnitude = 0.0f;
	private static float inputTurnStepDeg = 0.0f;
	private static float inputTargetYaw = 0.0f;
	private static float rootSpeedBps = 0.0f;
	private static float rootHeadingYaw = 0.0f;
	private static boolean hasRootHeading = false;
	private static float rootCycle = 0.0f;
	private static float rootLastCmdTickDs = 0.0f;

	private static float measuredSpeedBps = 0.0f;
	private static float smoothSpeedNorm = 0.0f;
	private static float smoothTurnRate = 0.0f;
	private static float smoothBank = 0.0f;
	private static float smoothHeadLead = 0.0f;
	private static float prevBodyYaw = 0.0f;
	private static boolean hasPrevYaw = false;
	private static float gaitCycle = 0.0f; // 0..1 exact full 2-step cycle
	/** Leg length in world blocks: 12 px * 0.9375 (player render scale) / 16. */
	private static final float GAIT_LEG_LEN = 0.703f;
	private static float gaitFrontRad = 0.50f;
	private static float gaitBackRad = 0.44f;
	private static float gaitStanceFrac = 0.60f;

	// Jump / Fall / Landing Impact animation trackers.
	private static boolean airborneActive = false;
	private static float airGraceTime = 0.0f;
	private static float airTime = 0.0f;
	private static float airBlend = 0.0f;
	private static float smoothTickVy = 0.0f;
	private static float peakFallSpeed = 0.0f;
	private static float jumpLeadSign = 1.0f;
	private static float jumpHorizNorm = 0.0f;
	private static float landElapsed = 0.0f;
	private static float landDuration = 0.0f;
	private static float landStrength = 0.0f;

	// Combat guard & Z-Targeting strafe trackers.
	private static float swordGuardBlend = 0.0f;
	private static float combatPoseBlend = 0.0f;
	private static float strafeForwardRel = 1.0f;
	private static float strafeRightRel = 0.0f;

	// Peripheral enemy glance tracking while moving.
	private static long lastEnemySenseNanos = 0L;
	private static boolean enemyVisibleMove = false;
	private static float enemyYawTarget = 0.0f;
	private static float enemyPitchTarget = 0.0f;
	private static float smoothEnemyYaw = 0.0f;
	private static float smoothEnemyPitch = 0.0f;
	private static float enemyLookBlend = 0.0f;

	// Context sensing for Idle.
	private static long lastContextSenseNanos = 0L;
	private static IdleScenario activeScenario = IdleScenario.NEUTRAL;
	private static IdleScenario prevScenario = IdleScenario.NEUTRAL;
	private static float idleTargetRelYaw = 0.0f;
	private static float idleTargetRelPitch = 0.0f;

	// Non-periodic crafted Idle one-shot timers.
	private static NeutralAction neutralAction = NeutralAction.NONE;
	private static float neutralActionTime = 0.0f;
	private static float neutralActionDuration = 0.0f;
	private static float neutralNextCooldown = 2.6f;
	private static int neutralSign = 1;
	private static int lastNeutralPick = -1;

	private static boolean coldSneezing = false;
	private static float coldSneezeTime = 0.0f;
	private static float coldSneezeCooldown = 4.6f;

	private static HotAction hotAction = HotAction.NONE;
	private static float hotActionTime = 0.0f;
	private static float hotActionDuration = 0.0f;
	private static float hotActionCooldown = 3.2f;

	private static boolean rainChecking = false;
	private static float rainCheckTime = 0.0f;
	private static float rainCheckCooldown = 3.6f;

	private static boolean cliffGlancing = false;
	private static float cliffGlanceTime = 0.0f;
	private static float cliffGlanceCooldown = 2.4f;
	private static float cliffGlanceSign = 1.0f;

	private static boolean alertShifting = false;
	private static float alertShiftTime = 0.0f;
	private static float alertShiftCooldown = 3.8f;

	// Equipment menu preview state (animates smoothly even when game is paused).
	private static final SpringPose menuSpring = new SpringPose();
	private static final Pose menuTarget = new Pose();
	private static final Pose menuSolved = new Pose();
	private static boolean menuInitialized = false;
	private static float menuClock = 0.0f;
	private static long lastMenuNanos = 0L;
	private static float menuShiftCooldown = 4.2f;
	private static float menuShiftTimer = 0.0f;
	private static float menuShiftDir = 1.0f;

	private ZeldaAnimationEngine() {}

	public static void triggerDodgeLanding(float strength) {
		landStrength = Math.max(landStrength, Mth.clamp(strength, 0.35f, 0.90f));
		landDuration = 0.22f;
		landElapsed = 0.0f;
	}

	public static void recordInput(float magnitude, float turnStepDeg, float targetYaw) {
		inputMagnitude = Mth.clamp(magnitude, 0.0f, 1.0f);
		inputTurnStepDeg = turnStepDeg;
		inputTargetYaw = targetYaw;
	}

	public static void recordLimbSwing(float limbSwingAmount) {
		if (limbSwingAmount >= 0.0f) {
			lastLimbSwingAmount = limbSwingAmount;
		}
	}

	public static void resetWorld() {
		engineWeight = 0.0f;
		moveBlend = 0.0f;
		locoSpringInitialized = false;
		wasActiveCombat = false;
		prevCombatMove = ZeldaCombatSystem.SwordMove.NONE;
		prevCombatDodge = ZeldaCombatSystem.DodgeMove.NONE;
		combatEntryBlend = 1.0f;
		hasLastCombatPose = false;
		ledgeBlend = 0.0f;
		hasLastLedgePose = false;
		ledgeClock = 0.0f;
		ZeldaLedgeSystem.reset();
		wasInHitstop = false;
		chargeTrembleClock = 0.0f;
		lastLimbSwingAmount = 0.0f;
		inputMagnitude = 0.0f;
		inputTurnStepDeg = 0.0f;
		rootSpeedBps = 0.0f;
		hasRootHeading = false;
		rootCycle = 0.0f;
		rootLastCmdTickDs = 0.0f;
		measuredSpeedBps = 0.0f;
		smoothSpeedNorm = 0.0f;
		smoothTurnRate = 0.0f;
		smoothBank = 0.0f;
		smoothHeadLead = 0.0f;
		enemyVisibleMove = false;
		enemyLookBlend = 0.0f;
		hasPrevYaw = false;
		hasPrevPos = false;
		idleInitialized = false;
		lastSolvedFrame = -1L;
		airborneActive = false;
		airGraceTime = 0.0f;
		airTime = 0.0f;
		airBlend = 0.0f;
		smoothTickVy = 0.0f;
		peakFallSpeed = 0.0f;
		jumpLeadSign = 1.0f;
		jumpHorizNorm = 0.0f;
		landElapsed = 0.0f;
		landDuration = 0.0f;
		landStrength = 0.0f;
		swordGuardBlend = 0.0f;
		combatPoseBlend = 0.0f;
		strafeForwardRel = 1.0f;
		strafeRightRel = 0.0f;
		activeScenario = IdleScenario.NEUTRAL;
		prevScenario = IdleScenario.NEUTRAL;
		resetFidgetTimers();
	}

	/**
	 * True when grounded Zelda Root Motion should authoritatively drive entity displacement.
	 * Yields cleanly to vanilla physics in water, air, climbing, riding, crouching, or knockback.
	 */
	public static boolean shouldDriveRootMotion(LocalPlayer player) {
		return EngineState.isActive()
				&& !ZeldaChestCutscene.isActive()
				&& !ZeldaLedgeSystem.isActive()
				&& !ZeldaCombatSystem.isAirDodge()
				&& player.onGround()
				&& !player.getAbilities().flying
				&& !player.isInWater()
				&& !player.isInLava()
				&& !player.isSwimming()
				&& !player.onClimbable()
				&& !player.isFallFlying()
				&& !player.isPassenger()
				&& !player.isSleeping()
				&& !player.isDeadOrDying()
				&& !player.isCrouching()
				&& !player.isUsingItem()
				&& player.hurtTime <= 0;
	}

	/** Keeps Root Motion velocity synced when yielding to air, water, ice, or cutscenes. */
	public static void syncRootMotionFromPhysics(LocalPlayer player) {
		rootLastCmdTickDs = 0.0f;
		if (ZeldaChestCutscene.isActive() || player.isSleeping() || player.isDeadOrDying()) {
			rootSpeedBps = 0.0f;
			hasRootHeading = false;
			return;
		}
		float horizBps = (float) (player.getDeltaMovement().horizontalDistance() * 20.0D);
		rootSpeedBps = Mth.clamp(horizBps, 0.0f, 9.0f);
		rootHeadingYaw = player.getYRot();
		hasRootHeading = true;
	}

	/**
	 * Advances the 20 Hz Root Motion momentum solver so animation stride phase and
	 * Zelda acceleration/cornering/stop envelopes drive the player's horizontal displacement.
	 */
	public static Vec3 stepRootMotionTick(LocalPlayer player) {
		final float tickDt = 0.05f;

		if (ZeldaCombatSystem.isInHitstop()) {
			rootLastCmdTickDs = 0.0f;
			return Vec3.ZERO;
		}

		if (ZeldaCombatSystem.overridesLocomotionRootMotion()) {
			Vec3 combatVel = ZeldaCombatSystem.stepCombatRootMotion(player);
			double dsTick = combatVel.horizontalDistance();
			rootSpeedBps = (float) (dsTick / tickDt);
			rootLastCmdTickDs = (float) dsTick;
			rootHeadingYaw = ZeldaCombatSystem.isRolling()
					? ZeldaCombatSystem.getDodgeMotionYawDeg()
					: ZeldaCombatSystem.getLockedSwingYawDeg();
			hasRootHeading = true;
			return combatVel;
		}

		// Feed back real post-collision displacement so running into a wall drops momentum cleanly.
		if (rootLastCmdTickDs > 0.01f) {
			double actualPrevDs = player.getDeltaMovement().horizontalDistance();
			if (actualPrevDs < rootLastCmdTickDs * 0.45D) {
				rootSpeedBps = Math.min(rootSpeedBps, (float) (actualPrevDs / tickDt));
			}
		}

		boolean targeting = ZeldaTargetingSystem.isFocusActive();

		// Authentic Ocarina-style dual-zone stick curve (Walk -> Jog).
		float desiredBps = 0.0f;
		if (inputMagnitude > 0.03f) {
			if (inputMagnitude <= 0.45f) {
				float t = (inputMagnitude - 0.03f) / 0.42f;
				desiredBps = Mth.lerp(t, 1.55f, 2.75f);
			} else {
				float t = smoothstep(0.45f, 1.0f, inputMagnitude);
				desiredBps = Mth.lerp(t, 2.75f, targeting ? 3.85f : 4.55f);
			}
			double attrSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
			float attrScale = Mth.clamp((float) (attrSpeed / 0.10000000149011612D), 0.35f, 2.0f);
			desiredBps *= attrScale;

			if (!targeting) {
				float yawDiff = Math.abs(Mth.wrapDegrees(inputTargetYaw - player.getYRot()));
				float turnPenalty = Mth.clamp(1.0f - yawDiff / 145.0f, 0.25f, 1.0f);
				desiredBps *= turnPenalty;
			}
		}

		// Weighted Zelda momentum envelope: crisp launch, smooth speed changes, grounded stop settle.
		if (desiredBps > rootSpeedBps) {
			float accelRate = (desiredBps > 2.9f) ? 16.5f : 13.5f;
			rootSpeedBps = damp(rootSpeedBps, desiredBps, accelRate, tickDt);
		} else if (desiredBps <= 0.02f) {
			float decelRate = (rootSpeedBps > 2.8f) ? 17.5f : 22.0f;
			rootSpeedBps = damp(rootSpeedBps, 0.0f, decelRate, tickDt);
			if (rootSpeedBps < 0.14f) {
				rootSpeedBps = 0.0f;
			}
		} else {
			rootSpeedBps = damp(rootSpeedBps, desiredBps, 14.0f, tickDt);
		}

		// Curve momentum: follow stick travel direction when Z-Targeting, or body facing when free-running.
		float desiredHeading = targeting ? inputTargetYaw : player.getYRot();
		if (!hasRootHeading || rootSpeedBps < 0.20f) {
			rootHeadingYaw = desiredHeading;
			hasRootHeading = true;
		} else {
			float headingDiff = Mth.wrapDegrees(desiredHeading - rootHeadingYaw);
			float alignFactor = 1.0f - (float) Math.exp((targeting ? -28.0f : -24.0f) * tickDt);
			rootHeadingYaw = Mth.wrapDegrees(rootHeadingYaw + headingDiff * alignFactor);
		}

		if (rootSpeedBps <= 0.001f) {
			rootLastCmdTickDs = 0.0f;
			rootCycle = 0.0f;
			return Vec3.ZERO;
		}

		float speedNorm = Mth.clamp(rootSpeedBps / 4.55f, 0.0f, 1.25f);
		float blendEst = Mth.clamp(speedNorm / 0.07f, 0.0f, 1.0f);
		float jog = smoothstep(0.36f, 0.78f, speedNorm);
		float strideScale = Mth.clamp(speedNorm / 0.28f, 0.48f, 1.0f);
		float basePeak = Mth.lerp(Math.abs(strafeRightRel), Mth.lerp(jog, 0.48f, 0.74f), 0.54f);
		float effectiveLegAmp = Math.max(0.18f, basePeak * strideScale * Math.max(0.25f, blendEst));
		float cycleGroundDist = 3.00f * Mth.sin(effectiveLegAmp);

		float pulse = Mth.lerp(jog, ROOT_PULSE_WALK.eval(rootCycle), ROOT_PULSE_JOG.eval(rootCycle));
		float tickSpeedBps = rootSpeedBps * pulse;
		float tickDs = tickSpeedBps * tickDt;

		rootLastCmdTickDs = tickDs;
		rootCycle = (rootCycle + tickDs / Math.max(0.50f, cycleGroundDist)) % 1.0f;

		float rad = rootHeadingYaw * Mth.DEG_TO_RAD;
		double vx = -Mth.sin(rad) * tickDs;
		double vz = Mth.cos(rad) * tickDs;
		return new Vec3(vx, 0.0D, vz);
	}

	/**
	 * Updates world animation state once per frame using exact interpolated ground travel (partialTick)
	 * produced by Root Motion so rendered steps lock 1:1 with terrain with zero sliding.
	 */
	public static void ensureWorldUpdated(LocalPlayer player, float partialTick, float limbSwingAmount) {
		if (limbSwingAmount >= 0.0f) {
			lastLimbSwingAmount = limbSwingAmount;
		}
		float effectiveLimbSwing = (limbSwingAmount >= 0.0f) ? limbSwingAmount : lastLimbSwingAmount;

		long frameId = EngineState.frameCounter;
		if (frameId != 0L && frameId == lastSolvedFrame && idleInitialized) {
			return;
		}

		long now = System.nanoTime();
		float dt = (frameId != 0L && EngineState.frameDt > 0.0f)
				? Mth.clamp(EngineState.frameDt, 0.0f, 0.05f)
				: (lastWorldNanos == 0L ? 0.016f : Mth.clamp((now - lastWorldNanos) / 1.0e9f, 0.0f, 0.05f));
		lastWorldNanos = now;
		if (frameId != 0L && frameId == lastSolvedFrame) {
			return;
		}
		lastSolvedFrame = frameId;

		boolean hitstop = ZeldaCombatSystem.isInHitstop();
		if (wasInHitstop && !hitstop) {
			dt = 0.016f;
		}
		wasInHitstop = hitstop;
		boolean isPaused = Minecraft.getInstance().isPaused() || hitstop;
		boolean canAnimate = isBodyAnimationControllable(player);
		boolean onGround = player.onGround();

		double rx = Mth.lerp(partialTick, player.xo, player.getX());
		double rz = Mth.lerp(partialTick, player.zo, player.getZ());
		float ds = 0.0f;
		if (!hasPrevPos) {
			prevSmoothX = rx;
			prevSmoothZ = rz;
			hasPrevPos = true;
		} else {
			double dx = rx - prevSmoothX;
			double dz = rz - prevSmoothZ;
			prevSmoothX = rx;
			prevSmoothZ = rz;
			double dist = Math.sqrt(dx * dx + dz * dz);
			ds = (dist < 1.5) ? (float) dist : 0.0f;
		}

		if (!isPaused && dt > 1.0e-4f) {
			chargeTrembleClock += dt;
			float instBps = ds / dt;
			float targetBps = shouldDriveRootMotion(player)
					? Mth.lerp(0.65f, instBps, rootSpeedBps)
					: instBps;
			measuredSpeedBps = damp(measuredSpeedBps, targetBps, targetBps > measuredSpeedBps ? 16.0f : 14.0f, dt);

			engineWeight = damp(engineWeight, canAnimate ? 1.0f : 0.0f, canAnimate ? 14.0f : 18.0f, dt);

			boolean ledgeActiveNow = canAnimate && ZeldaLedgeSystem.isActive();
			ledgeClock = ledgeActiveNow ? (ledgeClock + dt) : 0.0f;
			ledgeBlend = damp(ledgeBlend, ledgeActiveNow ? 1.0f : 0.0f, ledgeActiveNow ? 26.0f : 18.0f, dt);

			float tickVy = (float) player.getDeltaMovement().y;
			if (canAnimate && !onGround && !ledgeActiveNow) {
				airGraceTime += dt;
				if (!airborneActive && (tickVy > 0.14f || airGraceTime > 0.09f)) {
					airborneActive = true;
					airTime = 0.0f;
					peakFallSpeed = Math.max(0.0f, -tickVy);
					smoothTickVy = tickVy;
					jumpHorizNorm = Mth.clamp(measuredSpeedBps / 4.55f, 0.0f, 1.0f);
					jumpLeadSign = (gaitCycle >= 0.15f && gaitCycle < 0.65f) ? -1.0f : 1.0f;
					landDuration = 0.0f;
					landElapsed = 0.0f;
					landStrength = 0.0f;
				}
				if (airborneActive) {
					airTime += dt;
					smoothTickVy = damp(smoothTickVy, tickVy, 24.0f, dt);
					if (tickVy < 0.0f) {
						peakFallSpeed = Math.max(peakFallSpeed, -tickVy);
					}
				}
			} else {
				if (airborneActive && onGround && canAnimate) {
					if (airTime > 0.14f || peakFallSpeed > 0.22f) {
						landStrength = Mth.clamp((peakFallSpeed - 0.10f) / 0.64f, 0.36f, 1.0f);
						landDuration = Mth.lerp(landStrength, 0.24f, 0.40f);
						landElapsed = 0.0f;
					}
				}
				airborneActive = false;
				airGraceTime = 0.0f;
				airTime = 0.0f;
				peakFallSpeed = 0.0f;
				smoothTickVy = damp(smoothTickVy, 0.0f, 18.0f, dt);
			}

			if (landDuration > 0.0f) {
				landElapsed += dt;
				if (landElapsed >= landDuration) {
					landDuration = 0.0f;
					landElapsed = 0.0f;
					landStrength = 0.0f;
				}
			}

			airBlend = damp(airBlend, airborneActive ? 1.0f : 0.0f, airborneActive ? 24.0f : 26.0f, dt);

			float groundNorm = Mth.clamp(measuredSpeedBps / 4.55f, 0.0f, 1.25f);
			boolean movingOnGround = canAnimate && onGround && (ds > 0.0008f || measuredSpeedBps > 0.16f || (inputMagnitude > 0.05f && effectiveLimbSwing > 0.04f));
			float targetSpeedNorm = movingOnGround ? groundNorm : (airborneActive ? jumpHorizNorm : 0.0f);
			smoothSpeedNorm = damp(smoothSpeedNorm, targetSpeedNorm, movingOnGround ? 15.0f : (airborneActive ? 6.0f : 18.0f), dt);

			float targetMoveBlend = movingOnGround ? Mth.clamp(smoothSpeedNorm / 0.07f, 0.0f, 1.0f) : 0.0f;
			moveBlend = damp(moveBlend, targetMoveBlend, movingOnGround ? 18.0f : (airborneActive ? 14.0f : 16.0f), dt);

			float jog = smoothstep(0.36f, 0.78f, smoothSpeedNorm);
			float strideScale = Mth.clamp(smoothSpeedNorm / 0.28f, 0.48f, 1.0f);
			float wBlend = smoothstep(0.0f, 1.0f, moveBlend);
			float strafeSideBlend = Math.abs(strafeRightRel);
			float fwdLegPeak = Mth.lerp(jog, 0.48f, 0.74f);
			float effectiveBasePeak = Mth.lerp(strafeSideBlend, fwdLegPeak, 0.54f);
			float legPeakRad = Math.max(0.18f, effectiveBasePeak * strideScale * Math.max(0.25f, wBlend));

			gaitFrontRad = legPeakRad * 1.06f;
			gaitBackRad = legPeakRad * 0.92f;
			gaitStanceFrac = Mth.lerp(jog, 0.60f, 0.44f);
			float cycleGroundDist = GAIT_LEG_LEN * ((float) Math.sin(gaitFrontRad) + (float) Math.sin(gaitBackRad)) / gaitStanceFrac;
			if (ds > 1.0e-5f && canAnimate && onGround) {
				gaitCycle = (gaitCycle + ds / Math.max(0.45f, cycleGroundDist)) % 1.0f;
			} else if (moveBlend < 0.004f && onGround) {
				gaitCycle = 0.0f;
			}

			float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
			if (!hasPrevYaw) {
				prevBodyYaw = bodyYaw;
				hasPrevYaw = true;
			}
			float rawDegDelta = Mth.wrapDegrees(bodyYaw - prevBodyYaw);
			prevBodyYaw = bodyYaw;
			float safeDt = Math.max(dt, 0.002f);
			float instTurn = (moveBlend > 0.05f || airborneActive) ? ((rawDegDelta / safeDt) * 0.55f + (inputTurnStepDeg * 20.0f) * 0.45f) : 0.0f;
			smoothTurnRate = damp(smoothTurnRate, Mth.clamp(instTurn, -460.0f, 460.0f), 11.5f, dt);

			float targetBank = Mth.clamp(smoothTurnRate * 0.00088f * Mth.clamp(smoothSpeedNorm, 0.25f, 1.0f), -0.32f, 0.32f);
			float targetHeadLead = Mth.clamp(smoothTurnRate * 0.00145f, -0.58f, 0.58f);
			smoothBank = damp(smoothBank, targetBank, 11.5f, dt);
			smoothHeadLead = damp(smoothHeadLead, targetHeadLead, 13.0f, dt);

			if (moveBlend > 0.06f && onGround) {
				if (now - lastEnemySenseNanos > 70_000_000L) {
					lastEnemySenseNanos = now;
					sensePeripheralEnemy(player);
				}
				enemyLookBlend = damp(enemyLookBlend, enemyVisibleMove ? 1.0f : 0.0f, enemyVisibleMove ? 9.0f : 5.5f, dt);
				smoothEnemyYaw = damp(smoothEnemyYaw, enemyYawTarget, 11.0f, dt);
				smoothEnemyPitch = damp(smoothEnemyPitch, enemyPitchTarget, 11.0f, dt);
			} else {
				enemyVisibleMove = false;
				enemyLookBlend = damp(enemyLookBlend, 0.0f, 10.0f, dt);
			}

			boolean targeting = ZeldaTargetingSystem.isTargeting();
			boolean focusing = ZeldaTargetingSystem.isFocusActive();
			boolean swordDrawn = ZeldaCombatSystem.isSwordDrawn();
			boolean activeCombat = ZeldaCombatSystem.isActiveCombat();
			swordGuardBlend = damp(swordGuardBlend, (targeting || swordDrawn) ? 1.0f : 0.0f, targeting ? 16.0f : 11.0f, dt);
			combatPoseBlend = damp(combatPoseBlend, activeCombat ? 1.0f : 0.0f, activeCombat ? 28.0f : 16.0f, dt);

			if (focusing && inputMagnitude > 0.03f) {
				float relRad = Mth.wrapDegrees(inputTargetYaw - player.getYRot()) * Mth.DEG_TO_RAD;
				strafeForwardRel = damp(strafeForwardRel, Mth.cos(relRad), 18.0f, dt);
				strafeRightRel = damp(strafeRightRel, Mth.sin(relRad), 18.0f, dt);
			} else {
				strafeForwardRel = damp(strafeForwardRel, 1.0f, 16.0f, dt);
				strafeRightRel = damp(strafeRightRel, 0.0f, 16.0f, dt);
			}

			if (moveBlend < 0.96f && !airborneActive && !focusing && !activeCombat) {
				idleClock += dt;
				if (now - lastContextSenseNanos > 65_000_000L || !idleInitialized) {
					lastContextSenseNanos = now;
					senseIdleContext(player);
					if (activeScenario != prevScenario) {
						prevScenario = activeScenario;
						resetFidgetTimers();
					}
				}
				advanceIdleActionTimers(dt);
			}
			if (moveBlend > 0.12f || airborneActive || focusing || activeCombat) {
				idleClock = 0.0f;
				resetFidgetTimers();
			}
		}

		if (engineWeight <= 0.002f) {
			idleInitialized = false;
			hasPrevYaw = false;
			return;
		}

		computeIdleScenarioPose(activeScenario, idleClock, idleTarget);
		if (swordGuardBlend > 0.02f) {
			applySwordGuardOverlay(idleTarget, swordGuardBlend, ZeldaTargetingSystem.isTargeting());
		}
		if (!idleInitialized) {
			idleSpring.snap(idleTarget);
			idleInitialized = true;
		} else if (!isPaused) {
			float headOmega = (activeScenario == IdleScenario.HOSTILE_ALERT || coldSneezing || neutralAction == NeutralAction.LOOK_AROUND) ? 16.0f : 11.0f;
			float bodyOmega = coldSneezing ? 14.0f : 9.0f;
			float armOmega = (coldSneezing || hotAction == HotAction.WIPE_BROW) ? 15.0f : 10.5f;
			idleSpring.step(idleTarget, headOmega, bodyOmega, armOmega, 11.0f, 10.0f, dt);
		}
		idleSpring.unpack(idleSolved);

		computeLocomotionPose(locoPose);
		if (!locoSpringInitialized) {
			locoSpring.snap(locoPose);
			locoSpringInitialized = true;
		} else if (!isPaused) {
			locoSpring.step(locoPose, 26.0f, 24.0f, 30.0f, 38.0f, 34.0f, dt);
		}
		locoSpring.unpack(locoSolved);

		float w = smoothstep(0.0f, 1.0f, moveBlend);
		lerpPose(idleSolved, locoSolved, w, groundedPose);

		if (airBlend > 0.002f) {
			computeJumpPose(jumpPose);
			lerpPose(groundedPose, jumpPose, smoothstep(0.0f, 1.0f, airBlend), finalPose);
		} else {
			copyPose(groundedPose, finalPose);
		}

		if (landDuration > 0.0f && airBlend < 0.65f) {
			applyLandingImpact(finalPose, moveBlend);
		}

		boolean activeCombatNow = ZeldaCombatSystem.isActiveCombat();
		ZeldaCombatSystem.SwordMove curMove = ZeldaCombatSystem.getCurrentMove();
		ZeldaCombatSystem.DodgeMove curDodge = ZeldaCombatSystem.getCurrentDodge();
		if (activeCombatNow && (!wasActiveCombat || curMove != prevCombatMove || curDodge != prevCombatDodge)) {
			if (wasActiveCombat && hasLastCombatPose) {
				copyPose(lastCombatPose, combatEntryStartPose);
			} else if (hasLastCombatPose && combatPoseBlend > 0.02f) {
				lerpPose(finalPose, lastCombatPose, combatPoseBlend, combatEntryStartPose);
			} else {
				copyPose(finalPose, combatEntryStartPose);
			}
			combatEntryStartPose.wholeSpinY = wrapRad(combatEntryStartPose.wholeSpinY);
			combatEntryStartPose.wholeFlipX = wrapRad(combatEntryStartPose.wholeFlipX);
			combatEntryStartPose.wholeRollZ = wrapRad(combatEntryStartPose.wholeRollZ);
			combatEntryBlend = 0.0f;
		}
		wasActiveCombat = activeCombatNow;
		prevCombatMove = curMove;
		prevCombatDodge = curDodge;

		if (activeCombatNow) {
			if (!isPaused) {
				float blendWin = (curDodge != ZeldaCombatSystem.DodgeMove.NONE) ? 0.050f : 0.072f;
				combatEntryBlend = Math.min(1.0f, combatEntryBlend + dt / blendWin);
			}
			computeCombatOrDodgePose(finalPose, combatPose);
			float inW = smoothstep(0.0f, 1.0f, combatEntryBlend);
			if (inW < 0.999f) {
				lerpPose(combatEntryStartPose, combatPose, inW, combatPose);
			}
			copyPose(combatPose, lastCombatPose);
			lastCombatPose.wholeSpinY = wrapRad(lastCombatPose.wholeSpinY);
			lastCombatPose.wholeFlipX = wrapRad(lastCombatPose.wholeFlipX);
			lastCombatPose.wholeRollZ = wrapRad(lastCombatPose.wholeRollZ);
			hasLastCombatPose = true;
			copyPose(combatPose, finalPose);
		} else if (combatPoseBlend > 0.01f && hasLastCombatPose) {
			if (!isPaused) {
				lastCombatPose.wholeSpinY = damp(lastCombatPose.wholeSpinY, 0.0f, 22.0f, dt);
				lastCombatPose.wholeFlipX = damp(lastCombatPose.wholeFlipX, 0.0f, 22.0f, dt);
				lastCombatPose.wholeRollZ = damp(lastCombatPose.wholeRollZ, 0.0f, 22.0f, dt);
			}
			lerpPose(finalPose, lastCombatPose, combatPoseBlend, finalPose);
		} else {
			hasLastCombatPose = false;
		}

		if (canAnimate && ZeldaLedgeSystem.isActive()) {
			computeLedgePose(ledgePose);
			copyPose(ledgePose, lastLedgePose);
			hasLastLedgePose = true;
			lerpPose(finalPose, ledgePose, smoothstep(0.0f, 1.0f, ledgeBlend), finalPose);
		} else if (ledgeBlend > 0.01f && hasLastLedgePose) {
			lerpPose(finalPose, lastLedgePose, ledgeBlend, finalPose);
		} else {
			hasLastLedgePose = false;
		}
	}

	public static float getWholeLeanForwardRad() {
		return idleInitialized ? finalPose.wholeLeanX * engineWeight : 0.0f;
	}

	public static float getWholeBankRightRad() {
		return idleInitialized ? finalPose.wholeBankZ * engineWeight : 0.0f;
	}

	public static float getWholeOffsetYBlocks() {
		return idleInitialized ? finalPose.wholeOffsetY * engineWeight : 0.0f;
	}

	public static float getWholeShiftRightBlocks() {
		return idleInitialized ? finalPose.wholeShiftX * engineWeight : 0.0f;
	}

	public static float getWholeSpinYawRad() {
		return idleInitialized ? finalPose.wholeSpinY * engineWeight : 0.0f;
	}

	public static float getWholeFlipPitchRad() {
		return idleInitialized ? finalPose.wholeFlipX * engineWeight : 0.0f;
	}

	public static float getWholeRollZRad() {
		return idleInitialized ? finalPose.wholeRollZ * engineWeight : 0.0f;
	}

	public static float getWholeScaleY() {
		return idleInitialized ? Mth.lerp(engineWeight, 1.0f, finalPose.wholeScaleY) : 1.0f;
	}

	public static float getSwordWristPitchDeg() {
		if (ZeldaCombatSystem.isJumpSlamming()) {
			float p = ZeldaCombatSystem.getJumpSlamProgress();
			float hold = 1.0f - smoothstep(0.56f, 1.0f, p);
			return Mth.lerp(hold, -9.0f * swordGuardBlend, 54.0f);
		}
		ZeldaCombatSystem.SwordMove move = ZeldaCombatSystem.getCurrentMove();
		float u = ZeldaCombatSystem.getMoveProgress();
		return switch (move) {
			case SLASH_HORIZ -> SLASH_H_WRIST_PITCH.eval(u);
			case SLASH_BACKHAND -> SLASH_B_WRIST_PITCH.eval(u);
			case SLASH_VERTICAL -> SLASH_V_WRIST_PITCH.eval(u);
			case THRUST -> THRUST_WRIST_PITCH.eval(u);
			case JUMP_ATTACK -> JUMP_ATK_WRIST_PITCH.eval(u);
			case CHARGING_SPIN -> Mth.lerp(ZeldaCombatSystem.getChargeProgress(), -9.0f * swordGuardBlend, 18.0f);
			case SPIN_ATTACK -> SPIN_WRIST_PITCH.eval(u);
			case NONE -> -9.0f * swordGuardBlend;
		};
	}

	public static float getSwordWristYawDeg() {
		if (ZeldaCombatSystem.isJumpSlamming()) {
			float p = ZeldaCombatSystem.getJumpSlamProgress();
			float hold = 1.0f - smoothstep(0.56f, 1.0f, p);
			return Mth.lerp(hold, 6.0f * swordGuardBlend, -4.0f);
		}
		ZeldaCombatSystem.SwordMove move = ZeldaCombatSystem.getCurrentMove();
		float u = ZeldaCombatSystem.getMoveProgress();
		return switch (move) {
			case SLASH_HORIZ -> SLASH_H_WRIST_YAW.eval(u);
			case SLASH_BACKHAND -> SLASH_B_WRIST_YAW.eval(u);
			case SLASH_VERTICAL -> SLASH_V_WRIST_YAW.eval(u);
			case THRUST -> THRUST_WRIST_YAW.eval(u);
			case SPIN_ATTACK -> SPIN_WRIST_YAW.eval(u);
			case CHARGING_SPIN -> Mth.lerp(ZeldaCombatSystem.getChargeProgress(), 6.0f * swordGuardBlend, 8.0f);
			case NONE -> 6.0f * swordGuardBlend;
			default -> 0.0f;
		};
	}

	public static float getSwordWristRollDeg() {
		if (ZeldaCombatSystem.isJumpSlamming()) {
			float p = ZeldaCombatSystem.getJumpSlamProgress();
			float hold = 1.0f - smoothstep(0.56f, 1.0f, p);
			return -18.0f * hold;
		}
		ZeldaCombatSystem.SwordMove move = ZeldaCombatSystem.getCurrentMove();
		float u = ZeldaCombatSystem.getMoveProgress();
		return switch (move) {
			case SLASH_HORIZ -> SLASH_H_WRIST_ROLL.eval(u);
			case SLASH_BACKHAND -> SLASH_B_WRIST_ROLL.eval(u);
			case SLASH_VERTICAL -> SLASH_V_WRIST_ROLL.eval(u);
			case THRUST -> THRUST_WRIST_ROLL.eval(u);
			case JUMP_ATTACK -> JUMP_ATK_WRIST_ROLL.eval(u);
			case CHARGING_SPIN -> -48.0f * ZeldaCombatSystem.getChargeProgress();
			case SPIN_ATTACK -> SPIN_WRIST_ROLL.eval(u);
			case NONE -> 0.0f;
		};
	}

	public static void applyWorldToModel(HumanoidModel<?> model) {
		if (engineWeight <= 0.002f) {
			resetResidualModelPivots(model);
			return;
		}
		applyPoseToModel(model, finalPose, engineWeight, false);
	}

	/**
	 * Applies pro moving-hold Hero Idle to the Equipment menu preview (active even while paused),
	 * preserving smooth mouse-following head look.
	 */
	public static void applyMenuIdle(HumanoidModel<?> model) {
		long now = System.nanoTime();
		float dt = lastMenuNanos == 0L ? 0.016f : Mth.clamp((now - lastMenuNanos) / 1.0e9f, 0.0f, 0.05f);
		if (!menuInitialized || dt >= 0.001f) {
			lastMenuNanos = now;
			menuClock += dt;

			if (menuShiftTimer > 0.0f) {
				menuShiftTimer = Math.max(0.0f, menuShiftTimer - dt);
			} else {
				menuShiftCooldown -= dt;
				if (menuShiftCooldown <= 0.0f) {
					menuShiftTimer = rand(2.4f, 3.2f);
					menuShiftCooldown = rand(4.5f, 8.5f);
					menuShiftDir = -menuShiftDir;
				}
			}

			float breathU = (menuClock / 3.8f) % 1.0f;
			float breath = HERO_BREATH.eval(breathU);
			float armFollow = HERO_BREATH.eval((breathU + 0.92f) % 1.0f);
			float shiftW = menuShiftTimer > 0.0f ? smoothWindow(1.0f - menuShiftTimer / 2.8f) : 0.0f;

			menuTarget.headX = 0.012f * breath;
			menuTarget.headY = 0.0f;
			menuTarget.headZ = 0.018f * shiftW * menuShiftDir;

			menuTarget.bodyX = 0.0f;
			menuTarget.bodyY = 0.035f + 0.035f * shiftW * menuShiftDir;
			menuTarget.bodyZ = 0.0f;

			menuTarget.rArmX = 0.02f + 0.016f * armFollow + 0.020f * shiftW;
			menuTarget.rArmY = 0.0f;
			menuTarget.rArmZ = 0.048f + 0.014f * armFollow;
			menuTarget.rForearmX = -0.14f - 0.02f * armFollow;
			menuTarget.rForearmY = 0.0f;

			menuTarget.lArmX = 0.04f - 0.014f * armFollow - 0.020f * shiftW;
			menuTarget.lArmY = 0.0f;
			menuTarget.lArmZ = -0.048f - 0.014f * armFollow;
			menuTarget.lForearmX = -0.12f - 0.02f * armFollow;
			menuTarget.lForearmY = 0.0f;

			menuTarget.rLegX = -0.04f + 0.025f * shiftW * menuShiftDir;
			menuTarget.rLegY = 0.0f;
			menuTarget.rLegZ = 0.022f;
			menuTarget.rShinX = 0.04f + Math.max(0.0f, shiftW * menuShiftDir) * 0.08f;

			menuTarget.lLegX = 0.01f - 0.025f * shiftW * menuShiftDir;
			menuTarget.lLegY = 0.0f;
			menuTarget.lLegZ = -0.022f;
			menuTarget.lShinX = 0.06f + Math.max(0.0f, -shiftW * menuShiftDir) * 0.08f;

			menuTarget.wholeLeanX = 0.0f;
			menuTarget.wholeBankZ = 0.0f;
			menuTarget.wholeOffsetY = 0.0f;
			menuTarget.wholeShiftX = 0.0f;
			menuTarget.wholeScaleY = 1.0f;

			if (!menuInitialized) {
				menuSpring.snap(menuTarget);
				menuInitialized = true;
			} else {
				menuSpring.step(menuTarget, 9.0f, 8.0f, 9.0f, 9.0f, 9.0f, dt);
			}
			menuSpring.unpack(menuSolved);
		}
		applyPoseToModel(model, menuSolved, 1.0f, true);
	}

	// =========================================================================
	// Pro Keyframed Locomotion Evaluator (2-Bone Legs & Arms + Zero Skiing)
	// =========================================================================

	private static void computeLocomotionPose(Pose p) {
		float jog = smoothstep(0.36f, 0.78f, smoothSpeedNorm);
		float strideScale = Mth.clamp(smoothSpeedNorm / 0.28f, 0.48f, 1.0f);
		float u = gaitCycle;
		float twoPi = Mth.TWO_PI;

		float sideMag = Math.abs(strafeRightRel);
		float backpedalFactor = Mth.clamp(strafeForwardRel, -1.0f, 1.0f);
		float strideDir = Mth.lerp(smoothstep(-0.22f, 0.22f, backpedalFactor), -1.0f, 1.0f);
		float frontScale = 1.0f - 0.74f * sideMag;

		// ---- Legs: ground-locked stance, whipped swing, articulated knee bend ----------------------------
		float ts = gaitStanceFrac;
		float front = gaitFrontRad * frontScale;
		float back = gaitBackRad * frontScale;
		float aR = gaitLegAngle(u, ts, front, back) * strideDir;
		float aL = gaitLegAngle(u + 0.5f, ts, front, back) * strideDir;
		float rLegN = -Mth.cos(u * twoPi);

		float kneePhaseShift = (strideDir < 0.0f) ? 0.14f : 0.0f;
		float kneeScale = strideScale * (1.0f - 0.52f * sideMag) * (strideDir < 0.0f ? 0.70f : 1.0f);
		float kR = Math.max(0.0f, Mth.lerp(jog, WALK_KNEE.eval(u + kneePhaseShift), JOG_KNEE.eval(u + kneePhaseShift)) * kneeScale);
		float kL = Math.max(0.0f, Mth.lerp(jog, WALK_KNEE.eval(u + 0.5f + kneePhaseShift), JOG_KNEE.eval(u + 0.5f + kneePhaseShift)) * kneeScale);

		// Slightly fold hip forward when swing knee bends so thigh clearance matches natural running arcs.
		float swingLiftR = Math.max(0.0f, kR - 0.35f) * 0.20f;
		float swingLiftL = Math.max(0.0f, kL - 0.35f) * 0.20f;
		aR -= swingLiftR;
		aL -= swingLiftL;

		// ---- Short flight arc when jogging ---------------------------------------------------------------
		float flight = 0.0f;
		if (ts < 0.5f) {
			float phase = u % 0.5f;
			if (phase > ts) {
				flight = (float) Math.sin((phase - ts) / (0.5f - ts) * Math.PI);
			}
		}
		float flightLift = flight * 0.045f * strideScale * (1.0f - sideMag);
		p.wholeScaleY = 1.0f;

		// ---- Weight transfer, lean -----------------------------------------------------------------------
		float swayN = Mth.cos((u - ts * 0.5f) * twoPi);
		p.wholeLeanX = Mth.lerp(jog, 0.03f, 0.16f) * strideScale;
		p.wholeShiftX = swayN * Mth.lerp(jog, 0.028f, 0.040f) * strideScale;
		p.wholeBankZ = smoothBank + swayN * 0.030f * strideScale;

		// ---- Torso: smooth counter-rotation against the pelvis, slight spine flexion ---------------------
		float twistAmp = Mth.lerp(jog, 0.05f, 0.14f) * strideScale;
		float twistN = Mth.cos(u * twoPi) * strideDir;
		p.bodyX = Mth.lerp(jog, 0.02f, 0.06f) * strideScale;
		p.bodyY = twistN * twistAmp + smoothEnemyYaw * 0.08f * enemyLookBlend;
		p.bodyZ = swayN * Mth.lerp(jog, 0.020f, 0.050f) * strideScale;

		// ---- Head: stabilised on the horizon -------------------------------------------------------------
		float neutralHeadX = -(p.wholeLeanX + p.bodyX) * 0.70f;
		float neutralHeadY = smoothHeadLead - twistN * twistAmp * 0.75f;
		float neutralHeadZ = -smoothBank * 0.38f - (p.bodyZ + swayN * 0.030f * strideScale) * 0.6f;
		p.headX = Mth.lerp(enemyLookBlend, neutralHeadX, smoothEnemyPitch - p.wholeLeanX * 0.45f);
		p.headY = Mth.lerp(enemyLookBlend, neutralHeadY, smoothEnemyYaw);
		p.headZ = Mth.lerp(enemyLookBlend, neutralHeadZ, -0.05f * Mth.clamp(smoothEnemyYaw, -0.6f, 0.6f));

		// ---- Arms & Forearms: articulated swing opposite to the legs -------------------------------------
		float rArmN = Mth.cos((u - 0.04f) * twoPi);
		float lArmN = Mth.cos((u + 0.5f - 0.04f) * twoPi);
		float armCenterX = Mth.lerp(jog, 0.04f, -0.06f);
		float armAmp = Mth.lerp(jog, 0.34f, 0.56f) * strideScale;
		float armOutZ = Mth.lerp(jog, 0.06f, 0.12f);
		float chestInY = Mth.lerp(jog, 0.04f, 0.18f);
		float armFollow = p.bodyY * 0.85f;
		float rFwd = Mth.clamp(-rArmN, 0.0f, 1.0f);
		float lFwd = Mth.clamp(-lArmN, 0.0f, 1.0f);

		float rElbow = Mth.lerp(jog, WALK_ELBOW.eval(u - 0.04f), JOG_ELBOW.eval(u - 0.04f)) * Mth.lerp(strideScale, 0.65f, 1.0f);
		float lElbow = Mth.lerp(jog, WALK_ELBOW.eval(u + 0.5f - 0.04f), JOG_ELBOW.eval(u + 0.5f - 0.04f)) * Mth.lerp(strideScale, 0.65f, 1.0f);

		p.rArmX = armCenterX + rArmN * armAmp;
		p.rArmY = -chestInY * rFwd + armFollow;
		p.rArmZ = armOutZ + 0.030f * rFwd + smoothBank * 0.18f;
		p.rForearmX = Math.min(0.0f, rElbow);
		p.rForearmY = -0.10f * jog * rFwd;

		p.lArmX = armCenterX + lArmN * armAmp;
		p.lArmY = chestInY * lFwd + armFollow;
		p.lArmZ = -armOutZ - 0.030f * lFwd + smoothBank * 0.18f;
		p.lForearmX = Math.min(0.0f, lElbow);
		p.lForearmY = 0.10f * jog * lFwd;

		if (enemyLookBlend > 0.05f) {
			float alertArm = enemyLookBlend * 0.25f;
			p.lArmX = Mth.lerp(alertArm, p.lArmX, -0.18f + lArmN * 0.20f);
			p.lArmY = Mth.lerp(alertArm, p.lArmY, 0.24f);
			p.lForearmX = Mth.lerp(alertArm, p.lForearmX, -0.58f);
		}

		// ---- Side-step shuffle (Z-target strafing) -------------------------------------------------------
		float shufflePhase = Mth.sin(u * twoPi);
		float sideStepR = Math.max(0.0f, shufflePhase) * 0.54f * sideMag * strideScale;
		float sideStepL = Math.max(0.0f, -shufflePhase) * 0.54f * sideMag * strideScale;
		float sideStrideCross = sideMag * 0.10f * strideScale * Mth.cos(u * twoPi);

		p.rLegX = aR + sideStrideCross;
		p.rLegY = 0.0f;
		p.rLegZ = 0.028f - smoothBank * 0.10f + 0.06f * sideMag + sideStepR;
		p.rShinX = kR + sideMag * 0.22f * strideScale;

		p.lLegX = aL - sideStrideCross;
		p.lLegY = 0.0f;
		p.lLegZ = -0.028f - smoothBank * 0.10f - 0.06f * sideMag - sideStepL;
		p.lShinX = kL + sideMag * 0.22f * strideScale;

		p.wholeSpinY = 0.0f;
		p.wholeFlipX = 0.0f;
		p.wholeRollZ = 0.0f;

		if (swordGuardBlend > 0.02f) {
			float g = swordGuardBlend;
			p.rArmX = Mth.lerp(g, p.rArmX, -0.68f + rArmN * 0.14f);
			p.rArmY = Mth.lerp(g, p.rArmY, 0.08f);
			p.rArmZ = Mth.lerp(g, p.rArmZ, -0.08f);
			p.rForearmX = Mth.lerp(g, p.rForearmX, -0.46f + rArmN * 0.04f);
			p.rForearmY = Mth.lerp(g, p.rForearmY, 0.0f);

			p.lArmX = Mth.lerp(g, p.lArmX, -0.32f + lArmN * 0.14f);
			p.lArmY = Mth.lerp(g, p.lArmY, 0.42f);
			p.lArmZ = Mth.lerp(g, p.lArmZ, -0.14f);
			p.lForearmX = Mth.lerp(g, p.lForearmX, -0.64f);
			p.lForearmY = Mth.lerp(g, p.lForearmY, 0.12f);

			p.bodyY = Mth.lerp(g, p.bodyY, -0.22f + rLegN * 0.04f);
		}
		if (ZeldaTargetingSystem.isFocusActive()) {
			p.headY = 0.0f;
			p.bodyY = Mth.lerp(swordGuardBlend, 0.0f, -0.22f);
			p.wholeBankZ = strafeRightRel * 0.16f;
			p.wholeLeanX = (backpedalFactor < -0.15f)
					? (-0.04f * Math.abs(backpedalFactor))
					: (p.wholeLeanX * Mth.clamp(backpedalFactor, 0.15f, 1.0f));
		}

		// Ground contact with articulated 2-segment hip+knee kinematics.
		float lowest = gaitLowestFootY(p.rLegX, p.lLegX, p.rShinX, p.lShinX, p.wholeLeanX, p.bodyX, p.bodyY);
		p.wholeOffsetY = -lowest * 0.98f + flightLift - 0.032f * sideMag * strideScale;
	}

	// =========================================================================
	// Ocarina of Time Combat Guard, Sword Moves, Spin Attack & Dodges
	// =========================================================================

	private static void applySwordGuardOverlay(Pose p, float g, boolean zTargeting) {
		float lean = zTargeting ? 0.145f : 0.065f;
		float dip = zTargeting ? -0.065f : -0.020f;
		p.wholeLeanX = Mth.lerp(g, p.wholeLeanX, lean);
		p.wholeOffsetY = Mth.lerp(g, p.wholeOffsetY, dip);
		p.bodyY = Mth.lerp(g, p.bodyY, -0.28f);
		p.headY = Mth.lerp(g, p.headY, 0.18f);

		// Right sword arm: upper arm (-0.76) + forearm (-0.48) = -1.24 total wrist pitch!
		p.rArmX = Mth.lerp(g, p.rArmX, -0.76f);
		p.rArmY = Mth.lerp(g, p.rArmY, 0.08f);
		p.rArmZ = Mth.lerp(g, p.rArmZ, -0.09f);
		p.rForearmX = Mth.lerp(g, p.rForearmX, -0.48f);
		p.rForearmY = Mth.lerp(g, p.rForearmY, 0.0f);

		// Left shield arm held cleanly across chest
		p.lArmX = Mth.lerp(g, p.lArmX, zTargeting ? -0.34f : -0.22f);
		p.lArmY = Mth.lerp(g, p.lArmY, zTargeting ? 0.44f : 0.34f);
		p.lArmZ = Mth.lerp(g, p.lArmZ, -0.12f);
		p.lForearmX = Mth.lerp(g, p.lForearmX, zTargeting ? -0.66f : -0.54f);
		p.lForearmY = Mth.lerp(g, p.lForearmY, zTargeting ? 0.14f : 0.10f);

		if (zTargeting) {
			p.rLegX = Mth.lerp(g, p.rLegX, 0.24f);
			p.lLegX = Mth.lerp(g, p.lLegX, -0.42f);
			p.rLegZ = Mth.lerp(g, p.rLegZ, 0.08f);
			p.lLegZ = Mth.lerp(g, p.lLegZ, -0.08f);
			p.rShinX = Mth.lerp(g, p.rShinX, 0.14f);
			p.lShinX = Mth.lerp(g, p.lShinX, 0.24f);
		}
	}

	private static void computeCombatOrDodgePose(Pose base, Pose p) {
		copyPose(base, p);
		p.wholeSpinY = 0.0f;
		p.wholeFlipX = 0.0f;
		p.wholeRollZ = 0.0f;
		p.rForearmY = 0.0f;
		p.lForearmY = 0.0f;

		if (ZeldaCombatSystem.isJumpSlamming()) {
			float pSlam = ZeldaCombatSystem.getJumpSlamProgress();
			float plant = smoothstep(0.0f, 0.14f, pSlam);
			float lift = smoothstep(0.56f, 1.0f, pSlam);
			float s = Mth.lerp(plant, 0.88f, 1.0f) * (1.0f - lift);
			p.wholeLeanX = Mth.lerp(s, 0.13f, 0.42f);
			p.wholeOffsetY = Mth.lerp(s, -0.05f, -0.34f);
			p.wholeScaleY = Mth.lerp(s, 1.0f, 0.84f);
			p.bodyY = Mth.lerp(s, -0.28f, -0.24f);
			p.headX = Mth.lerp(s, -0.06f, -0.32f);
			p.headY = Mth.lerp(s, 0.20f, 0.22f);
			p.rArmX = Mth.lerp(s, -0.76f, -0.34f);
			p.rForearmX = Mth.lerp(s, -0.48f, -0.28f);
			p.rArmY = Mth.lerp(s, 0.08f, -0.78f);
			p.rArmZ = Mth.lerp(s, -0.09f, 0.28f);
			p.lArmX = Mth.lerp(s, -0.34f, 0.34f);
			p.lForearmX = Mth.lerp(s, -0.66f, -0.36f);
			p.lArmY = Mth.lerp(s, 0.46f, 0.22f);
			p.lArmZ = Mth.lerp(s, -0.12f, -0.44f);
			p.rLegX = Mth.lerp(s, 0.24f, 0.44f);
			p.lLegX = Mth.lerp(s, -0.42f, -0.98f);
			p.rShinX = Mth.lerp(s, 0.14f, 0.72f);
			p.lShinX = Mth.lerp(s, 0.24f, 1.04f);
			p.rLegZ = 0.14f;
			p.lLegZ = -0.14f;
			return;
		}

		ZeldaCombatSystem.DodgeMove dodge = ZeldaCombatSystem.getCurrentDodge();
		if (dodge != ZeldaCombatSystem.DodgeMove.NONE) {
			float u = ZeldaCombatSystem.getDodgeProgress();
			if (dodge == ZeldaCombatSystem.DodgeMove.ROLL) {
				float tuck = smoothWindow(u);
				float rollout = smoothstep(0.72f, 1.0f, u);
				p.wholeFlipX = ROLL_PITCH.eval(u);
				p.wholeScaleY = Mth.lerp(tuck, 1.0f, 0.88f);
				p.wholeOffsetY = -0.36f * tuck - 0.08f * rollout * (1.0f - rollout);
				p.wholeLeanX = Mth.lerp(rollout, 0.04f, 0.20f);
				p.wholeRollZ = -0.18f * tuck;
				p.bodyY = -0.32f * tuck;
				p.rLegX = Mth.lerp(tuck, p.rLegX, -1.52f);
				p.lLegX = Mth.lerp(tuck, p.lLegX, -1.42f);
				p.rShinX = Mth.lerp(tuck, p.rShinX, 1.48f);
				p.lShinX = Mth.lerp(tuck, p.lShinX, 1.48f);
				p.rArmX = Mth.lerp(tuck, p.rArmX, -0.68f);
				p.lArmX = Mth.lerp(tuck, p.lArmX, -0.62f);
				p.rForearmX = Mth.lerp(tuck, p.rForearmX, -0.96f);
				p.lForearmX = Mth.lerp(tuck, p.lForearmX, -0.96f);
				p.rArmZ = 0.20f * tuck + 0.08f * rollout;
				p.lArmZ = -0.20f * tuck - 0.08f * rollout;
				p.headX = 0.72f * tuck;
			} else if (dodge == ZeldaCombatSystem.DodgeMove.SIDE_ROLL_LEFT
					|| dodge == ZeldaCombatSystem.DodgeMove.SIDE_ROLL_RIGHT) {
				float dir = (dodge == ZeldaCombatSystem.DodgeMove.SIDE_ROLL_RIGHT) ? 1.0f : -1.0f;
				float tuck = smoothWindow(u);
				float rollout = smoothstep(0.70f, 1.0f, u);

				p.wholeRollZ = dir * SIDE_ROLL_Z.eval(u);
				p.wholeBankZ = dir * 0.08f * tuck;
				p.wholeOffsetY = SIDE_ROLL_OFFSET_Y.eval(u);
				p.wholeScaleY = SIDE_ROLL_SCALE_Y.eval(u);
				p.wholeLeanX = SIDE_ROLL_LEAN_X.eval(u);

				p.bodyX = 0.28f * tuck + 0.06f * rollout;
				p.bodyY = dir * SIDE_ROLL_BODY_Y.eval(u);
				p.headX = 0.16f * tuck - 0.10f * rollout;
				p.headY = -p.bodyY * 0.65f;
				p.headZ = -dir * 0.14f * tuck;

				float leadLegX = SIDE_ROLL_LEAD_LEG_X.eval(u);
				float trailLegX = SIDE_ROLL_TRAIL_LEG_X.eval(u);
				float leadKneeX = SIDE_ROLL_LEAD_KNEE_X.eval(u);
				float trailKneeX = SIDE_ROLL_TRAIL_KNEE_X.eval(u);
				float leadLegZ = SIDE_ROLL_LEAD_LEG_Z.eval(u);
				float trailLegZ = SIDE_ROLL_TRAIL_LEG_Z.eval(u);

				if (dir > 0.0f) {
					p.rLegX = leadLegX;
					p.lLegX = trailLegX;
					p.rShinX = leadKneeX;
					p.lShinX = trailKneeX;
					p.rLegZ = leadLegZ;
					p.lLegZ = -trailLegZ;
				} else {
					p.rLegX = trailLegX;
					p.lLegX = leadLegX;
					p.rShinX = trailKneeX;
					p.lShinX = leadKneeX;
					p.rLegZ = trailLegZ;
					p.lLegZ = -leadLegZ;
				}

				if (dir > 0.0f) {
					// Rolling right: tuck sword arm tight across chest, shield arm braced forward.
					p.rArmX = Mth.lerp(tuck, -0.58f, -0.96f) + 0.18f * rollout;
					p.rForearmX = Mth.lerp(tuck, -0.52f, -1.18f);
					p.rArmY = Mth.lerp(tuck, 0.08f, 0.44f);
					p.rArmZ = Mth.lerp(tuck, 0.16f, 0.34f);

					p.lArmX = Mth.lerp(tuck, -0.44f, -0.86f);
					p.lForearmX = Mth.lerp(tuck, -0.68f, -1.12f);
					p.lArmY = Mth.lerp(tuck, 0.42f, 0.62f);
					p.lArmZ = Mth.lerp(tuck, -0.14f, -0.26f);
				} else {
					// Rolling left: tuck shield shoulder tight into the roll, sword arm folded across torso.
					p.rArmX = Mth.lerp(tuck, -0.58f, -0.82f);
					p.rForearmX = Mth.lerp(tuck, -0.52f, -1.08f);
					p.rArmY = Mth.lerp(tuck, 0.08f, -0.26f);
					p.rArmZ = Mth.lerp(tuck, 0.16f, 0.28f);

					p.lArmX = Mth.lerp(tuck, -0.44f, -1.06f);
					p.lForearmX = Mth.lerp(tuck, -0.68f, -1.20f);
					p.lArmY = Mth.lerp(tuck, 0.42f, 0.54f);
					p.lArmZ = Mth.lerp(tuck, -0.14f, -0.34f);
				}
			} else if (dodge == ZeldaCombatSystem.DodgeMove.BACKFLIP) {
				p.wholeFlipX = BACKFLIP_PITCH.eval(u);
				float airW = smoothWindow(u);
				float landW = smoothstep(0.72f, 1.0f, u);
				p.wholeOffsetY = BACKFLIP_OFFSET_Y.eval(u);
				p.wholeScaleY = BACKFLIP_SCALE_Y.eval(u);
				p.wholeLeanX = Mth.lerp(airW, 0.24f, 0.20f);
				p.bodyY = Mth.lerp(landW, -0.08f * airW, -0.28f);
				p.rLegX = BACKFLIP_RLEG_X.eval(u);
				p.lLegX = BACKFLIP_LLEG_X.eval(u);
				float bfKnee = BACKFLIP_KNEE_X.eval(u);
				p.rShinX = bfKnee;
				p.lShinX = bfKnee;
				p.rLegZ = 0.14f * airW + 0.14f * landW;
				p.lLegZ = -0.14f * airW - 0.14f * landW;
				p.rArmX = Mth.lerp(landW, -0.82f * airW - 0.24f, -0.64f);
				p.rForearmX = Mth.lerp(landW, -0.62f * airW - 0.18f, -0.44f);
				p.rArmY = 0.10f;
				p.rArmZ = 0.44f * airW + 0.06f;
				p.lArmX = Mth.lerp(landW, -0.78f * airW - 0.22f, -0.38f);
				p.lForearmX = Mth.lerp(landW, -0.64f * airW - 0.20f, -0.62f);
				p.lArmY = Mth.lerp(landW, 0.25f, 0.46f);
				p.lArmZ = -0.34f * airW - 0.10f;
				p.headX = -0.26f * airW + 0.18f * landW;
			} else {
				float dir = (dodge == ZeldaCombatSystem.DodgeMove.SIDE_HOP_RIGHT) ? 1.0f : -1.0f;
				float airW = smoothWindow(u);
				float landW = smoothstep(0.74f, 1.0f, u);
				float leadLegX = SIDEHOP_LEAD_LEG_X.eval(u);
				float trailLegX = SIDEHOP_TRAIL_LEG_X.eval(u);
				float leadKneeX = SIDEHOP_LEAD_KNEE_X.eval(u);
				float trailKneeX = SIDEHOP_TRAIL_KNEE_X.eval(u);
				float leadLegZ = SIDEHOP_LEAD_LEG_Z.eval(u);
				float trailLegZ = SIDEHOP_TRAIL_LEG_Z.eval(u);

				p.wholeRollZ = 0.0f;
				p.wholeBankZ = dir * SIDEHOP_BANK_Z.eval(u);
				p.wholeOffsetY = SIDEHOP_OFFSET_Y.eval(u);
				p.wholeScaleY = SIDEHOP_SCALE_Y.eval(u);
				p.wholeLeanX = 0.22f * airW + 0.26f * landW;
				p.bodyY = dir * SIDEHOP_TORSO_YAW_MULT.eval(u) - 0.14f * (1.0f - airW);
				p.headX = -0.14f * airW - 0.10f * landW;
				p.headY = -p.bodyY * 0.85f;
				p.headZ = -p.wholeBankZ * 0.72f;

				if (dir > 0.0f) {
					p.rLegX = leadLegX;
					p.lLegX = trailLegX;
					p.rShinX = leadKneeX;
					p.lShinX = trailKneeX;
					p.rLegZ = leadLegZ;
					p.lLegZ = -trailLegZ;
				} else {
					p.rLegX = trailLegX;
					p.lLegX = leadLegX;
					p.rShinX = trailKneeX;
					p.lShinX = leadKneeX;
					p.rLegZ = trailLegZ;
					p.lLegZ = -leadLegZ;
				}

				p.rArmX = Mth.lerp(airW, -0.68f, -0.28f);
				p.rForearmX = Mth.lerp(airW, -0.44f, -0.32f);
				p.rArmY = (dir > 0.0f) ? 0.38f * airW : -0.24f * airW;
				p.rArmZ = 0.04f + (dir > 0.0f ? 0.58f : 0.36f) * airW;
				p.lArmX = Mth.lerp(airW, -0.36f, -0.54f);
				p.lForearmX = Mth.lerp(airW, -0.62f, -0.74f);
				p.lArmY = 0.52f;
				p.lArmZ = -0.10f - (dir < 0.0f ? 0.26f : 0.06f) * airW;
			}
			return;
		}

		ZeldaCombatSystem.SwordMove move = ZeldaCombatSystem.getCurrentMove();
		float u = ZeldaCombatSystem.getMoveProgress();
		switch (move) {
			case SLASH_HORIZ -> {
				float win = smoothWindow(u);
				float follow = smoothstep(0.24f, 0.58f, u) * (1.0f - smoothstep(0.80f, 1.0f, u));
				p.bodyY = SLASH_H_BODY_Y.eval(u);
				p.headY = -p.bodyY * 0.72f;
				p.wholeScaleY = SLASH_H_SCALE_Y.eval(u);
				p.rArmX = SLASH_H_RARM_X.eval(u);
				p.rForearmX = SLASH_H_RFOREARM_X.eval(u);
				p.rArmY = SLASH_H_RARM_Y.eval(u);
				p.rArmZ = SLASH_H_RARM_Z.eval(u);
				p.lArmX = Mth.lerp(follow, -0.32f, 0.26f);
				p.lForearmX = Mth.lerp(follow, -0.62f, -0.26f);
				p.lArmY = Mth.lerp(follow, 0.45f, 0.14f);
				p.lArmZ = Mth.lerp(follow, -0.24f, -0.44f);
				p.wholeLeanX = Mth.lerp(smoothstep(0.18f, 0.55f, u), 0.02f, 0.22f) * win + 0.08f * (1.0f - win);
				p.wholeOffsetY = -0.095f * win;
				p.lLegX = SLASH_H_LLEG_X.eval(u);
				p.rLegX = SLASH_H_RLEG_X.eval(u);
				p.lShinX = SLASH_H_LSHIN_X.eval(u);
				p.rShinX = SLASH_H_RSHIN_X.eval(u);
				p.rLegZ = 0.10f * win;
				p.lLegZ = -0.10f * win;
			}
			case SLASH_BACKHAND -> {
				float win = smoothWindow(u);
				float follow = smoothstep(0.20f, 0.55f, u) * (1.0f - smoothstep(0.80f, 1.0f, u));
				p.bodyY = SLASH_B_BODY_Y.eval(u);
				p.headY = -p.bodyY * 0.72f;
				p.wholeScaleY = SLASH_B_SCALE_Y.eval(u);
				p.rArmX = SLASH_B_RARM_X.eval(u);
				p.rForearmX = SLASH_B_RFOREARM_X.eval(u);
				p.rArmY = SLASH_B_RARM_Y.eval(u);
				p.rArmZ = SLASH_B_RARM_Z.eval(u);
				p.lArmX = Mth.lerp(follow, 0.18f, -0.36f);
				p.lForearmX = Mth.lerp(follow, -0.28f, -0.64f);
				p.lArmY = Mth.lerp(follow, 0.18f, 0.36f);
				p.lArmZ = Mth.lerp(follow, -0.38f, -0.28f);
				p.wholeLeanX = Mth.lerp(smoothstep(0.15f, 0.75f, u), 0.18f, -0.02f) * win + 0.06f * (1.0f - win);
				p.wholeOffsetY = (u > 0.65f ? 0.03f : -0.08f) * win;
				p.rLegX = SLASH_B_RLEG_X.eval(u);
				p.lLegX = SLASH_B_LLEG_X.eval(u);
				p.lShinX = SLASH_B_LSHIN_X.eval(u);
				p.rShinX = SLASH_B_RSHIN_X.eval(u);
				p.rLegZ = 0.10f * win;
				p.lLegZ = -0.10f * win;
			}
			case SLASH_VERTICAL -> {
				p.rArmX = SLASH_V_RARM_X.eval(u);
				p.rForearmX = SLASH_V_RFOREARM_X.eval(u);
				p.rArmY = SLASH_V_RARM_Y.eval(u);
				p.rArmZ = SLASH_V_RARM_Z.eval(u);
				float raiseW = smoothstep(0.08f, 0.32f, u) * (1.0f - smoothstep(0.48f, 0.85f, u));
				p.lArmX = Mth.lerp(raiseW, -0.34f, -0.84f);
				p.lForearmX = Mth.lerp(raiseW, -0.60f, -0.64f);
				p.lArmY = Mth.lerp(raiseW, 0.42f, 0.16f);
				p.lArmZ = Mth.lerp(raiseW, -0.18f, -0.44f);
				p.wholeLeanX = SLASH_V_LEAN.eval(u);
				p.wholeOffsetY = SLASH_V_OFFSET_Y.eval(u);
				p.wholeScaleY = SLASH_V_SCALE_Y.eval(u);
				p.bodyY = Mth.lerp(smoothstep(0.16f, 0.56f, u), 0.24f, -0.28f);
				p.headY = -p.bodyY * 0.65f;
				p.headX = -p.wholeLeanX * 0.55f;
				p.lLegX = SLASH_V_LLEG_X.eval(u);
				p.rLegX = SLASH_V_RLEG_X.eval(u);
				p.lShinX = SLASH_V_LSHIN_X.eval(u);
				p.rShinX = SLASH_V_RSHIN_X.eval(u);
				float brace = smoothWindow(u);
				p.rLegZ = 0.12f * brace;
				p.lLegZ = -0.12f * brace;
			}
			case THRUST -> {
				p.rArmX = THRUST_RARM_X.eval(u);
				p.rForearmX = THRUST_RFOREARM_X.eval(u);
				p.rArmY = THRUST_RARM_Y.eval(u);
				p.rArmZ = THRUST_RARM_Z.eval(u);
				p.bodyY = THRUST_BODY_Y.eval(u);
				p.headY = -p.bodyY * 0.78f;
				p.wholeScaleY = THRUST_SCALE_Y.eval(u);
				float extend = smoothstep(0.18f, 0.46f, u) * (1.0f - smoothstep(0.74f, 1.0f, u));
				p.lArmX = Mth.lerp(extend, -0.34f, 0.46f);
				p.lForearmX = Mth.lerp(extend, -0.62f, -0.26f);
				p.lArmY = Mth.lerp(extend, 0.44f, 0.18f);
				p.lArmZ = Mth.lerp(extend, -0.18f, -0.44f);
				float lunge = smoothstep(0.18f, 0.48f, u) * (1.0f - smoothstep(0.70f, 1.0f, u));
				p.wholeLeanX = Mth.lerp(lunge, 0.08f, 0.32f);
				p.wholeOffsetY = Mth.lerp(lunge, -0.05f, -0.19f);
				p.headX = -p.wholeLeanX * 0.65f;
				p.lLegX = THRUST_LLEG_X.eval(u);
				p.rLegX = THRUST_RLEG_X.eval(u);
				p.lShinX = THRUST_LSHIN_X.eval(u);
				p.rShinX = THRUST_RSHIN_X.eval(u);
				p.rLegZ = 0.11f;
				p.lLegZ = -0.11f;
			}
			case JUMP_ATTACK -> {
				float dive = smoothstep(0.20f, 0.60f, u);
				p.rArmX = JUMP_ATK_RARM_X.eval(u);
				p.rForearmX = JUMP_ATK_RFOREARM_X.eval(u);
				p.rArmY = JUMP_ATK_RARM_Y.eval(u);
				p.rArmZ = JUMP_ATK_RARM_Z.eval(u);
				p.lArmX = JUMP_ATK_LARM_X.eval(u);
				p.lForearmX = Mth.lerp(dive, -0.52f, -0.26f);
				p.lArmY = Mth.lerp(dive, 0.16f, 0.34f);
				p.lArmZ = Mth.lerp(dive, -0.42f, -0.24f);
				p.wholeLeanX = JUMP_ATK_LEAN.eval(u);
				p.wholeScaleY = JUMP_ATK_SCALE_Y.eval(u);
				p.bodyY = Mth.lerp(dive, 0.14f, -0.24f);
				p.headY = -p.bodyY * 0.62f;
				p.headX = -p.wholeLeanX * 0.55f;
				p.rLegX = JUMP_ATK_RLEG_X.eval(u);
				p.lLegX = JUMP_ATK_LLEG_X.eval(u);
				p.lShinX = Mth.lerp(dive, 0.68f, 0.94f);
				p.rShinX = Mth.lerp(dive, 0.42f, 0.32f);
				p.rLegZ = 0.12f;
				p.lLegZ = -0.12f;
			}
			case CHARGING_SPIN -> {
				float c = ZeldaCombatSystem.getChargeProgress();
				float tremble = ZeldaCombatSystem.isSpinFullyCharged()
						? Mth.sin(chargeTrembleClock * 45.0f) * 0.045f : 0.0f;
				p.wholeOffsetY = -0.14f * c;
				p.wholeScaleY = 1.0f - 0.12f * c;
				p.wholeLeanX = 0.24f * c;
				p.bodyY = 0.68f * c;
				p.headY = -0.58f * c;
				p.rArmX = Mth.lerp(c, -0.76f, -0.36f) + tremble;
				p.rForearmX = Mth.lerp(c, -0.48f, -0.62f);
				p.rArmY = Mth.lerp(c, 0.08f, 1.08f);
				p.rArmZ = Mth.lerp(c, -0.09f, -0.12f) + tremble;
				p.lArmX = -0.34f;
				p.lForearmX = -0.66f;
				p.lArmY = 0.48f;
				p.lArmZ = -0.18f;
				float shuffle = smoothstep(0.05f, 0.35f, moveBlend);
				p.rLegX = Mth.lerp(shuffle, 0.28f * c, base.rLegX * 0.85f + 0.10f);
				p.lLegX = Mth.lerp(shuffle, -0.58f * c, base.lLegX * 0.85f - 0.18f);
				p.rShinX = Mth.lerp(shuffle, 0.34f * c, base.rShinX + 0.14f * c);
				p.lShinX = Mth.lerp(shuffle, 0.56f * c, base.lShinX + 0.22f * c);
				p.rLegZ = 0.11f;
				p.lLegZ = -0.11f;
			}
			case SPIN_ATTACK -> {
				p.wholeSpinY = SPIN_ANGLE.eval(u);
				float active = smoothWindow(u);
				p.wholeLeanX = 0.26f * active;
				p.wholeOffsetY = -0.12f * active;
				p.wholeScaleY = SPIN_SCALE_Y.eval(u);
				p.bodyY = SPIN_BODY_Y.eval(u);
				p.headY = -p.bodyY * 0.72f;
				p.rArmX = SPIN_RARM_X.eval(u);
				p.rForearmX = SPIN_RFOREARM_X.eval(u);
				p.rArmY = SPIN_RARM_Y.eval(u);
				p.rArmZ = SPIN_RARM_Z.eval(u);
				p.lArmX = -0.42f;
				p.lForearmX = -0.68f;
				p.lArmY = 0.44f;
				p.lArmZ = -0.20f;
				p.lLegX = SPIN_LLEG_X.eval(u);
				p.rLegX = SPIN_RLEG_X.eval(u);
				p.lShinX = 0.42f * active;
				p.rShinX = 0.24f * active;
				p.rLegZ = 0.14f;
				p.lLegZ = -0.14f;
			}
			case NONE -> {}
		}

		if (ZeldaCombatSystem.isInHitstop()) {
			float jitter = ZeldaCombatSystem.getHitstopJitter();
			p.rArmX += jitter * 0.048f;
			p.rForearmX -= jitter * 0.024f;
			p.rArmZ -= jitter * 0.032f;
			p.bodyY += jitter * 0.025f;
			p.wholeLeanX += Math.abs(jitter) * 0.025f;
		}
	}

	// =========================================================================
	// Pro Keyframed Jump, Apex, Descent & Landing Squash Evaluator
	// =========================================================================

	private static void computeJumpPose(Pose p) {
		float uJump;
		if (smoothTickVy >= 0.0f) {
			uJump = Mth.clamp((0.42f - smoothTickVy) / 0.42f, 0.0f, 1.0f) * 0.48f;
		} else {
			uJump = 0.48f + Mth.clamp(-smoothTickVy / 0.95f, 0.0f, 1.0f) * 0.52f;
		}

		float horiz = Mth.lerp(jumpHorizNorm, 0.52f, 1.0f);
		float fallUrgency = smoothstep(0.62f, 0.98f, uJump);
		float flutter = fallUrgency * Mth.sin(airTime * 16.0f) * 0.07f;

		float leadLeg = JUMP_LEAD_LEG.eval(uJump) * horiz;
		float trailLeg = JUMP_TRAIL_LEG.eval(uJump) * horiz;
		float leadKnee = JUMP_LEAD_KNEE.eval(uJump) * Mth.lerp(jumpHorizNorm, 0.65f, 1.0f);
		float trailKnee = JUMP_TRAIL_KNEE.eval(uJump) * Mth.lerp(jumpHorizNorm, 0.65f, 1.0f);
		float leadArm = JUMP_LEAD_ARM.eval(uJump);
		float trailArm = JUMP_TRAIL_ARM.eval(uJump);
		float armElbow = JUMP_ARM_ELBOW.eval(uJump);
		float flare = JUMP_ARM_FLARE.eval(uJump);

		float standSymArm = Mth.lerp(fallUrgency, -0.28f, -0.88f);
		float runWeight = smoothstep(0.10f, 0.65f, jumpHorizNorm);

		float rightArmDrive = (jumpLeadSign > 0.0f) ? trailArm : leadArm;
		float leftArmDrive = (jumpLeadSign > 0.0f) ? leadArm : trailArm;

		p.wholeLeanX = JUMP_LEAN_X.eval(uJump) * Mth.lerp(jumpHorizNorm, 0.45f, 1.0f);
		p.wholeBankZ = smoothBank * 0.65f;
		p.wholeOffsetY = (uJump < 0.35f) ? 0.022f * (1.0f - uJump / 0.35f) : 0.0f;
		p.wholeShiftX = 0.0f;
		p.wholeScaleY = (uJump < 0.45f) ? Mth.lerp(uJump / 0.45f, 1.08f, 1.03f) : Mth.lerp((uJump - 0.45f) / 0.55f, 1.03f, 1.06f);

		p.bodyX = 0.0f;
		p.bodyY = 0.095f * jumpLeadSign * runWeight * (1.0f - fallUrgency * 0.6f);
		p.bodyZ = 0.0f;

		float headPitch = Mth.lerp(smoothstep(0.20f, 0.85f, uJump), -0.10f, 0.32f);
		p.headX = headPitch - p.wholeLeanX * 0.35f;
		p.headY = smoothHeadLead * 0.7f;
		p.headZ = -smoothBank * 0.25f;

		p.rArmX = Mth.lerp(runWeight, standSymArm, rightArmDrive) + flutter;
		p.rArmY = -0.04f * (1.0f - fallUrgency);
		p.rArmZ = flare + flutter * 0.6f + smoothBank * 0.14f;
		p.rForearmX = armElbow;
		p.rForearmY = 0.0f;

		p.lArmX = Mth.lerp(runWeight, standSymArm, leftArmDrive) - flutter;
		p.lArmY = 0.04f * (1.0f - fallUrgency);
		p.lArmZ = -flare - flutter * 0.6f + smoothBank * 0.14f;
		p.lForearmX = armElbow;
		p.lForearmY = 0.0f;

		if (jumpLeadSign > 0.0f) {
			p.rLegX = leadLeg;
			p.lLegX = trailLeg;
			p.rShinX = leadKnee;
			p.lShinX = trailKnee;
		} else {
			p.rLegX = trailLeg;
			p.lLegX = leadLeg;
			p.rShinX = trailKnee;
			p.lShinX = leadKnee;
		}
		p.rLegY = 0.0f;
		p.rLegZ = 0.035f + 0.03f * fallUrgency;
		p.lLegY = 0.0f;
		p.lLegZ = -0.035f - 0.03f * fallUrgency;
	}

	private static void applyLandingImpact(Pose p, float currentMoveBlend) {
		float u = Mth.clamp(landElapsed / Math.max(0.01f, landDuration), 0.0f, 1.0f);
		float squash = LAND_SQUASH.eval(u) * landStrength;
		float moveAtten = Mth.lerp(currentMoveBlend, 1.0f, 0.58f);

		p.wholeOffsetY -= squash * 0.125f * moveAtten;
		p.wholeScaleY *= (1.0f - squash * 0.13f * moveAtten);
		p.wholeLeanX += squash * 0.145f * moveAtten;
		p.headX += squash * 0.16f * moveAtten;

		float armCushion = (1.0f - currentMoveBlend * 0.65f);
		p.rArmX += squash * 0.18f * armCushion;
		p.lArmX += squash * 0.18f * armCushion;
		p.rForearmX -= Math.max(0.0f, squash) * 0.38f * armCushion;
		p.lForearmX -= Math.max(0.0f, squash) * 0.38f * armCushion;
		p.rArmZ += squash * 0.22f * armCushion;
		p.lArmZ -= squash * 0.22f * armCushion;

		float stillFactor = 1.0f - currentMoveBlend;
		p.rLegX += squash * (-0.30f - 0.12f * jumpLeadSign) * stillFactor;
		p.lLegX += squash * (-0.30f + 0.12f * jumpLeadSign) * stillFactor;
		p.rShinX = Math.max(0.0f, p.rShinX + squash * 0.72f * moveAtten);
		p.lShinX = Math.max(0.0f, p.lShinX + squash * 0.72f * moveAtten);
		p.rLegZ += squash * 0.045f * stillFactor;
		p.lLegZ -= squash * 0.045f * stillFactor;
	}

	// =========================================================================
	// Ocarina of Time Ledge Hang, Shimmy & Climb-Up Animator
	// =========================================================================

	private static void computeLedgePose(Pose p) {
		p.wholeSpinY = 0.0f;
		p.wholeFlipX = 0.0f;
		p.wholeRollZ = 0.0f;
		p.wholeShiftX = 0.0f;

		if (ZeldaLedgeSystem.isClimbing()) {
			float u = ZeldaLedgeSystem.getClimbProgress();
			float bodyTwist = (float) Math.sin(u * Math.PI) * 0.14f;

			p.wholeLeanX = CLIMB_LEAN_X.eval(u);
			p.wholeBankZ = -bodyTwist * 0.35f;
			p.wholeOffsetY = CLIMB_OFFSET_Y.eval(u);
			p.wholeScaleY = CLIMB_SCALE_Y.eval(u);

			p.bodyX = 0.06f * (float) Math.sin(u * Math.PI);
			p.bodyY = bodyTwist;
			p.bodyZ = 0.0f;

			p.headX = CLIMB_HEAD_X.eval(u);
			p.headY = -bodyTwist * 0.5f;
			p.headZ = 0.0f;

			p.rArmX = CLIMB_RARM_X.eval(u);
			p.rArmY = 0.0f;
			p.rArmZ = -0.06f * (1.0f - u) + 0.08f * (float) Math.sin(u * Math.PI);
			p.rForearmX = CLIMB_RFOREARM_X.eval(u);
			p.rForearmY = 0.0f;

			p.lArmX = CLIMB_LARM_X.eval(u);
			p.lArmY = 0.0f;
			p.lArmZ = 0.06f * (1.0f - u) - 0.08f * (float) Math.sin(u * Math.PI);
			p.lForearmX = CLIMB_LFOREARM_X.eval(u);
			p.lForearmY = 0.0f;

			p.rLegX = CLIMB_RLEG_X.eval(u);
			p.rLegY = 0.0f;
			p.rLegZ = 0.06f * (float) Math.sin(u * Math.PI) + 0.022f;
			p.rShinX = CLIMB_RSHIN_X.eval(u);

			p.lLegX = CLIMB_LLEG_X.eval(u);
			p.lLegY = 0.0f;
			p.lLegZ = -0.06f * (float) Math.sin(u * Math.PI) - 0.022f;
			p.lShinX = CLIMB_LSHIN_X.eval(u);
			return;
		}

		// HANGING / SHIMMYING
		float catchW = 1.0f - smoothstep(0.0f, 1.0f, ZeldaLedgeSystem.getCatchProgress());
		float shimmyVel = ZeldaLedgeSystem.getShimmyVelNorm();
		float shimmyMag = Math.abs(shimmyVel);
		float phase = ZeldaLedgeSystem.getShimmyPhase() * Mth.TWO_PI;
		float reach = Mth.sin(phase);
		float breath = Mth.sin(ledgeClock * 2.4f);

		p.wholeLeanX = 0.07f + 0.05f * catchW;
		p.wholeBankZ = shimmyVel * 0.045f + reach * 0.055f * shimmyMag;
		p.wholeOffsetY = -0.065f * catchW + Math.abs(reach) * 0.025f * shimmyMag + 0.008f * breath;
		p.wholeScaleY = 1.0f + 0.04f * catchW;

		p.bodyX = 0.04f;
		p.bodyY = reach * 0.14f * shimmyMag;
		p.bodyZ = -reach * 0.04f * shimmyMag;

		p.headX = -0.26f - 0.10f * catchW;
		p.headY = shimmyVel * 0.24f - p.bodyY * 0.55f;
		p.headZ = -p.wholeBankZ * 0.5f;

		float rReachLift = Math.max(0.0f, reach) * shimmyMag;
		float lReachLift = Math.max(0.0f, -reach) * shimmyMag;
		float rPull = Math.max(0.0f, -reach) * shimmyMag;
		float lPull = Math.max(0.0f, reach) * shimmyMag;

		p.rArmX = -2.62f - 0.26f * rReachLift + 0.22f * rPull - 0.12f * catchW;
		p.rArmY = -0.06f * shimmyMag;
		p.rArmZ = -0.08f + 0.06f * rPull - 0.04f * rReachLift;
		p.rForearmX = -0.46f + 0.20f * rReachLift - 0.34f * rPull - 0.10f * catchW;
		p.rForearmY = 0.0f;

		p.lArmX = -2.62f - 0.26f * lReachLift + 0.22f * lPull - 0.12f * catchW;
		p.lArmY = 0.06f * shimmyMag;
		p.lArmZ = 0.08f - 0.06f * lPull + 0.04f * lReachLift;
		p.lForearmX = -0.46f + 0.20f * lReachLift - 0.34f * lPull - 0.10f * catchW;
		p.lForearmY = 0.0f;

		float legPendulum = Mth.sin(phase - 0.6f) * shimmyMag;
		p.rLegX = 0.08f + 0.14f * catchW + 0.03f * breath + 0.12f * legPendulum;
		p.rLegY = 0.0f;
		p.rLegZ = 0.04f - shimmyVel * 0.10f + 0.12f * legPendulum;
		p.rShinX = 0.28f + 0.32f * catchW + 0.20f * Math.max(0.0f, -legPendulum);

		p.lLegX = 0.04f + 0.10f * catchW - 0.03f * breath - 0.12f * legPendulum;
		p.lLegY = 0.0f;
		p.lLegZ = -0.04f - shimmyVel * 0.10f + 0.12f * legPendulum;
		p.lShinX = 0.34f + 0.26f * catchW + 0.20f * Math.max(0.0f, legPendulum);
	}

	// =========================================================================
	// Pro Moving-Hold & Keyframed Ocarina of Time 3D Idle Scenarios
	// =========================================================================

	private static void computeIdleScenarioPose(IdleScenario scenario, float t, Pose p) {
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

	/** Calm, solid moving-hold hero stance: poised stillness with soft knees and natural elbows. */
	private static void buildBaseHeroStance(float t, Pose p) {
		float breathU = (t / 4.0f) % 1.0f;
		float breath = HERO_BREATH.eval(breathU);
		float shoulderLag = HERO_BREATH.eval((breathU + 0.92f) % 1.0f);

		p.headX = 0.014f * breath;
		p.headY = 0.0f;
		p.headZ = 0.0f;

		p.bodyX = 0.0f;
		p.bodyY = 0.032f;
		p.bodyZ = 0.0f;

		p.rArmX = 0.025f + 0.016f * shoulderLag;
		p.rArmY = 0.0f;
		p.rArmZ = 0.048f + 0.014f * shoulderLag;
		p.rForearmX = -0.14f - 0.02f * shoulderLag;
		p.rForearmY = 0.0f;

		p.lArmX = 0.045f - 0.014f * shoulderLag;
		p.lArmY = 0.0f;
		p.lArmZ = -0.048f - 0.014f * shoulderLag;
		p.lForearmX = -0.12f - 0.02f * shoulderLag;
		p.lForearmY = 0.0f;

		p.rLegX = -0.040f;
		p.rLegY = 0.0f;
		p.rLegZ = 0.022f;
		p.rShinX = 0.045f;

		p.lLegX = 0.010f;
		p.lLegY = 0.0f;
		p.lLegZ = -0.022f;
		p.lShinX = 0.060f;

		p.wholeLeanX = 0.008f;
		p.wholeBankZ = 0.0f;
		p.wholeOffsetY = 0.0f;
		p.wholeShiftX = 0.0f;
	}

	private static void buildNeutralPose(float t, Pose p) {
		buildBaseHeroStance(t, p);

		switch (neutralAction) {
			case LOOK_AROUND -> {
				float u = Mth.clamp(neutralActionTime / neutralActionDuration, 0.0f, 1.0f);
				float yaw = LOOK_YAW_CURVE.eval(u) * 0.48f * neutralSign;
				float pitch = LOOK_PITCH_CURVE.eval(u) * 0.07f;
				p.headY += yaw;
				p.headX += pitch;
				p.headZ += 0.045f * LOOK_YAW_CURVE.eval(u) * neutralSign;
				p.bodyY += 0.14f * yaw;
			}
			case STANCE_CHECK -> {
				float u = Mth.clamp(neutralActionTime / neutralActionDuration, 0.0f, 1.0f);
				float w = smoothWindow(u);
				float tap = (u > 0.32f && u < 0.72f)
						? (float) Math.pow(Math.abs(Math.sin(((u - 0.32f) / 0.40f) * Math.PI * 2.0)), 1.6)
						: 0.0f;
				p.wholeBankZ = -0.028f * neutralSign * w;
				p.wholeShiftX = -0.022f * neutralSign * w;
				p.headX = Mth.lerp(w, p.headX, 0.14f);
				p.headY = Mth.lerp(w, p.headY, 0.10f * neutralSign);
				p.rArmX = Mth.lerp(w, p.rArmX, 0.10f);
				p.rArmY = Mth.lerp(w, p.rArmY, -0.18f);
				p.rArmZ = Mth.lerp(w, p.rArmZ, 0.025f);
				p.rForearmX = Mth.lerp(w, p.rForearmX, -0.68f);
				if (neutralSign > 0) {
					p.lLegX = Mth.lerp(w, p.lLegX, -0.16f * tap);
					p.lShinX = Mth.lerp(w, p.lShinX, 0.08f + 0.22f * tap);
				} else {
					p.rLegX = Mth.lerp(w, p.rLegX, -0.16f * tap);
					p.rShinX = Mth.lerp(w, p.rShinX, 0.08f + 0.22f * tap);
				}
			}
			case STRETCH_SIGH -> {
				float st = neutralActionTime;
				if (st < 0.36f) {
					float a = easeInOut(st / 0.36f);
					p.wholeOffsetY = -0.020f * a;
					p.wholeLeanX += 0.03f * a;
					p.rShinX += 0.14f * a;
					p.lShinX += 0.14f * a;
				} else if (st < 1.95f) {
					float rise = easeOut(Mth.clamp((st - 0.36f) / 0.58f, 0.0f, 1.0f));
					p.wholeLeanX = Mth.lerp(rise, p.wholeLeanX, -0.048f);
					p.wholeOffsetY = Mth.lerp(rise, 0.0f, 0.014f);
					p.headX = Mth.lerp(rise, p.headX, -0.22f);
					p.rArmX = Mth.lerp(rise, p.rArmX, -1.96f);
					p.rArmZ = Mth.lerp(rise, p.rArmZ, 0.22f);
					p.rForearmX = Mth.lerp(rise, p.rForearmX, -0.28f);
					p.lArmX = Mth.lerp(rise, p.lArmX, -1.96f);
					p.lArmZ = Mth.lerp(rise, p.lArmZ, -0.22f);
					p.lForearmX = Mth.lerp(rise, p.lForearmX, -0.28f);
				} else {
					float settle = easeOut(Mth.clamp((st - 1.95f) / 0.85f, 0.0f, 1.0f));
					p.wholeLeanX = Mth.lerp(settle, -0.048f, p.wholeLeanX);
					p.wholeOffsetY = Mth.lerp(settle, 0.014f, 0.0f);
					p.headX = Mth.lerp(settle, -0.22f, p.headX);
					p.rArmX = Mth.lerp(settle, -1.96f, p.rArmX);
					p.rArmZ = Mth.lerp(settle, 0.22f, p.rArmZ);
					p.rForearmX = Mth.lerp(settle, -0.28f, p.rForearmX);
					p.lArmX = Mth.lerp(settle, -1.96f, p.lArmX);
					p.lArmZ = Mth.lerp(settle, -0.22f, p.lArmZ);
					p.lForearmX = Mth.lerp(settle, -0.28f, p.lForearmX);
				}
			}
			case WEIGHT_SHIFT -> {
				float u = Mth.clamp(neutralActionTime / neutralActionDuration, 0.0f, 1.0f);
				float w = smoothWindow(u);
				p.wholeShiftX = 0.032f * neutralSign * w;
				p.wholeBankZ = 0.036f * neutralSign * w;
				p.wholeOffsetY = -0.014f * w;
				p.bodyY = Mth.lerp(w, p.bodyY, 0.045f + 0.06f * neutralSign);
				p.headZ = Mth.lerp(w, p.headZ, -0.042f * neutralSign);
				p.headX = Mth.lerp(w, p.headX, 0.10f);
				p.rLegX = Mth.lerp(w, p.rLegX, -0.08f * neutralSign);
				p.lLegX = Mth.lerp(w, p.lLegX, 0.04f * neutralSign);
				p.rShinX = Mth.lerp(w, p.rShinX, neutralSign > 0 ? 0.16f : 0.02f);
				p.lShinX = Mth.lerp(w, p.lShinX, neutralSign > 0 ? 0.02f : 0.16f);
			}
			case NONE -> {}
		}
	}

	private static void buildLowHealthPose(float t, Pose p) {
		float pantU = (t / 1.18f) % 1.0f;
		float pant = HERO_BREATH.eval(pantU);
		float armLag = HERO_BREATH.eval((pantU + 0.88f) % 1.0f);

		p.wholeLeanX = 0.072f + 0.016f * pant;
		p.wholeOffsetY = -0.038f - 0.010f * (1.0f - pant);
		p.wholeBankZ = 0.012f * Mth.sin(t * 0.8f);
		p.wholeShiftX = 0.0f;

		p.headX = 0.22f + 0.045f * pant;
		p.headY = 0.04f * Mth.sin(t * 0.65f);
		p.headZ = 0.025f * Mth.sin(t * 0.65f);

		p.bodyX = 0.0f;
		p.bodyY = 0.02f;
		p.bodyZ = 0.0f;

		p.rArmX = 0.06f + 0.025f * armLag;
		p.rArmY = -0.14f;
		p.rArmZ = 0.05f;
		p.rForearmX = -0.44f - 0.05f * armLag;
		p.rForearmY = -0.08f;

		p.lArmX = 0.14f - 0.022f * armLag;
		p.lArmY = 0.0f;
		p.lArmZ = -0.07f;
		p.lForearmX = -0.34f - 0.04f * armLag;
		p.lForearmY = 0.0f;

		p.rLegX = -0.18f;
		p.rLegY = 0.0f;
		p.rLegZ = 0.04f;
		p.rShinX = 0.26f;

		p.lLegX = 0.02f;
		p.lLegY = 0.0f;
		p.lLegZ = -0.04f;
		p.lShinX = 0.22f;
	}

	private static void buildHostileAlertPose(float t, Pose p) {
		float breath = HERO_BREATH.eval((t / 1.9f) % 1.0f);
		float guardPulse = alertShifting ? smoothWindow(alertShiftTime / 1.5f) : 0.0f;
		float torsoYaw = Mth.clamp(idleTargetRelYaw * 0.34f, -0.34f, 0.34f);

		p.wholeLeanX = 0.058f + 0.010f * breath;
		p.wholeBankZ = -0.020f * Mth.clamp(idleTargetRelYaw, -0.6f, 0.6f);
		p.wholeOffsetY = -0.028f - 0.006f * (1.0f - breath);
		p.wholeShiftX = 0.0f;

		p.headX = Mth.clamp(idleTargetRelPitch - p.wholeLeanX * 0.35f, -0.48f, 0.45f) + 0.015f * breath;
		p.headY = idleTargetRelYaw;
		p.headZ = -0.045f * Mth.clamp(idleTargetRelYaw, -0.55f, 0.55f);

		p.bodyX = 0.0f;
		p.bodyY = torsoYaw;
		p.bodyZ = 0.0f;

		p.rArmX = 0.24f + 0.024f * breath + 0.06f * guardPulse;
		p.rArmY = -0.08f;
		p.rArmZ = 0.10f + 0.025f * guardPulse;
		p.rForearmX = -0.46f - 0.04f * guardPulse;
		p.rForearmY = 0.0f;

		p.lArmX = 0.02f + 0.020f * breath - 0.06f * guardPulse;
		p.lArmY = 0.28f + 0.06f * guardPulse;
		p.lArmZ = -0.03f;
		p.lForearmX = -0.68f - 0.06f * guardPulse;
		p.lForearmY = 0.14f;

		p.rLegX = 0.06f;
		p.rLegY = 0.06f;
		p.rLegZ = 0.05f;
		p.rShinX = 0.30f;

		p.lLegX = -0.26f;
		p.lLegY = -0.05f;
		p.lLegZ = -0.05f;
		p.lShinX = 0.28f;
	}

	private static void buildCliffEdgePose(float t, Pose p) {
		float breath = HERO_BREATH.eval((t / 2.4f) % 1.0f);
		float lookSweep = 0.0f;
		float glanceBank = 0.0f;
		if (cliffGlancing) {
			float u = cliffGlanceTime / 2.6f;
			float curve = LOOK_YAW_CURVE.eval(u);
			lookSweep = 0.36f * cliffGlanceSign * curve;
			glanceBank = 0.028f * cliffGlanceSign * curve;
		}

		p.wholeLeanX = 0.090f + 0.010f * breath;
		p.wholeBankZ = glanceBank;
		p.wholeOffsetY = -0.014f;
		p.wholeShiftX = 0.0f;

		p.headX = 0.42f + 0.026f * breath;
		p.headY = Mth.clamp(idleTargetRelYaw * 0.80f + lookSweep, -1.05f, 1.05f);
		p.headZ = glanceBank * 0.7f;

		p.bodyX = 0.0f;
		p.bodyY = Mth.clamp(idleTargetRelYaw * 0.28f + 0.12f * lookSweep, -0.32f, 0.32f);
		p.bodyZ = 0.0f;

		p.rArmX = 0.02f;
		p.rArmY = 0.0f;
		p.rArmZ = 0.16f + 0.03f * breath;
		p.rForearmX = -0.26f;
		p.rForearmY = 0.0f;

		p.lArmX = 0.12f;
		p.lArmY = 0.0f;
		p.lArmZ = -0.16f - 0.03f * breath;
		p.lForearmX = -0.24f;
		p.lForearmY = 0.0f;

		p.rLegX = -0.14f;
		p.rLegY = 0.0f;
		p.rLegZ = 0.04f;
		p.rShinX = 0.18f;

		p.lLegX = 0.02f;
		p.lLegY = 0.0f;
		p.lLegZ = -0.04f;
		p.lShinX = 0.16f;
	}

	private static void buildColdPose(float t, Pose p) {
		float shiverWindow = Mth.clamp(Mth.sin(t * 1.4f) * 1.5f + 0.3f, 0.0f, 1.0f);
		float shiver = Mth.sin(t * 28.0f) * shiverWindow;

		p.wholeLeanX = 0.032f;
		p.wholeBankZ = 0.0f;
		p.wholeOffsetY = -0.012f;
		p.wholeShiftX = 0.0f;

		p.headX = 0.18f + 0.016f * shiverWindow;
		p.headY = 0.028f * shiver;
		p.headZ = 0.018f * shiver;

		p.bodyX = 0.0f;
		p.bodyY = 0.015f * shiver;
		p.bodyZ = 0.0f;

		// Arms hunched & forearms folded tightly across torso against the cold
		p.rArmX = -0.08f;
		p.rArmY = -0.24f;
		p.rArmZ = -0.03f + 0.02f * shiver;
		p.rForearmX = -1.04f;
		p.rForearmY = -0.22f;

		p.lArmX = -0.06f;
		p.lArmY = 0.24f;
		p.lArmZ = 0.03f - 0.02f * shiver;
		p.lForearmX = -1.04f;
		p.lForearmY = 0.22f;

		p.rLegX = -0.07f;
		p.rLegY = 0.0f;
		p.rLegZ = 0.02f;
		p.rShinX = 0.12f;

		p.lLegX = -0.02f;
		p.lLegY = 0.0f;
		p.lLegZ = -0.02f;
		p.lShinX = 0.12f;

		if (coldSneezing) {
			float st = coldSneezeTime;
			if (st < 0.72f) {
				float u = easeInOut(st / 0.72f);
				p.wholeLeanX = Mth.lerp(u, p.wholeLeanX, -0.05f);
				p.headX = Mth.lerp(u, p.headX, -0.32f);
				p.rArmX = Mth.lerp(u, p.rArmX, -0.26f);
				p.lArmX = Mth.lerp(u, p.lArmX, -0.26f);
				p.rForearmX = Mth.lerp(u, p.rForearmX, -0.68f);
				p.lForearmX = Mth.lerp(u, p.lForearmX, -0.68f);
			} else if (st < 1.08f) {
				float k = (st - 0.72f) / 0.36f;
				float snap = (float) Math.sin(k * Math.PI);
				p.wholeLeanX = Mth.lerp(snap, 0.0f, 0.15f);
				p.headX = Mth.lerp(snap, -0.14f, 0.48f);
				p.rArmX = -0.08f;
				p.rArmZ = 0.20f * snap;
				p.rForearmX = -0.34f;
				p.lArmX = -0.08f;
				p.lArmZ = -0.20f * snap;
				p.lForearmX = -0.34f;
			} else if (st < 1.80f) {
				float u = smoothWindow((st - 1.08f) / 0.72f);
				p.rArmX = Mth.lerp(u, p.rArmX, -0.46f);
				p.rArmY = Mth.lerp(u, p.rArmY, -0.30f);
				p.rArmZ = Mth.lerp(u, p.rArmZ, 0.05f);
				p.rForearmX = Mth.lerp(u, p.rForearmX, -1.18f);
				p.headX = Mth.lerp(u, p.headX, 0.20f);
			}
		}
	}

	private static void buildHotPose(float t, Pose p) {
		float breath = HERO_BREATH.eval((t / 2.6f) % 1.0f);

		p.wholeLeanX = 0.028f;
		p.wholeBankZ = 0.0f;
		p.wholeOffsetY = -0.010f * (1.0f - breath);
		p.wholeShiftX = 0.0f;

		p.headX = 0.06f + 0.022f * breath;
		p.headY = 0.0f;
		p.headZ = 0.0f;

		p.bodyX = 0.0f;
		p.bodyY = 0.032f;
		p.bodyZ = 0.0f;

		p.rArmX = 0.02f + 0.016f * breath;
		p.rArmY = 0.0f;
		p.rArmZ = 0.060f;
		p.rForearmX = -0.14f;
		p.rForearmY = 0.0f;

		p.lArmX = 0.04f - 0.014f * breath;
		p.lArmY = 0.0f;
		p.lArmZ = -0.060f;
		p.lForearmX = -0.14f;
		p.lForearmY = 0.0f;

		p.rLegX = -0.045f;
		p.rLegY = 0.0f;
		p.rLegZ = 0.024f;
		p.rShinX = 0.055f;

		p.lLegX = 0.015f;
		p.lLegY = 0.0f;
		p.lLegZ = -0.024f;
		p.lShinX = 0.065f;

		if (hotAction == HotAction.WIPE_BROW) {
			float u = hotActionTime;
			if (u < 1.75f) {
				float w = smoothWindow(u / 1.75f);
				float sweep = Mth.sin((u / 1.75f) * Mth.PI);
				p.rArmX = Mth.lerp(w, p.rArmX, -0.78f);
				p.rArmY = Mth.lerp(w, p.rArmY, -0.38f + 0.44f * sweep);
				p.rArmZ = Mth.lerp(w, p.rArmZ, 0.18f);
				p.rForearmX = Mth.lerp(w, p.rForearmX, -1.16f);
				p.headX = Mth.lerp(w, p.headX, -0.10f);
				p.headY = Mth.lerp(w, p.headY, 0.14f - 0.12f * sweep);
			} else {
				float flick = smoothWindow((u - 1.75f) / 0.70f);
				p.rArmX = Mth.lerp(flick, -0.06f, -0.36f);
				p.rArmZ = Mth.lerp(flick, 0.060f, 0.28f);
				p.rForearmX = Mth.lerp(flick, -0.14f, -0.34f);
			}
		} else if (hotAction == HotAction.FAN_FACE) {
			float w = smoothWindow(hotActionTime / 2.35f);
			float wave = Mth.sin(hotActionTime * 11.5f);
			p.lArmX = Mth.lerp(w, p.lArmX, -0.26f);
			p.lArmY = Mth.lerp(w, p.lArmY, 0.34f);
			p.lArmZ = Mth.lerp(w, p.lArmZ, -0.04f);
			p.lForearmX = Mth.lerp(w, p.lForearmX, -1.04f + 0.24f * wave);
			p.headX = Mth.lerp(w, p.headX, -0.04f);
		}
	}

	private static void buildRainPose(float t, Pose p) {
		buildBaseHeroStance(t, p);
		if (rainChecking) {
			float w = smoothWindow(rainCheckTime / 2.8f);
			float scan = Mth.sin(rainCheckTime * 1.8f);
			p.headX = Mth.lerp(w, p.headX, -0.36f);
			p.headY = Mth.lerp(w, p.headY, 0.16f * scan);
			p.rArmX = Mth.lerp(w, p.rArmX, -0.54f);
			p.rArmY = Mth.lerp(w, p.rArmY, -0.14f);
			p.rArmZ = Mth.lerp(w, p.rArmZ, 0.16f);
			p.rForearmX = Mth.lerp(w, p.rForearmX, -0.52f);
		}
	}

	// =========================================================================
	// Model Application (Flush Pivots + Articulated Forearms & Shins)
	// =========================================================================

	private static void applyPoseToModel(HumanoidModel<?> m, Pose p, float w, boolean preserveHeadLook) {
		boolean slim = (m instanceof ArticulatedPlayerModel apm && apm.ocarina$isSlim());
		float baseShoulderY = slim ? 2.5f : 2.0f;

		if (preserveHeadLook) {
			m.head.xRot += p.headX;
			m.head.zRot = p.headZ;
		} else {
			m.head.xRot = Mth.lerp(w, m.head.xRot, p.headX);
			m.head.yRot = Mth.lerp(w, m.head.yRot, p.headY);
			m.head.zRot = Mth.lerp(w, m.head.zRot, p.headZ);
		}
		m.head.y = 0.0f;

		m.body.xRot = Mth.lerp(w, m.body.xRot, p.bodyX);
		m.body.yRot = Mth.lerp(w, m.body.yRot, p.bodyY);
		m.body.zRot = Mth.lerp(w, m.body.zRot, p.bodyZ);
		m.body.y = 0.0f;

		float bx = m.body.xRot;
		float by = m.body.yRot;
		float bz = m.body.zRot;
		float rShoulderX = -5.0f * Mth.cos(by);
		float rShoulderY = baseShoulderY * Mth.cos(bx) + m.body.y - 5.0f * Mth.sin(bz);
		float rShoulderZ = 4.6f * Mth.sin(by) + 2.0f * Mth.sin(bx);
		float lShoulderX = 5.0f * Mth.cos(by);
		float lShoulderY = baseShoulderY * Mth.cos(bx) + m.body.y + 5.0f * Mth.sin(bz);
		float lShoulderZ = -4.6f * Mth.sin(by) + 2.0f * Mth.sin(bx);
		float hipZ = 10.5f * Mth.sin(bx);

		m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, p.rArmX);
		m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, p.rArmY);
		m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, p.rArmZ);
		m.rightArm.x = Mth.lerp(w, -5.0f, rShoulderX);
		m.rightArm.y = Mth.lerp(w, baseShoulderY, rShoulderY);
		m.rightArm.z = Mth.lerp(w, 0.0f, rShoulderZ);

		m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, p.lArmX);
		m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, p.lArmY);
		m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, p.lArmZ);
		m.leftArm.x = Mth.lerp(w, 5.0f, lShoulderX);
		m.leftArm.y = Mth.lerp(w, baseShoulderY, lShoulderY);
		m.leftArm.z = Mth.lerp(w, 0.0f, lShoulderZ);

		m.rightLeg.xRot = Mth.lerp(w, m.rightLeg.xRot, p.rLegX);
		m.rightLeg.yRot = Mth.lerp(w, m.rightLeg.yRot, p.rLegY);
		m.rightLeg.zRot = Mth.lerp(w, m.rightLeg.zRot, p.rLegZ);
		m.rightLeg.z = Mth.lerp(w, 0.0f, hipZ + 1.6f * Mth.sin(by));

		m.leftLeg.xRot = Mth.lerp(w, m.leftLeg.xRot, p.lLegX);
		m.leftLeg.yRot = Mth.lerp(w, m.leftLeg.yRot, p.lLegY);
		m.leftLeg.zRot = Mth.lerp(w, m.leftLeg.zRot, p.lLegZ);
		m.leftLeg.z = Mth.lerp(w, 0.0f, hipZ - 1.6f * Mth.sin(by));

		if (m instanceof ArticulatedPlayerModel apm) {
			applyChildJoint(apm.ocarina$rightForearm(), apm.ocarina$rightForearmSleeve(),
					p.rForearmX, p.rForearmY, w);
			applyChildJoint(apm.ocarina$leftForearm(), apm.ocarina$leftForearmSleeve(),
					p.lForearmX, p.lForearmY, w);
			applyChildJoint(apm.ocarina$rightShin(), apm.ocarina$rightShinPants(),
					p.rShinX, 0.0f, w);
			applyChildJoint(apm.ocarina$leftShin(), apm.ocarina$leftShinPants(),
					p.lShinX, 0.0f, w);
		}

		m.hat.copyFrom(m.head);
	}

	private static void applyChildJoint(ModelPart part, ModelPart overlay, float targetX, float targetY, float w) {
		if (part != null) {
			part.xRot = Mth.lerp(w, part.xRot, targetX);
			part.yRot = Mth.lerp(w, part.yRot, targetY);
			part.zRot = 0.0f;
			if (overlay != null) {
				overlay.copyFrom(part);
			}
		}
	}

	public static void resetResidualModelPivots(HumanoidModel<?> m) {
		boolean slim = (m instanceof ArticulatedPlayerModel apm && apm.ocarina$isSlim());
		float baseShoulderY = slim ? 2.5f : 2.0f;
		m.head.zRot = 0.0f;
		m.head.y = 0.0f;
		m.body.zRot = 0.0f;
		m.body.y = 0.0f;
		m.rightArm.x = -5.0f;
		m.rightArm.y = baseShoulderY;
		m.rightArm.z = 0.0f;
		m.leftArm.x = 5.0f;
		m.leftArm.y = baseShoulderY;
		m.leftArm.z = 0.0f;
		m.rightLeg.zRot = 0.0f;
		m.rightLeg.z = 0.0f;
		m.leftLeg.zRot = 0.0f;
		m.leftLeg.z = 0.0f;
		if (m instanceof ArticulatedPlayerModel apm) {
			applyChildJoint(apm.ocarina$rightForearm(), apm.ocarina$rightForearmSleeve(), 0.0f, 0.0f, 1.0f);
			applyChildJoint(apm.ocarina$leftForearm(), apm.ocarina$leftForearmSleeve(), 0.0f, 0.0f, 1.0f);
			applyChildJoint(apm.ocarina$rightShin(), apm.ocarina$rightShinPants(), 0.0f, 0.0f, 1.0f);
			applyChildJoint(apm.ocarina$leftShin(), apm.ocarina$leftShinPants(), 0.0f, 0.0f, 1.0f);
		}
		m.hat.copyFrom(m.head);
	}

	// =========================================================================
	// World Sensors & Timers
	// =========================================================================

	private static boolean isBodyAnimationControllable(LocalPlayer player) {
		return !player.isInWater()
				&& !player.isInLava()
				&& !player.isSwimming()
				&& !player.isPassenger()
				&& !player.onClimbable()
				&& !player.isFallFlying()
				&& !player.isUsingItem()
				&& player.swingTime <= 0
				&& !player.isSleeping()
				&& !player.isCrouching();
	}

	private static void sensePeripheralEnemy(LocalPlayer player) {
		ClientLevel level = player.clientLevel;
		if (level == null) {
			enemyVisibleMove = false;
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
			if (d >= bestDist) continue;
			double dx = mob.getX() - player.getX();
			double dz = mob.getZ() - player.getZ();
			float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float relYawDeg = Mth.wrapDegrees(worldYaw - player.yBodyRot);
			if (Math.abs(relYawDeg) <= 125.0f && player.hasLineOfSight(mob)) {
				bestDist = d;
				best = mob;
				double dy = mob.getEyeY() - player.getEyeY();
				double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
				bestRelYaw = Mth.clamp(relYawDeg * Mth.DEG_TO_RAD, -1.15f, 1.15f);
				bestPitch = Mth.clamp((float) Math.atan2(-dy, horiz), -0.50f, 0.50f);
			}
		}

		if (best != null) {
			enemyVisibleMove = true;
			enemyYawTarget = bestRelYaw;
			enemyPitchTarget = bestPitch;
		} else {
			enemyVisibleMove = false;
		}
	}

	private static void senseIdleContext(LocalPlayer player) {
		ClientLevel level = player.clientLevel;
		if (level == null) {
			activeScenario = IdleScenario.NEUTRAL;
			return;
		}

		if (player.getHealth() <= 4.0f) {
			activeScenario = IdleScenario.LOW_HEALTH;
			idleTargetRelYaw = 0.0f;
			idleTargetRelPitch = 0.0f;
			return;
		}

		Mob nearestEnemy = findNearestHostile(level, player);
		if (nearestEnemy != null) {
			activeScenario = IdleScenario.HOSTILE_ALERT;
			double dx = nearestEnemy.getX() - player.getX();
			double dz = nearestEnemy.getZ() - player.getZ();
			double dy = nearestEnemy.getEyeY() - player.getEyeY();
			double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
			float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float relYaw = Mth.wrapDegrees(worldYaw - player.yBodyRot);
			idleTargetRelYaw = Mth.clamp(relYaw * Mth.DEG_TO_RAD, -1.10f, 1.10f);
			idleTargetRelPitch = Mth.clamp((float) Math.atan2(-dy, horiz), -0.52f, 0.52f);
			return;
		}

		Float cliffYaw = findCliffEdgeYaw(level, player);
		if (cliffYaw != null) {
			activeScenario = IdleScenario.CLIFF_EDGE;
			float relYaw = Mth.wrapDegrees(cliffYaw - player.yBodyRot);
			idleTargetRelYaw = Mth.clamp(relYaw * Mth.DEG_TO_RAD, -1.10f, 1.10f);
			idleTargetRelPitch = 0.52f;
			return;
		}

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

		if (level.dimensionType().ultraWarm() || biome.getBaseTemperature() >= 1.8f || isNearHeatSource(level, pos)) {
			activeScenario = IdleScenario.HOT;
			return;
		}

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

	private static void resetFidgetTimers() {
		neutralAction = NeutralAction.NONE;
		neutralActionTime = 0.0f;
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

	private static void advanceIdleActionTimers(float dt) {
		switch (activeScenario) {
			case NEUTRAL -> {
				if (neutralAction != NeutralAction.NONE) {
					neutralActionTime += dt;
					if (neutralActionTime >= neutralActionDuration) {
						neutralAction = NeutralAction.NONE;
						neutralActionTime = 0.0f;
						neutralNextCooldown = rand(3.8f, 8.2f);
					}
				} else {
					neutralNextCooldown -= dt;
					if (neutralNextCooldown <= 0.0f) {
						pickNextNeutralAction();
					}
				}
			}
			case COLD -> {
				if (coldSneezing) {
					coldSneezeTime += dt;
					if (coldSneezeTime >= 2.20f) {
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
						hotActionDuration = (hotAction == HotAction.WIPE_BROW) ? 2.45f : 2.35f;
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
					if (alertShiftTime >= 1.5f) {
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

	private static void pickNextNeutralAction() {
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		int pick;
		do {
			pick = rng.nextInt(4);
		} while (pick == lastNeutralPick);
		lastNeutralPick = pick;
		neutralSign = rng.nextBoolean() ? 1 : -1;
		neutralActionTime = 0.0f;
		switch (pick) {
			case 0 -> {
				neutralAction = NeutralAction.LOOK_AROUND;
				neutralActionDuration = rand(2.7f, 3.3f);
			}
			case 1 -> {
				neutralAction = NeutralAction.STANCE_CHECK;
				neutralActionDuration = rand(2.4f, 2.9f);
			}
			case 2 -> {
				neutralAction = NeutralAction.STRETCH_SIGH;
				neutralActionDuration = 2.8f;
			}
			default -> {
				neutralAction = NeutralAction.WEIGHT_SHIFT;
				neutralActionDuration = rand(2.4f, 3.0f);
			}
		}
	}

	// =========================================================================
	// Math Utilities
	// =========================================================================

	private static void copyPose(Pose src, Pose out) {
		out.headX = src.headX; out.headY = src.headY; out.headZ = src.headZ;
		out.bodyX = src.bodyX; out.bodyY = src.bodyY; out.bodyZ = src.bodyZ;
		out.rArmX = src.rArmX; out.rArmY = src.rArmY; out.rArmZ = src.rArmZ;
		out.lArmX = src.lArmX; out.lArmY = src.lArmY; out.lArmZ = src.lArmZ;
		out.rForearmX = src.rForearmX; out.rForearmY = src.rForearmY;
		out.lForearmX = src.lForearmX; out.lForearmY = src.lForearmY;
		out.rLegX = src.rLegX; out.rLegY = src.rLegY; out.rLegZ = src.rLegZ;
		out.lLegX = src.lLegX; out.lLegY = src.lLegY; out.lLegZ = src.lLegZ;
		out.rShinX = src.rShinX; out.lShinX = src.lShinX;
		out.wholeLeanX = src.wholeLeanX;
		out.wholeBankZ = src.wholeBankZ;
		out.wholeOffsetY = src.wholeOffsetY;
		out.wholeShiftX = src.wholeShiftX;
		out.wholeSpinY = src.wholeSpinY;
		out.wholeFlipX = src.wholeFlipX;
		out.wholeRollZ = src.wholeRollZ;
		out.wholeScaleY = src.wholeScaleY;
	}

	private static void lerpPose(Pose a, Pose b, float t, Pose out) {
		out.headX = Mth.lerp(t, a.headX, b.headX);
		out.headY = Mth.lerp(t, a.headY, b.headY);
		out.headZ = Mth.lerp(t, a.headZ, b.headZ);
		out.bodyX = Mth.lerp(t, a.bodyX, b.bodyX);
		out.bodyY = Mth.lerp(t, a.bodyY, b.bodyY);
		out.bodyZ = Mth.lerp(t, a.bodyZ, b.bodyZ);
		out.rArmX = Mth.lerp(t, a.rArmX, b.rArmX);
		out.rArmY = Mth.lerp(t, a.rArmY, b.rArmY);
		out.rArmZ = Mth.lerp(t, a.rArmZ, b.rArmZ);
		out.lArmX = Mth.lerp(t, a.lArmX, b.lArmX);
		out.lArmY = Mth.lerp(t, a.lArmY, b.lArmY);
		out.lArmZ = Mth.lerp(t, a.lArmZ, b.lArmZ);
		out.rForearmX = Mth.lerp(t, a.rForearmX, b.rForearmX);
		out.rForearmY = Mth.lerp(t, a.rForearmY, b.rForearmY);
		out.lForearmX = Mth.lerp(t, a.lForearmX, b.lForearmX);
		out.lForearmY = Mth.lerp(t, a.lForearmY, b.lForearmY);
		out.rLegX = Mth.lerp(t, a.rLegX, b.rLegX);
		out.rLegY = Mth.lerp(t, a.rLegY, b.rLegY);
		out.rLegZ = Mth.lerp(t, a.rLegZ, b.rLegZ);
		out.lLegX = Mth.lerp(t, a.lLegX, b.lLegX);
		out.lLegY = Mth.lerp(t, a.lLegY, b.lLegY);
		out.lLegZ = Mth.lerp(t, a.lLegZ, b.lLegZ);
		out.rShinX = Mth.lerp(t, a.rShinX, b.rShinX);
		out.lShinX = Mth.lerp(t, a.lShinX, b.lShinX);
		out.wholeLeanX = Mth.lerp(t, a.wholeLeanX, b.wholeLeanX);
		out.wholeBankZ = Mth.lerp(t, a.wholeBankZ, b.wholeBankZ);
		out.wholeOffsetY = Mth.lerp(t, a.wholeOffsetY, b.wholeOffsetY);
		out.wholeShiftX = Mth.lerp(t, a.wholeShiftX, b.wholeShiftX);
		out.wholeSpinY = Mth.lerp(t, a.wholeSpinY, b.wholeSpinY);
		out.wholeFlipX = Mth.lerp(t, a.wholeFlipX, b.wholeFlipX);
		out.wholeRollZ = Mth.lerp(t, a.wholeRollZ, b.wholeRollZ);
		out.wholeScaleY = Mth.lerp(t, a.wholeScaleY, b.wholeScaleY);
	}

	/**
	 * Normalised foot position over one leg cycle: -1 = front (heel strike), +1 = back (toe-off).
	 * Stance is LINEAR (constant foot speed = ground locked).
	 */
	private static float gaitFootS(float tau, float ts) {
		tau = tau - (float) Math.floor(tau);
		if (tau < ts) {
			return -1.0f + 2.0f * tau / ts;
		}
		float q = (tau - ts) / (1.0f - ts);
		float m = (2.0f / ts) * (1.0f - ts) * 0.55f;
		float q2 = q * q;
		float q3 = q2 * q;
		return (2.0f * q3 - 3.0f * q2 + 1.0f) - (-2.0f * q3 + 3.0f * q2) + (q3 - 2.0f * q2 + q) * m + (q3 - q2) * m;
	}

	/** Hip flexion (negative = leg forward, same sign as ModelPart.xRot) with a linear foot-x in stance. */
	private static float gaitLegAngle(float tau, float ts, float front, float back) {
		float xf = (float) Math.sin(front);
		float xb = (float) Math.sin(back);
		float x = -xf + (xf + xb) * (gaitFootS(tau, ts) + 1.0f) * 0.5f;
		return (float) Math.asin(Mth.clamp(x, -0.985f, 0.985f));
	}

	/**
	 * Height (world blocks, relative to the model origin) of the lowest corner of either 2-segment leg
	 * (thigh rotated around hip, shin rotated around knee pivot (0, +6, -2)), after whole-body forward lean.
	 */
	private static float gaitLowestFootY(float aR, float aL, float kR, float kL, float lean, float bodyX, float bodyY) {
		float px = 0.9375f / 16.0f;
		float hipZ = 10.5f * (float) Math.sin(bodyX);
		float yawZ = 1.6f * (float) Math.sin(bodyY);
		float cosLean = (float) Math.cos(lean);
		float sinLean = (float) Math.sin(lean);
		float low = Float.MAX_VALUE;
		for (int leg = 0; leg < 2; leg++) {
			float th = (leg == 0) ? aR : aL;
			float kn = (leg == 0) ? kR : kL;
			float hz = hipZ + ((leg == 0) ? yawZ : -yawZ);
			float ct = (float) Math.cos(th);
			float st = (float) Math.sin(th);
			float ck = (float) Math.cos(kn);
			float sk = (float) Math.sin(kn);
			for (int corner = 0; corner <= 1; corner++) {
				// Bottom corners of lower shin in knee-pivot space: (y=6, z=0) front and (y=6, z=4) back.
				float zK = (corner == 0) ? 0.0f : 4.0f;
				float yKnee = 6.0f * ck - zK * sk;
				float zKnee = 6.0f * sk + zK * ck;
				float yHip = 6.0f + yKnee;
				float zHip = -2.0f + zKnee;
				float yPrime = yHip * ct - zHip * st;
				float zPrime = yHip * st + zHip * ct;
				float yF = (12.0f - yPrime) * px;
				float zF = (hz + zPrime) * px;
				low = Math.min(low, yF * cosLean + zF * sinLean);
			}
		}
		return low;
	}

	private static float wrapRad(float rad) {
		return Mth.wrapDegrees(rad * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
	}

	private static float smoothWindow(float u) {
		float x = Mth.clamp(u, 0.0f, 1.0f);
		if (x < 0.24f) return easeInOut(x / 0.24f);
		if (x > 0.76f) return easeInOut((1.0f - x) / 0.24f);
		return 1.0f;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / Math.max(0.001f, edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
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
