package com.ocarinaengine.client.camera;

import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The brain of the Zelda engine: tracks where the camera is looking.
 * The camera is independent from the player (like in Ocarina of Time).
 */
public final class EngineState {
	public static final float DEFAULT_PITCH = 12.0f;
	private static final float MIN_PITCH = -20.0f;
	private static final float AIM_MIN_PITCH = -72.0f;
	private static final float MAX_PITCH = 75.0f;

	/** Camera speed with the right stick, in degrees per second. */
	private static final float STICK_YAW_SPEED = 110.1f;
	private static final float STICK_PITCH_SPEED = 72.0f;

	/** Camera yaw (same coordinate system as the player: 0 = south). */
	public static float camYaw = 0.0f;
	/** Camera pitch: positive = looking downward. */
	public static float camPitch = DEFAULT_PITCH;
	/** Higher values make the camera recenter behind the player faster. Updated by movement. */
	public static float followStrength = 0.0f;
	/** Smooth blend [0..1] for over-the-shoulder Slingshot aiming camera. */
	public static float slingshotAimBlend = 0.0f;
	/** World-space position of the camera on the latest frame (used for parallax-free reticle raycasting). */
	public static Vec3 aimCameraPos = Vec3.ZERO;

	/** Monotonic render frame counter used to update procedural animations once per frame. */
	public static long frameCounter = 0L;
	/** Delta time (seconds, clamped) of the current render frame; the single clock shared by animation and combat. */
	public static float frameDt = 0.016f;
	private static long lastFrameNanos = 0L;
	private static long manualUntilNanos = 0L;
	private static LocalPlayer lastPlayer = null;

	private EngineState() {}

	public static boolean isActive() {
		if (!OcarinaGameTypeClient.isZelda()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.level != null && mc.getCameraEntity() == mc.player;
	}

	public static void markManual() {
		manualUntilNanos = System.nanoTime() + 1_500_000_000L;
	}

	public static void addCameraRotation(float yawDelta, float pitchDelta) {
		camYaw += yawDelta;
		float minP = ZeldaQuickItems.isAimingSlingshot() ? AIM_MIN_PITCH : MIN_PITCH;
		camPitch = Mth.clamp(camPitch + pitchDelta, minP, MAX_PITCH);
		markManual();
	}

	public static void frameUpdate() {
		frameCounter++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		long now = System.nanoTime();
		if (player == null) {
			lastPlayer = null;
			lastFrameNanos = now;
			slingshotAimBlend = 0.0f;
			ZeldaLedgeSystem.reset();
			return;
		}
		if (player != lastPlayer) {
			lastPlayer = player;
			camYaw = player.getYRot();
			camPitch = DEFAULT_PITCH;
			followStrength = 0.0f;
			slingshotAimBlend = 0.0f;
			lastFrameNanos = now;
			ZeldaLedgeSystem.reset();
		}
		float dt = Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, 0.1f);
		lastFrameNanos = now;
		frameDt = dt;
		if (!isActive()) {
			slingshotAimBlend = 0.0f;
			ZeldaLedgeSystem.reset();
			return;
		}
		if (ZeldaChestCutscene.isActive()) {
			slingshotAimBlend = 0.0f;
			ZeldaLedgeSystem.reset();
			ZeldaChestCutscene.updateCamera(dt);
			return;
		}
		ZeldaCombatSystem.stepImpactEffects(dt);
		ZeldaCombatSystem.stepFrameAnimations(dt);
		ZeldaLedgeSystem.stepFrame(player, dt);
		Gamepad.poll();
		boolean inGame = mc.screen == null && mc.isWindowActive();
		ZeldaTargetingSystem.updateCamera(player, dt);

		boolean aiming = ZeldaQuickItems.isAimingSlingshot();
		float aimTarget = aiming ? 1.0f : 0.0f;
		slingshotAimBlend += (aimTarget - slingshotAimBlend) * (1.0f - (float) Math.exp(-16.0f * dt));
		if (!aiming && camPitch < MIN_PITCH) {
			camPitch += (MIN_PITCH - camPitch) * Math.min(1.0f, dt * 10.0f);
		}

		if (!ZeldaTargetingSystem.isFocusActive()) {
			if (inGame && Gamepad.rightStickActive()) {
				float rx = Gamepad.rightX * Math.abs(Gamepad.rightX);
				float ry = Gamepad.rightY * Math.abs(Gamepad.rightY);
				addCameraRotation(rx * STICK_YAW_SPEED * dt, -ry * STICK_PITCH_SPEED * dt);
			}
			if (!aiming && followStrength > 0.0f && now > manualUntilNanos) {
				float targetYaw = ZeldaLedgeSystem.isActive() ? ZeldaLedgeSystem.getHangYawDeg() : player.getYRot();
				float diff = Mth.wrapDegrees(targetYaw - camYaw);
				float amount = 1.0f - (float) Math.exp(-0.9f * followStrength * dt);
				camYaw += diff * amount;
			}
		}
		camYaw = Mth.wrapDegrees(camYaw);
	}
}