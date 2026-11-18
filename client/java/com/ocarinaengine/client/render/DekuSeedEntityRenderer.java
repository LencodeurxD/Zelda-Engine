package com.ocarinaengine.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ocarinaengine.entity.DekuSeedEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public final class DekuSeedEntityRenderer extends EntityRenderer<DekuSeedEntity> {
	public DekuSeedEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(DekuSeedEntity entity, float entityYaw, float partialTick,
					   PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		poseStack.translate(0.0f, 0.12f, 0.0f);
		poseStack.scale(0.32f, 0.32f, 0.32f);
		ZeldaItemRenderer3D.renderStandingSpin("deku_nut", false, (entity.tickCount + partialTick) / 10.0f, poseStack, buffer, packedLight);
		poseStack.popPose();
		super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(DekuSeedEntity entity) {
		return com.ocarinaengine.OcarinaEngine.id("textures/item3d/deku_nut.png");
	}
}