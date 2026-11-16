package com.ocarinaengine.client.camera;

import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The brain of the Zelda engine: tracks where the camera is looking.
 * The camera is independent from the player (like in Ocarina of Time).
 */
public final class EngineState {
	public static final float DEFAULT_PITCH = 12.0f;
	private static final float MIN_PITCH = -20.0f;
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
	/** Monotonic render frame counter used to update procedural animations once per frame. */
	public static long frameCounter = 0L;
	/** Delta time (seconds, clamped) of the current render frame; the single clock shared by animation and combat. */
	public static float frameDt = 0.016f;

	private static long lastFrameNanos = 0L;
	private static long manualUntilNanos = 0L;
	private static LocalPlayer lastPlayer = null;

	private EngineState() {}

	/** True when the Zelda engine controls gameplay (Zelda game type, in-world, not on a title screen). */
	public static boolean isActive() {
		if (!OcarinaGameTypeClient.isZelda()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.level != null && mc.getCameraEntity() == mc.player;
	}

	/** The player just rotated the camera manually: leave it alone for a short time. */
	public static void markManual() {
		manualUntilNanos = System.nanoTime() + 1_500_000_000L;
	}

	public static void addCameraRotation(float yawDelta, float pitchDelta) {
		camYaw += yawDelta;
		camPitch = Mth.clamp(camPitch + pitchDelta, MIN_PITCH, MAX_PITCH);
		markManual();
	}

	/** Called every frame before the camera is positioned. */
	public static void frameUpdate() {
		frameCounter++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		long now = System.nanoTime();
		if (player == null) {
			lastPlayer = null;
			lastFrameNanos = now;
			ZeldaLedgeSystem.reset();
			return;
		}
		// New player (world join, respawn): place the camera behind them.
		if (player != lastPlayer) {
			lastPlayer = player;
			camYaw = player.getYRot();
			camPitch = DEFAULT_PITCH;
			followStrength = 0.0f;
			lastFrameNanos = now;
			ZeldaLedgeSystem.reset();
		}
		float dt = Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, 0.1f);
		lastFrameNanos = now;
		frameDt = dt;

		if (!isActive()) {
			ZeldaLedgeSystem.reset();
			return;
		}
		if (ZeldaChestCutscene.isActive()) {
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

		if (!ZeldaTargetingSystem.isFocusActive()) {
			// Right stick = rotate camera when not locked onto a target.
			if (inGame && Gamepad.rightStickActive()) {
				float rx = Gamepad.rightX * Math.abs(Gamepad.rightX);
				float ry = Gamepad.rightY * Math.abs(Gamepad.rightY);
				addCameraRotation(rx * STICK_YAW_SPEED * dt, -ry * STICK_PITCH_SPEED * dt);
			}
			// Gently recenter the camera behind the player while moving forward or hanging on a ledge.
			if (followStrength > 0.0f && now > manualUntilNanos) {
				float targetYaw = ZeldaLedgeSystem.isActive() ? ZeldaLedgeSystem.getHangYawDeg() : player.getYRot();
				float diff = Mth.wrapDegrees(targetYaw - camYaw);
				float amount = 1.0f - (float) Math.exp(-0.9f * followStrength * dt);
				camYaw += diff * amount;
			}
		}
		camYaw = Mth.wrapDegrees(camYaw);
	}
}
