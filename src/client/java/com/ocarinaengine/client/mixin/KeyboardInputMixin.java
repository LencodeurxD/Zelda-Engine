package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zelda movement, Z-Target combat locomotion, and Ledge Hang/Shimmy/Climb input routing:
 * - Free exploration: stick sets direction relative to the camera; Link smoothly turns and walks/jogs.
 * - Z-Targeting: Link stays squared up facing the locked target, strafes/backpedals around it,
 *   and performs crisp Side-Hops, Side-Rolls & Backflips.
 * - Ledge Hang/Climb: raw stick/WASD left/right shimmies along the wall, Up climbs onto the block,
 *   and Down drops cleanly off the ledge.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
	@Unique
	private boolean ocarina$wasJumpPressed = false;

	@Inject(method = "tick", at = @At("TAIL"))
	private void ocarina$zeldaMovement(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || !EngineState.isActive()) {
			this.ocarina$wasJumpPressed = false;
			ZeldaLedgeSystem.reset();
			return;
		}
		if (ZeldaChestCutscene.isActive()) {
			this.forwardImpulse = 0.0f;
			this.leftImpulse = 0.0f;
			this.jumping = false;
			this.shiftKeyDown = false;
			this.ocarina$wasJumpPressed = false;
			EngineState.followStrength = 0.0f;
			ZeldaLedgeSystem.reset();
			ZeldaCombatSystem.recordCombatDirectionalInput(0.0f);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, player.getYRot());
			ZeldaChestCutscene.applyPlayerPose(player);
			return;
		}
		boolean inGame = mc.screen == null && mc.isWindowActive();
		Gamepad.poll();

		// Evaluate jump suppression unconditionally every tick so it always clears when Jump is released.
		boolean jumpHeld = this.jumping || (inGame && Gamepad.actionJump);
		if (ZeldaChestCutscene.isJumpSuppressed(jumpHeld)) {
			this.jumping = false;
		} else if (inGame && Gamepad.actionJump) {
			this.jumping = true;
		}
		boolean jumpEdge = this.jumping && !this.ocarina$wasJumpPressed;
		this.ocarina$wasJumpPressed = this.jumping;

		// When riding a mount (horse, boat...), let Minecraft handle controls.
		if (player.isPassenger()) {
			EngineState.followStrength = 0.0f;
			ZeldaLedgeSystem.reset();
			ZeldaCombatSystem.recordCombatDirectionalInput(0.0f);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, player.getYRot());
			return;
		}

		// 1) Desired input: right (sx) and forward (sy), between -1 and 1.
		float sx;
		float sy;
		boolean usingStick = inGame && Gamepad.leftStickActive();
		if (usingStick) {
			sx = Gamepad.leftX;
			sy = Gamepad.leftY;
		} else {
			sx = -this.leftImpulse; // in Minecraft, "left" is positive
			sy = this.forwardImpulse;
		}

		ZeldaLedgeSystem.recordMovementInput(player, sx, sy, jumpEdge);
		if (ZeldaLedgeSystem.isActive()) {
			float wallYaw = ZeldaLedgeSystem.getHangYawDeg();
			this.forwardImpulse = 0.0f;
			this.leftImpulse = 0.0f;
			this.jumping = false;
			EngineState.followStrength = ZeldaLedgeSystem.isHanging() ? 0.85f : 0.45f;
			ZeldaCombatSystem.recordCombatDirectionalInput(0.0f);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, wallYaw);
			return;
		}

		float magnitude = Math.min(1.0f, (float) Math.sqrt(sx * sx + sy * sy));
		if (!usingStick && inGame && magnitude > 0.01f
				&& GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS) {
			magnitude *= 0.36f;
		}

		// Lock body rotation to sword swing direction during grounded swings, while buffering dodge cancels.
		if (ZeldaCombatSystem.isSwinging() && player.onGround()) {
			if (jumpEdge && ZeldaTargetingSystem.isFocusActive()) {
				ZeldaCombatSystem.tryStartDodge(player, sx, sy);
			}
			float swingYaw = ZeldaCombatSystem.getLockedSwingYawDeg();
			player.setYRot(swingYaw);
			player.setYHeadRot(swingYaw);
			player.yBodyRot = swingYaw;
			this.forwardImpulse = 0.0f;
			this.leftImpulse = 0.0f;
			this.jumping = false;
			ZeldaCombatSystem.recordCombatDirectionalInput(sy);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, swingYaw);
			return;
		}

		// Keep roll & dodge orientation intact even if ZL / Z-Target is released mid-maneuver.
		if (ZeldaCombatSystem.isDodging()) {
			float dodgeYaw = ZeldaCombatSystem.isRolling()
					? ZeldaCombatSystem.getDodgeYawDeg()
					: (ZeldaTargetingSystem.isFocusActive()
							? ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f)
							: ZeldaCombatSystem.getDodgeYawDeg());
			player.setYRot(dodgeYaw);
			player.setYHeadRot(dodgeYaw);
			player.yBodyRot = dodgeYaw;
			if (!player.isSwimming() && !player.isFallFlying()) {
				player.setXRot(0.0f);
			}
			this.forwardImpulse = 0.0f;
			this.leftImpulse = 0.0f;
			this.jumping = false;
			ZeldaCombatSystem.recordCombatDirectionalInput(sy);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, dodgeYaw);
			return;
		}

		// Z-Targeting / ZL focus lock: keep facing fixed (target or locked direction), allow strafing/backpedaling and dodges.
		if (ZeldaTargetingSystem.isFocusActive()) {
			EngineState.followStrength = 0.0f;
			boolean freeFocus = ZeldaTargetingSystem.isFreeFocusing();
			float lockYaw = ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f);
			float current = player.getYRot();
			float diff = Mth.wrapDegrees(lockYaw - current);
			float turnStep = freeFocus ? diff : Mth.clamp(diff * 0.55f, -38.0f, 38.0f);
			float newYaw = freeFocus ? lockYaw : (current + turnStep);
			player.setYRot(newYaw);
			player.setYHeadRot(newYaw);
			player.yBodyRot = newYaw;
			if (!player.isSwimming() && !player.isFallFlying()) {
				player.setXRot(0.0f);
			}

			ZeldaCombatSystem.recordCombatDirectionalInput(sy);

			// Side-Hop or Backflip cancels plain vertical jump when strafing/backing up and pressing Jump.
			if (jumpEdge && ZeldaCombatSystem.tryStartDodge(player, sx, sy)) {
				this.jumping = false;
				this.forwardImpulse = 0.0f;
				this.leftImpulse = 0.0f;
				ZeldaAnimationEngine.recordInput(0.0f, 0.0f, lockYaw);
				return;
			}

			if (magnitude < 0.01f) {
				this.forwardImpulse = 0.0f;
				this.leftImpulse = 0.0f;
				ZeldaAnimationEngine.recordInput(0.0f, freeFocus ? 0.0f : turnStep, lockYaw);
				return;
			}

			float stickAngle = (float) Math.toDegrees(Math.atan2(sx, sy));
			float moveWorldYaw = lockYaw + stickAngle;
			float spinScale = ZeldaCombatSystem.isChargingSpin() ? 0.36f : 1.0f;
			this.forwardImpulse = sy * spinScale;
			this.leftImpulse = -sx * spinScale;
			ZeldaAnimationEngine.recordInput(magnitude * spinScale, 0.0f, moveWorldYaw);
			return;
		}

		ZeldaCombatSystem.recordCombatDirectionalInput(0.0f);

		if (magnitude < 0.01f) {
			this.forwardImpulse = 0.0f;
			this.leftImpulse = 0.0f;
			EngineState.followStrength = 0.0f;
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, player.getYRot());
			return;
		}
		if (ZeldaCombatSystem.isChargingSpin()) {
			magnitude *= 0.36f;
		}

		// 2) Desired world direction = camera yaw + stick angle.
		float stickAngle = (float) Math.toDegrees(Math.atan2(sx, sy));
		float targetYaw = EngineState.camYaw + stickAngle;

		// 3) Smoothly ease player rotation toward targetYaw (fluid turns instead of abrupt snapping).
		float current = player.getYRot();
		float diff = Mth.wrapDegrees(targetYaw - current);
		float maxTurn = Mth.lerp(magnitude, 22.0f, 34.0f);
		float turnStep = Mth.clamp(diff * 0.44f, -maxTurn, maxTurn);
		float newYaw = current + turnStep;
		player.setYRot(newYaw);
		player.setYHeadRot(newYaw);
		float bodyDiff = Mth.wrapDegrees(newYaw - player.yBodyRot);
		player.yBodyRot += bodyDiff * 0.65f;
		if (!player.isSwimming() && !player.isFallFlying()) {
			player.setXRot(0.0f);
		}

		// 4) Move straight forward with smooth cornering momentum.
		float remaining = Math.abs(Mth.wrapDegrees(targetYaw - newYaw));
		float turnFactor = Mth.clamp(1.0f - remaining / 140.0f, 0.35f, 1.0f);
		this.forwardImpulse = magnitude * turnFactor;
		this.leftImpulse = 0.0f;
		ZeldaAnimationEngine.recordInput(magnitude, turnStep, targetYaw);

		// Tells the camera to recenter behind the player when moving forward.
		float forwardPart = Math.max(0.0f, sy / Math.max(magnitude, 0.01f)) * magnitude;
		EngineState.followStrength = forwardPart;
	}
}
