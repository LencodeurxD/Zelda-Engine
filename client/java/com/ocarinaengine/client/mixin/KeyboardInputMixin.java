package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.items.ZeldaQuickItems;
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
 * Zelda movement, Z-Target combat locomotion, Ledge Hang/Shimmy/Climb, and Slingshot archer aim strafing.
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
		boolean jumpHeld = this.jumping || (inGame && Gamepad.actionJump);
		if (ZeldaChestCutscene.isJumpSuppressed(jumpHeld)) {
			this.jumping = false;
		} else if (inGame && Gamepad.actionJump) {
			this.jumping = true;
		}
		boolean jumpEdge = this.jumping && !this.ocarina$wasJumpPressed;
		this.ocarina$wasJumpPressed = this.jumping;

		if (player.isPassenger()) {
			EngineState.followStrength = 0.0f;
			ZeldaLedgeSystem.reset();
			ZeldaCombatSystem.recordCombatDirectionalInput(0.0f);
			ZeldaAnimationEngine.recordInput(0.0f, 0.0f, player.getYRot());
			return;
		}

		boolean shieldSuppressSneak = ZeldaEquipment.hasEquippedShield()
				&& (ZeldaCombatSystem.isGuarding() || (inGame && ZeldaKeys.isShieldHeld()));
		if (shieldSuppressSneak) {
			this.shiftKeyDown = false;
			float rawFwd = (this.up == this.down) ? 0.0f : (this.up ? 1.0f : -1.0f);
			float rawLeft = (this.left == this.right) ? 0.0f : (this.left ? 1.0f : -1.0f);
			if (rawFwd != 0.0f && rawLeft != 0.0f) {
				rawFwd *= 0.70710677f;
				rawLeft *= 0.70710677f;
			}
			this.forwardImpulse = rawFwd;
			this.leftImpulse = rawLeft;
		}

		float sx;
		float sy;
		boolean usingStick = inGame && Gamepad.leftStickActive();
		if (usingStick) {
			sx = Gamepad.leftX;
			sy = Gamepad.leftY;
		} else {
			sx = -this.leftImpulse;
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

		if (ZeldaCombatSystem.isGuarding()) {
			this.shiftKeyDown = false;
		}

		float shieldWalkScale = ZeldaCombatSystem.isShieldCrouchTurtling()
				? 0.0f
				: (ZeldaCombatSystem.isGuarding() ? 0.68f : 1.0f);
		float magnitude = Math.min(1.0f, (float) Math.sqrt(sx * sx + sy * sy));
		if (!usingStick && inGame && magnitude > 0.01f
				&& GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS) {
			magnitude *= 0.36f;
		}
		magnitude *= shieldWalkScale;

		// Fairy Slingshot Aiming: lock Link's facing to camera crosshair while allowing full 4-axis strafe & backpedal (matches Z-Target)
		if (ZeldaQuickItems.isAimingSlingshot()) {
			EngineState.followStrength = 0.0f;
			float aimYaw = EngineState.camYaw;
			player.setYRot(aimYaw);
			player.setYHeadRot(aimYaw);
			player.yBodyRot = aimYaw;
			if (!player.isSwimming() && !player.isFallFlying()) {
				player.setXRot(0.0f);
			}
			float aimFwd = sy;
			float aimLeft = -sx;
			if (!usingStick) {
				boolean kUp = this.up || mc.options.keyUp.isDown();
				boolean kDown = this.down || mc.options.keyDown.isDown();
				boolean kLeft = this.left || mc.options.keyLeft.isDown();
				boolean kRight = this.right || mc.options.keyRight.isDown();
				aimFwd = (kUp == kDown) ? 0.0f : (kUp ? 1.0f : -1.0f);
				aimLeft = (kLeft == kRight) ? 0.0f : (kLeft ? 1.0f : -1.0f);
				if (aimFwd != 0.0f && aimLeft != 0.0f) {
					aimFwd *= 0.70710677f;
					aimLeft *= 0.70710677f;
				}
			}
			float aimScale = this.shiftKeyDown ? 0.55f : 1.0f;
			this.forwardImpulse = aimFwd * aimScale;
			this.leftImpulse = aimLeft * aimScale;
			ZeldaCombatSystem.recordCombatDirectionalInput(aimFwd);
			float aimMag = Math.min(1.0f, (float) Math.hypot(aimLeft, aimFwd));
			float moveWorldYaw = (aimMag > 0.02f)
					? (aimYaw + (float) Math.toDegrees(Math.atan2(-aimLeft, aimFwd)))
					: aimYaw;
			ZeldaAnimationEngine.recordInput(aimMag * aimScale, 0.0f, moveWorldYaw);
			return;
		}

		float rawMag = (float) Math.sqrt(sx * sx + sy * sy);
		boolean advancingNow = inGame && ((!ZeldaTargetingSystem.isFocusActive() && rawMag > 0.30f && sy >= -0.15f) || sy > 0.25f);
		float advanceYaw = player.getYRot();
		if (sx * sx + sy * sy > 0.02f) {
			float rawStickAngle = (float) Math.toDegrees(Math.atan2(sx, sy));
			float baseAdvanceYaw = ZeldaTargetingSystem.isFocusActive()
					? ZeldaTargetingSystem.getFocusYawDeg(player, 1.0f)
					: EngineState.camYaw;
			advanceYaw = Mth.wrapDegrees(baseAdvanceYaw + rawStickAngle);
		}
		ZeldaCombatSystem.recordAirborneAdvanceInput(player, advancingNow, advanceYaw);

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
			float spinScale = (ZeldaCombatSystem.isChargingSpin() ? 0.36f : 1.0f) * shieldWalkScale;
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
		float stickAngle = (float) Math.toDegrees(Math.atan2(sx, sy));
		float targetYaw = EngineState.camYaw + stickAngle;
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
		float remaining = Math.abs(Mth.wrapDegrees(targetYaw - newYaw));
		float turnFactor = Mth.clamp(1.0f - remaining / 140.0f, 0.35f, 1.0f);
		this.forwardImpulse = magnitude * turnFactor;
		this.leftImpulse = 0.0f;
		ZeldaAnimationEngine.recordInput(magnitude, turnStep, targetYaw);
		float forwardPart = Math.max(0.0f, sy / Math.max(magnitude, 0.01f)) * magnitude;
		EngineState.followStrength = forwardPart;
	}
}