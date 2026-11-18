package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Minecraft's camera with the Zelda camera, applies impact zoom/shake,
 * chest cutscenes, and over-the-shoulder zoom when aiming the Fairy Slingshot.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	public abstract Vec3 getPosition();

	@Shadow
	public abstract float getYRot();

	@Shadow
	public abstract float getXRot();

	@Inject(method = "setup", at = @At("HEAD"))
	private void ocarina$beforeSetup(CallbackInfo ci) {
		EngineState.frameUpdate();
	}

	@ModifyArg(
		method = "setup",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0),
		index = 0
	)
	private float ocarina$yaw(float original) {
		return EngineState.isActive() ? (EngineState.camYaw + ZeldaCombatSystem.getImpactCameraShakeYaw()) : original;
	}

	@ModifyArg(
		method = "setup",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0),
		index = 1
	)
	private float ocarina$pitch(float original) {
		return EngineState.isActive() ? (EngineState.camPitch + ZeldaCombatSystem.getImpactCameraShakePitch()) : original;
	}

	@Inject(method = "setup", at = @At("TAIL"))
	private void ocarina$afterSetup(BlockGetter level, Entity entity, boolean detached,
									boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		if (!EngineState.isActive()) {
			return;
		}
		if (ZeldaChestCutscene.isActive()) {
			ZeldaChestCutscene.applyCutsceneCamera(level, entity, (Vec3 pos, float yaw, float pitch) -> {
				this.setRotation(yaw, pitch);
				this.setPosition(pos.x, pos.y, pos.z);
			});
			return;
		}

		if (EngineState.slingshotAimBlend > 0.002f && level != null && entity != null) {
			float yawRad = this.getYRot() * Mth.DEG_TO_RAD;
			float pitchRad = this.getXRot() * Mth.DEG_TO_RAD;
			double cosPitch = Mth.cos(pitchRad);
			Vec3 forward = new Vec3(-Mth.sin(yawRad) * cosPitch, -Mth.sin(pitchRad), Mth.cos(yawRad) * cosPitch);
			Vec3 right = new Vec3(-Mth.cos(yawRad), 0.0, -Mth.sin(yawRad));
			Vec3 eyePos = entity.getEyePosition(partialTick);
			Vec3 idealAimPos = eyePos.subtract(forward.scale(2.20)).add(right.scale(0.64)).add(0.0, 0.16, 0.0);

			HitResult wallHit = level.clip(new ClipContext(
					eyePos, idealAimPos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			Vec3 safeAimPos = wallHit.getType() != HitResult.Type.MISS
					? wallHit.getLocation().lerp(eyePos, 0.10)
					: idealAimPos;

			float b = EngineState.slingshotAimBlend;
			float smooth = b * b * (3.0f - 2.0f * b);
			Vec3 blended = this.getPosition().lerp(safeAimPos, smooth);
			this.setPosition(blended.x, blended.y, blended.z);
		}

		float zoomPush = ZeldaCombatSystem.getImpactCameraZoomPush();
		if (zoomPush > 0.001f) {
			float yawRad = this.getYRot() * Mth.DEG_TO_RAD;
			float pitchRad = this.getXRot() * Mth.DEG_TO_RAD;
			double cosPitch = Mth.cos(pitchRad);
			double fx = -Mth.sin(yawRad) * cosPitch;
			double fy = -Mth.sin(pitchRad);
			double fz = Mth.cos(yawRad) * cosPitch;
			Vec3 pos = this.getPosition();
			this.setPosition(pos.x + fx * zoomPush, pos.y + fy * zoomPush, pos.z + fz * zoomPush);
		}
		EngineState.aimCameraPos = this.getPosition();
	}
}