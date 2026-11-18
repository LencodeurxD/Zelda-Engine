package com.ocarinaengine.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.equipment.EquipSlot;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.equipment.ZeldaItem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders the floating 3D revealed item (using custom 3D models via {@link ZeldaItemRenderer3D} and {@link ZeldaShieldRenderer})
 * and additive golden chest rays during Zelda chest cutscenes.
 */
public final class ZeldaLightEffects {
	private ZeldaLightEffects() {}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(ZeldaLightEffects::renderFloatingReward);
		WorldRenderEvents.AFTER_TRANSLUCENT.register(ZeldaLightEffects::renderLightEffects);
	}

	private static void renderFloatingReward(WorldRenderContext context) {
		if (!OcarinaGameTypeClient.isZelda() || !ZeldaChestCutscene.isActive()) {
			return;
		}
		ItemStack reward = ZeldaChestCutscene.rewardStack();
		float itemReveal = ZeldaChestCutscene.itemRevealAlpha();
		if (reward.isEmpty() || itemReveal <= 0.01f) {
			return;
		}

		PoseStack pose = context.matrixStack();
		if (pose == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}

		Vec3 camPos = context.camera().getPosition();
		Vec3 itemWorldPos = ZeldaChestCutscene.itemWorldPosition();
		float time = ZeldaChestCutscene.elapsedSeconds();

		pose.pushPose();
		pose.translate(itemWorldPos.x - camPos.x, itemWorldPos.y - camPos.y, itemWorldPos.z - camPos.z);
		pose.mulPose(Axis.YP.rotationDegrees(time * 165.0f));
		float s = 0.85f * Mth.clamp(itemReveal, 0.0f, 1.0f);
		pose.scale(s, s, s);

		MultiBufferSource.BufferSource immediate = mc.renderBuffers().bufferSource();
		ZeldaItem rewardEquip = ZeldaEquipment.previewEquipment(reward);
		if (rewardEquip != null && rewardEquip.slot() == EquipSlot.SHIELD) {
			ZeldaShieldRenderer.renderStandingSpin(rewardEquip.id(), pose, immediate, LightTexture.FULL_BRIGHT);
		} else if (rewardEquip != null && ZeldaItemRenderer3D.supports3D(rewardEquip.id())) {
			ZeldaItemRenderer3D.renderStandingSpin(rewardEquip.id(), false, time, pose, immediate, LightTexture.FULL_BRIGHT);
		} else {
			QuickItem quick = ZeldaQuickItems.mapStack(reward);
			if (quick != null && ZeldaItemRenderer3D.supports3D(quick.id())) {
				ZeldaItemRenderer3D.renderStandingSpin(quick.id(), false, time, pose, immediate, LightTexture.FULL_BRIGHT);
			} else {
				mc.getItemRenderer().renderStatic(
						reward,
						ItemDisplayContext.FIXED,
						LightTexture.FULL_BRIGHT,
						OverlayTexture.NO_OVERLAY,
						pose,
						immediate,
						mc.level,
						0
				);
			}
		}
		immediate.endBatch();
		pose.popPose();
	}

	private static void renderLightEffects(WorldRenderContext context) {
		if (!OcarinaGameTypeClient.isZelda() || !ZeldaChestCutscene.isActive()) {
			return;
		}
		PoseStack pose = context.matrixStack();
		if (pose == null) {
			return;
		}
		Vec3 camPos = context.camera().getPosition();

		float chestBeamStrength = ZeldaChestCutscene.chestBeamIntensity();
		if (chestBeamStrength > 0.01f) {
			Vec3 chestCenter = ZeldaChestCutscene.chestCenter();
			renderChestLightBeams(pose, camPos, chestCenter, chestBeamStrength, ZeldaChestCutscene.elapsedSeconds());
		}

		ItemStack reward = ZeldaChestCutscene.rewardStack();
		float itemReveal = ZeldaChestCutscene.itemRevealAlpha();
		if (!reward.isEmpty() && itemReveal > 0.01f) {
			Vec3 itemWorldPos = ZeldaChestCutscene.itemWorldPosition();
			Vec3 camLook = new Vec3(context.camera().getLookVector());
			Vec3 auraPos = itemWorldPos.add(camLook.scale(0.24));
			renderItemAura(pose, camPos, auraPos, itemReveal, ZeldaChestCutscene.elapsedSeconds(), ZeldaChestCutscene.isBigChest());
		}
	}

	private static void renderChestLightBeams(PoseStack pose, Vec3 camPos, Vec3 chestCenter, float strength, float time) {
		pose.pushPose();
		pose.translate(chestCenter.x - camPos.x, chestCenter.y - camPos.y + 0.35, chestCenter.z - camPos.z);

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();

		int rays = 7;
		float height = 3.0f * (0.65f + 0.35f * strength);
		float baseR = 0.28f;
		float topR = 1.00f;

		for (int i = 0; i < rays; i++) {
			float angle = (float) (i * (Math.PI * 2.0 / rays)) + time * 0.55f;
			float nextAngle = angle + 0.42f;
			float pulse = 0.75f + 0.25f * Mth.sin(time * 4.5f + i * 1.1f);
			int alphaBot = Math.round(135 * strength * pulse);

			float x0 = Mth.cos(angle) * baseR;
			float z0 = Mth.sin(angle) * baseR;
			float x1 = Mth.cos(nextAngle) * baseR;
			float z1 = Mth.sin(nextAngle) * baseR;

			float tx0 = Mth.cos(angle - 0.12f) * topR;
			float tz0 = Mth.sin(angle - 0.12f) * topR;
			float tx1 = Mth.cos(nextAngle + 0.12f) * topR;
			float tz1 = Mth.sin(nextAngle + 0.12f) * topR;

			buf.addVertex(mat, x0, 0.0f, z0).setColor(255, 225, 115, alphaBot);
			buf.addVertex(mat, x1, 0.0f, z1).setColor(255, 225, 115, alphaBot);
			buf.addVertex(mat, tx1, height, tz1).setColor(255, 245, 180, 0);

			buf.addVertex(mat, x0, 0.0f, z0).setColor(255, 225, 115, alphaBot);
			buf.addVertex(mat, tx1, height, tz1).setColor(255, 245, 180, 0);
			buf.addVertex(mat, tx0, height, tz0).setColor(255, 245, 180, 0);
		}

		int coreAlpha = Math.round(150 * strength);
		for (int i = 0; i < 4; i++) {
			float a0 = (float) (i * Math.PI * 0.5) - time * 0.8f;
			float a1 = a0 + (float) (Math.PI * 0.5);
			buf.addVertex(mat, Mth.cos(a0) * 0.2f, 0.0f, Mth.sin(a0) * 0.2f).setColor(255, 250, 210, coreAlpha);
			buf.addVertex(mat, Mth.cos(a1) * 0.2f, 0.0f, Mth.sin(a1) * 0.2f).setColor(255, 250, 210, coreAlpha);
			buf.addVertex(mat, 0.0f, height * 0.72f, 0.0f).setColor(255, 240, 170, 0);
		}

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		pose.popPose();
	}

	private static void renderItemAura(PoseStack pose, Vec3 camPos, Vec3 worldPos, float alpha, float time, boolean bigChest) {
		Minecraft mc = Minecraft.getInstance();
		pose.pushPose();
		pose.translate(worldPos.x - camPos.x, worldPos.y - camPos.y, worldPos.z - camPos.z);
		pose.mulPose(mc.gameRenderer.getMainCamera().rotation());

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);

		BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f mat = pose.last().pose();

		float pulse = 0.88f + 0.12f * Mth.sin(time * 6.0f);
		float radius = (bigChest ? 0.82f : 0.52f) * pulse * alpha;
		int centerAlpha = Math.round((bigChest ? 115 : 85) * alpha);

		int segments = 16;
		for (int i = 0; i < segments; i++) {
			float a0 = (float) (i * Math.PI * 2.0 / segments) + time * 0.9f;
			float a1 = (float) ((i + 1) * Math.PI * 2.0 / segments) + time * 0.9f;
			float r0 = (i % 2 == 0) ? radius : radius * 0.60f;
			float r1 = ((i + 1) % 2 == 0) ? radius : radius * 0.60f;

			buf.addVertex(mat, 0.0f, 0.0f, 0.0f).setColor(255, 232, 130, centerAlpha);
			buf.addVertex(mat, Mth.cos(a0) * r0, Mth.sin(a0) * r0, 0.0f).setColor(255, 190, 50, 0);
			buf.addVertex(mat, Mth.cos(a1) * r1, Mth.sin(a1) * r1, 0.0f).setColor(255, 190, 50, 0);
		}

		MeshData mesh = buf.build();
		if (mesh != null) {
			BufferUploader.drawWithShader(mesh);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		pose.popPose();
	}
}