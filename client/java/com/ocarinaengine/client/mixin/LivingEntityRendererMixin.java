package com.ocarinaengine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an Ocarina of Time Remake style rich crimson-red damage flash & blink on struck enemies
 * on top of Minecraft's standard hurt overlay.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>>
		extends EntityRenderer<T> {
	@Shadow
	protected M model;

	protected LivingEntityRendererMixin(EntityRendererProvider.Context context) {
		super(context);
	}

	@Inject(
		method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
			shift = At.Shift.AFTER
		),
		require = 0
	)
	private void ocarina$redDamageBlink(T entity, float entityYaw, float partialTicks,
									   PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		float blink = ZeldaCombatSystem.getEnemyRedBlinkIntensity(entity.getId());
		if (blink <= 0.01f) {
			return;
		}
		int alpha = Math.round(212.0f * blink);
		int crimsonArgb = (alpha << 24) | (255 << 16) | (34 << 8) | 40;
		int overlay = OverlayTexture.pack(OverlayTexture.u(0.30f), OverlayTexture.v(true));

		VertexConsumer flashBuf = buffer.getBuffer(RenderType.entityTranslucent(this.getTextureLocation(entity)));
		poseStack.pushPose();
		poseStack.scale(1.0025f, 1.0025f, 1.0025f);
		this.model.renderToBuffer(
				poseStack,
				flashBuf,
				LightTexture.FULL_BRIGHT,
				overlay,
				crimsonArgb
		);
		poseStack.popPose();
	}
}
