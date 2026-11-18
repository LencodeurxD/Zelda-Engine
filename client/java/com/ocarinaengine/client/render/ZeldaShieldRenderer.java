package com.ocarinaengine.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Custom 3D ModelPart & texture renderer for the three Ocarina of Time shields
 * (Deku Shield, Hylian Shield, Mirror Shield) so each shield renders at compact
 * OoT proportions strapped flush onto Link's left forearm or across his back,
 * instead of vanilla's oversized floating plank shield.
 */
public final class ZeldaShieldRenderer {
	private static final ResourceLocation TEX_DEKU =
			ResourceLocation.fromNamespaceAndPath("ocarina_engine", "textures/shield/deku_shield.png");
	private static final ResourceLocation TEX_HYLIAN =
			ResourceLocation.fromNamespaceAndPath("ocarina_engine", "textures/shield/hylian_shield.png");
	private static final ResourceLocation TEX_MIRROR =
			ResourceLocation.fromNamespaceAndPath("ocarina_engine", "textures/shield/mirror_shield.png");

	private static ModelPart dekuModel;
	private static ModelPart hylianModel;
	private static ModelPart mirrorModel;

	private ZeldaShieldRenderer() {}

	public static ResourceLocation textureFor(String shieldId) {
		if (shieldId == null) {
			return TEX_HYLIAN;
		}
		return switch (shieldId) {
			case "deku_shield" -> TEX_DEKU;
			case "mirror_shield" -> TEX_MIRROR;
			default -> TEX_HYLIAN;
		};
	}

	private static float scaleFor(String shieldId) {
		if (shieldId == null) {
			return 0.84f;
		}
		return switch (shieldId) {
			case "deku_shield" -> 0.78f;
			case "mirror_shield" -> 0.84f;
			default -> 0.86f;
		};
	}

	private static ModelPart modelFor(String shieldId) {
		if ("deku_shield".equals(shieldId)) {
			if (dekuModel == null) {
				dekuModel = buildDekuModel();
			}
			return dekuModel;
		}
		if ("mirror_shield".equals(shieldId)) {
			if (mirrorModel == null) {
				mirrorModel = buildMirrorModel();
			}
			return mirrorModel;
		}
		if (hylianModel == null) {
			hylianModel = buildHylianModel();
		}
		return hylianModel;
	}

	private static ModelPart buildDekuModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("plate",
				CubeListBuilder.create()
						.texOffs(16, 0)
						.addBox(-5.0F, -6.0F, 0.0F, 10.0F, 12.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crest",
				CubeListBuilder.create()
						.texOffs(16, 15)
						.addBox(-4.0F, -5.0F, 0.65F, 8.0F, 10.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crown",
				CubeListBuilder.create()
						.texOffs(40, 0)
						.addBox(-3.0F, -6.75F, 0.15F, 6.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("tip",
				CubeListBuilder.create()
						.texOffs(40, 7)
						.addBox(-3.0F, 3.65F, 0.15F, 6.0F, 3.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("boss",
				CubeListBuilder.create()
						.texOffs(40, 14)
						.addBox(-2.0F, -2.5F, 1.05F, 4.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildHylianModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("plate",
				CubeListBuilder.create()
						.texOffs(16, 0)
						.addBox(-5.0F, -6.0F, 0.0F, 10.0F, 12.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crest",
				CubeListBuilder.create()
						.texOffs(16, 15)
						.addBox(-4.0F, -5.0F, 0.70F, 8.0F, 10.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crown",
				CubeListBuilder.create()
						.texOffs(40, 0)
						.addBox(-3.0F, -7.1F, 0.20F, 6.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("tip",
				CubeListBuilder.create()
						.texOffs(40, 7)
						.addBox(-3.0F, 4.1F, 0.25F, 6.0F, 3.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("boss",
				CubeListBuilder.create()
						.texOffs(40, 14)
						.addBox(-2.0F, -1.5F, 1.05F, 4.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildMirrorModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("plate",
				CubeListBuilder.create()
						.texOffs(16, 0)
						.addBox(-5.0F, -6.0F, 0.0F, 10.0F, 12.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crest",
				CubeListBuilder.create()
						.texOffs(16, 15)
						.addBox(-4.0F, -5.0F, 0.75F, 8.0F, 10.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crown",
				CubeListBuilder.create()
						.texOffs(40, 0)
						.addBox(-3.0F, -6.8F, 0.25F, 6.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("tip",
				CubeListBuilder.create()
						.texOffs(40, 7)
						.addBox(-3.0F, 3.7F, 0.25F, 6.0F, 3.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("boss",
				CubeListBuilder.create()
						.texOffs(40, 14)
						.addBox(-2.0F, -0.5F, 1.05F, 4.0F, 4.0F, 1.0F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	/**
	 * Renders the shield strapped flush onto the outer sleeve of Link's articulated left forearm
	 * right after {@code HumanoidModel.translateToHand(HumanoidArm.LEFT, poseStack)}.
	 */
	public static void renderInLeftHand(String shieldId, boolean slim, float raiseBlend,
										  float impactProgress, PoseStack poseStack,
										  MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		float armOuterX = slim ? 0.175f : 0.210f;
		float recoil = (impactProgress >= 0.0f) ? Mth.sin(impactProgress * Mth.PI) * 0.06f : 0.0f;
		poseStack.translate(
				armOuterX + recoil * 0.35f,
				Mth.lerp(raiseBlend, 0.43f, 0.40f),
				Mth.lerp(raiseBlend, 0.0f, -0.03f)
		);
		// Point the front face (+Z) straight outward (+X) away from the forearm sleeve.
		poseStack.mulPose(Axis.YP.rotationDegrees(90.0f));
		poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(raiseBlend, -6.0f, -24.0f)));
		poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(raiseBlend, 0.0f, 10.0f)));
		float s = scaleFor(shieldId);
		poseStack.scale(s, s, s);

		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(textureFor(shieldId)));
		modelFor(shieldId).render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}

	/**
	 * Renders the shield slung neatly across Link's back right after
	 * {@code hm.body.translateAndRotate(poseStack)}.
	 */
	public static void renderOnBack(String shieldId, PoseStack poseStack,
									MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		poseStack.translate(0.02f, 0.36f, 0.155f);
		poseStack.mulPose(Axis.ZP.rotationDegrees(-12.0f));
		float s = scaleFor(shieldId) * 0.96f;
		poseStack.scale(s, s, s);

		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(textureFor(shieldId)));
		modelFor(shieldId).render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}

	/**
	 * Renders the rotating shield floating above a chest during the Zelda chest reveal cutscene.
	 */
	public static void renderStandingSpin(String shieldId, PoseStack poseStack,
										  MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		float s = scaleFor(shieldId) * 1.15f;
		poseStack.scale(s, s, s);
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(textureFor(shieldId)));
		modelFor(shieldId).render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}
}
