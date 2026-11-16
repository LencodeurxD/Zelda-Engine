package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.animation.ZeldaPlayerAnimator;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies procedural Zelda animations (chest cutscenes, context-sensitive idle,
 * and the Equipment screen character preview) to the player's humanoid model.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> {
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void ocarina$applyPlayerAnimation(T entity, float limbSwing, float limbSwingAmount,
											  float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		if (ZeldaPlayerAnimator.shouldAnimate(entity)) {
			ZeldaPlayerAnimator.apply((HumanoidModel<?>) (Object) this, entity, limbSwingAmount);
		}
	}
}
