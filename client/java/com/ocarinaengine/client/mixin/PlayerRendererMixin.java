package com.ocarinaengine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ocarinaengine.client.animation.ZeldaPlayerAnimator;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies whole-body ground-pivot transforms (jog forward lean, curve banking,
 * stride bounce, and cautious crouch/cliff tilt) after vanilla setupRotations.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
	@Inject(
		method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
		at = @At("TAIL")
	)
	private void ocarina$applyWholeBodyTransforms(AbstractClientPlayer entity, PoseStack poseStack,
												  float ageInTicks, float rotationYaw,
												  float partialTicks, float scale, CallbackInfo ci) {
		ZeldaPlayerAnimator.applyBodyTransform(entity, poseStack, partialTicks);
	}
}
