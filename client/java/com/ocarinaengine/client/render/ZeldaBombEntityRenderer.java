package com.ocarinaengine.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ocarinaengine.entity.ZeldaBombEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public final class ZeldaBombEntityRenderer extends EntityRenderer<ZeldaBombEntity> {
	public ZeldaBombEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.18f;
	}

	@Override
	public void render(ZeldaBombEntity entity, float entityYaw, float partialTick,
					   PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		ZeldaItemRenderer3D.renderBombEntity(
				entity.getDisplayFuse(),
				partialTick,
				entity.getYRot(),
				entity.getRollDeg(partialTick),
				poseStack,
				buffer,
				packedLight
		);
		super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(ZeldaBombEntity entity) {
		return com.ocarinaengine.OcarinaEngine.id("textures/item3d/bomb.png");
	}
}