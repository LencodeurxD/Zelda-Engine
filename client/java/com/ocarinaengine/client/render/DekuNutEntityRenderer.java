package com.ocarinaengine.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ocarinaengine.entity.DekuNutEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public final class DekuNutEntityRenderer extends EntityRenderer<DekuNutEntity> {
	public DekuNutEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(DekuNutEntity entity, float entityYaw, float partialTick,
					   PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		float age = entity.tickCount + partialTick;
		poseStack.translate(0.0f, 0.16f, 0.0f);
		poseStack.mulPose(Axis.YP.rotationDegrees(age * 24.0f));
		poseStack.mulPose(Axis.XP.rotationDegrees(age * 18.0f));
		poseStack.scale(0.55f, 0.55f, 0.55f);
		ZeldaItemRenderer3D.renderStandingSpin("deku_nut", false, age / 20.0f, poseStack, buffer, packedLight);
		poseStack.popPose();
		super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(DekuNutEntity entity) {
		return com.ocarinaengine.OcarinaEngine.id("textures/item3d/deku_nut.png");
	}
}