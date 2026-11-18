package com.ocarinaengine.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.ocarinaengine.OcarinaEngine;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Procedural 3D item models (64x32 UV-mapped ModelPart) for Link's hand,
 * overhead bomb carry, mouth-held ocarina, chest reveal spin, and world entities.
 */
public final class ZeldaItemRenderer3D {
	private static final ResourceLocation TEX_DEKU_STICK = OcarinaEngine.id("textures/item3d/deku_stick.png");
	private static final ResourceLocation TEX_DEKU_STICK_LIT = OcarinaEngine.id("textures/item3d/deku_stick_lit.png");
	private static final ResourceLocation TEX_DEKU_NUT = OcarinaEngine.id("textures/item3d/deku_nut.png");
	private static final ResourceLocation TEX_SLINGSHOT = OcarinaEngine.id("textures/item3d/fairy_slingshot.png");
	private static final ResourceLocation TEX_BOMB = OcarinaEngine.id("textures/item3d/bomb.png");
	private static final ResourceLocation TEX_OCARINA = OcarinaEngine.id("textures/item3d/fairy_ocarina.png");
	private static final ResourceLocation TEX_KOKIRI_SWORD = OcarinaEngine.id("textures/item3d/kokiri_sword.png");

	private static final ModelPart DEKU_STICK_MODEL = buildDekuStickModel();
	private static final ModelPart DEKU_STICK_LIT_WOOD_MODEL = buildDekuStickLitWoodModel();
	private static final ModelPart DEKU_STICK_FLAME_OUTER = buildFlamePart("flame_outer", -1.6F, -4.0F, -1.6F, 3.2F, 4.2F, 3.2F, 48, 4);
	private static final ModelPart DEKU_STICK_FLAME_CORE = buildFlamePart("flame_core", -0.9F, -5.6F, -0.9F, 1.8F, 5.6F, 1.8F, 48, 8);
	private static final ModelPart DEKU_STICK_FLAME_EMBER = buildFlamePart("flame_ember", -0.55F, -1.1F, -0.55F, 1.1F, 1.1F, 1.1F, 48, 12);

	private static final ModelPart DEKU_NUT_MODEL = buildDekuNutModel();
	private static final ModelPart SLINGSHOT_MODEL = buildSlingshotModel();
	private static final ModelPart BOMB_MODEL = buildBombModel();
	private static final ModelPart OCARINA_MODEL = buildOcarinaModel();
	private static final ModelPart KOKIRI_SWORD_MODEL = buildKokiriSwordModel();

	private ZeldaItemRenderer3D() {}

	public static boolean supports3D(String itemId) {
		return switch (itemId) {
			case "deku_stick", "deku_stick_lit", "deku_nut", "fairy_slingshot", "bomb", "fairy_ocarina", "kokiri_sword", "wooden_sword" -> true;
			default -> false;
		};
	}

	private static ModelPart modelFor(String itemId, boolean lit) {
		return switch (itemId) {
			case "deku_stick" -> lit ? DEKU_STICK_LIT_WOOD_MODEL : DEKU_STICK_MODEL;
			case "deku_stick_lit" -> DEKU_STICK_LIT_WOOD_MODEL;
			case "deku_nut" -> DEKU_NUT_MODEL;
			case "fairy_slingshot" -> SLINGSHOT_MODEL;
			case "bomb" -> BOMB_MODEL;
			case "fairy_ocarina" -> OCARINA_MODEL;
			case "kokiri_sword", "wooden_sword" -> KOKIRI_SWORD_MODEL;
			default -> DEKU_STICK_MODEL;
		};
	}

	private static ResourceLocation textureFor(String itemId, boolean lit) {
		return switch (itemId) {
			case "deku_stick" -> lit ? TEX_DEKU_STICK_LIT : TEX_DEKU_STICK;
			case "deku_stick_lit" -> TEX_DEKU_STICK_LIT;
			case "deku_nut" -> TEX_DEKU_NUT;
			case "fairy_slingshot" -> TEX_SLINGSHOT;
			case "bomb" -> TEX_BOMB;
			case "fairy_ocarina" -> TEX_OCARINA;
			case "kokiri_sword", "wooden_sword" -> TEX_KOKIRI_SWORD;
			default -> TEX_DEKU_STICK;
		};
	}

	private static ModelPart buildFlamePart(String name, float ox, float oy, float oz,
										  float sx, float sy, float sz, int u, int v) {
		MeshDefinition mesh = new MeshDefinition();
		mesh.getRoot().addOrReplaceChild(
				name,
				CubeListBuilder.create().texOffs(u, v).addBox(ox, oy, oz, sx, sy, sz, CubeDeformation.NONE),
				PartPose.ZERO
		);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	/**
	 * Renders the 2x-long Deku Stick or Slingshot in Link's right hand after {@code hm.translateToHand(RIGHT)}.
	 * Because Link's arm/forearm already lean forward (~ -67 deg), +122 deg around X tilts the "-Y" tip
	 * straight FORWARD (-Z) and ~35 deg upward in front of Link (never behind his head!).
	 */
	public static void renderInRightHand(String itemId, boolean lit, float waveProgress,
										 PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		boolean isStick = "deku_stick".equals(itemId) || "deku_stick_lit".equals(itemId);
		boolean isLong = isStick || "kokiri_sword".equals(itemId);
		boolean isLitStick = lit && isStick;

		poseStack.pushPose();
		poseStack.translate(-0.02f, 0.54f, -0.02f);

		float waveReach = 0.0f;
		float waveYaw = 0.0f;
		float waveRoll = 0.0f;
		if (waveProgress > 0.0f) {
			// Anticipation draw (-0.25) -> brisk forward whip -> elastic settle
			if (waveProgress < 0.22f) {
				float k = waveProgress / 0.22f;
				waveReach = -0.35f * Mth.sin(k * Mth.HALF_PI);
				waveYaw = -16.0f * k;
			} else if (waveProgress < 0.52f) {
				float k = (waveProgress - 0.22f) / 0.30f;
				float s = k * k * (3.0f - 2.0f * k);
				waveReach = Mth.lerp(s, -0.35f, 1.0f);
				waveYaw = Mth.lerp(s, -16.0f, 18.0f);
				waveRoll = Mth.sin(k * Mth.PI) * 14.0f;
			} else {
				float k = (waveProgress - 0.52f) / 0.48f;
				float decay = (1.0f - k) * (1.0f - k);
				waveReach = decay;
				waveYaw = 18.0f * decay;
			}
		}

		if (isLong) {
			// Arm is tilted forward -67 deg around X; +120 deg rotates the -Y stick tip to +53 deg (FORWARD & slightly UP!).
			poseStack.mulPose(Axis.XP.rotationDegrees(120.0f + waveReach * 24.0f));
			poseStack.mulPose(Axis.YP.rotationDegrees(waveYaw));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-6.0f + waveRoll));
		} else if ("fairy_slingshot".equals(itemId)) {
			poseStack.mulPose(Axis.XP.rotationDegrees(96.0f));
			poseStack.mulPose(Axis.YP.rotationDegrees(10.0f));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-8.0f));
		} else if ("deku_nut".equals(itemId)) {
			poseStack.translate(0.0f, 0.04f, -0.02f);
			poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
		} else {
			poseStack.mulPose(Axis.XP.rotationDegrees(95.0f));
		}

		float s = "deku_nut".equals(itemId) ? 0.72f : 0.92f;
		poseStack.scale(s, s, s);

		int woodLight = isLitStick ? LightTexture.FULL_BRIGHT : packedLight;
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(textureFor(itemId, lit)));
		modelFor(itemId, lit).render(poseStack, vc, woodLight, OverlayTexture.NO_OVERLAY);

		if (isLitStick) {
			renderAnimatedFlame(poseStack, buffer, Math.max(0.0f, waveReach), true);
		}
		poseStack.popPose();
	}

	/** Renders a Bomb cradled cleanly between Link's raised hands (right after {@code hm.body.translateAndRotate}). */
	public static void renderBombOverhead(float liftBlend, float throwWindup,
										  PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		// BOMB_MODEL bottom is at +6px = +0.375 blocks (+0.2325 at 0.62 scale); liftY=-0.77 puts bottom at -0.5375 (flush above -0.53125 hat, cradled by raised palms)
		float yU = Mth.clamp(liftBlend / 0.65f, 0.0f, 1.0f);
		float zU = Mth.clamp((liftBlend - 0.60f) / 0.40f, 0.0f, 1.0f);
		float yT = yU * yU * (3.0f - 2.0f * yU);
		float zT = zU * zU * (3.0f - 2.0f * zU);
		float liftY = Mth.lerp(yT, 0.02f, -0.77f);
		float liftZ = Mth.lerp(zT, -0.52f, -0.07f);
		// During initial 0..0.28 throw wind-up, pull bomb slightly UP & back above the crown (never down into the skull)
		poseStack.translate(0.0f, liftY - throwWindup * 0.05f, liftZ + throwWindup * 0.14f);
		poseStack.mulPose(Axis.XP.rotationDegrees(throwWindup * -28.0f));
		poseStack.scale(0.62f, 0.62f, 0.62f);
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEX_BOMB));
		BOMB_MODEL.render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}

	/** Renders the Fairy Ocarina cupped cleanly at Link's lips (right after {@code hm.head.translateAndRotate}). */
	public static void renderOcarinaAtMouth(PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		poseStack.translate(0.02f, -0.09f, -0.33f);
		poseStack.mulPose(Axis.YP.rotationDegrees(78.0f));
		poseStack.mulPose(Axis.ZP.rotationDegrees(8.0f));
		poseStack.scale(0.84f, 0.84f, 0.84f);
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEX_OCARINA));
		OCARINA_MODEL.render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}

	/** Renders the flashing Bomb entity right on top of the floor, tumbling cleanly around its sphere center. */
	public static void renderBombEntity(int fuseTicks, float partialTick, float yawDeg, float rollDeg,
										PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		poseStack.pushPose();
		float speed = fuseTicks < 24 ? 1.25f : 0.42f;
		float pulse = 1.0f + (fuseTicks < 24 ? 0.12f : 0.04f) * Math.max(0.0f, Mth.sin((fuseTicks - partialTick) * speed));
		// Exact lowest-vertex clearance at any tumble angle so the bomb never clips into the ground block
		float rollRad = rollDeg * Mth.DEG_TO_RAD;
		float absC = Math.abs(Mth.cos(rollRad));
		float absS = Math.abs(Mth.sin(rollRad));
		float halfExtentPx = Math.max(4.5f * (absC + absS), 3.3f * absC + 5.2f * absS);
		float centerHeight = (halfExtentPx / 16.0f) * 0.64f * pulse;
		poseStack.translate(0.0f, centerHeight, 0.0f);
		poseStack.mulPose(Axis.YP.rotationDegrees(-yawDeg));
		poseStack.mulPose(Axis.XP.rotationDegrees(rollDeg));
		poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
		poseStack.scale(0.64f * pulse, 0.64f * pulse, 0.64f * pulse);
		// Align BOMB_MODEL's true sphere center (Y in [-3,+6]px -> midpoint +1.5px = +0.09375) onto (0,0,0)
		poseStack.translate(0.0f, -0.09375f, 0.0f);

		boolean flashOn = fuseTicks < 24 && ((fuseTicks / 2) % 2 == 0);
		int light = flashOn ? LightTexture.FULL_BRIGHT : packedLight;
		int overlay = flashOn
				? OverlayTexture.pack(OverlayTexture.u(1.0f), OverlayTexture.v(false))
				: OverlayTexture.NO_OVERLAY;
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEX_BOMB));
		BOMB_MODEL.render(poseStack, vc, light, overlay);
		poseStack.popPose();
	}

	/** Renders a centered multi-layer animated flame + rising embers right at the charred stick tip. */
	private static void renderAnimatedFlame(PoseStack poseStack, MultiBufferSource buffer, float waveArc, boolean handSpace) {
		double t = (System.nanoTime() & 0xFFFFFFFFL) / 1.0e9;
		VertexConsumer flameVc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEX_DEKU_STICK_LIT));

		poseStack.pushPose();
		// Exact tip center of the charred upper shaft in ModelPart coordinates (-Y is up)
		poseStack.translate(0.01f, -1.75f, 0.0f);
		if (handSpace) {
			poseStack.mulPose(Axis.XP.rotationDegrees(-55.0f - waveArc * 16.0f));
		}

		// 1. Centered outer orange/red flame
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees((float) ((t * 95.0) % 360.0)));
		poseStack.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(t * 14.0) * 8.0f));
		float outerW = 0.88f + (float) Math.sin(t * 21.0) * 0.16f;
		float outerH = 0.90f + (float) Math.cos(t * 17.5) * 0.25f;
		poseStack.scale(outerW, outerH, outerW);
		DEKU_STICK_FLAME_OUTER.render(poseStack, flameVc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();

		// 2. Centered bright yellow core tongue
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees((float) ((-t * 130.0) % 360.0)));
		poseStack.mulPose(Axis.XP.rotationDegrees((float) Math.cos(t * 19.0) * 7.0f));
		float coreW = 0.85f + (float) Math.cos(t * 26.0) * 0.20f;
		float coreH = 0.92f + (float) Math.sin(t * 23.0) * 0.32f;
		poseStack.scale(coreW, coreH, coreW);
		DEKU_STICK_FLAME_CORE.render(poseStack, flameVc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();

		// 3. Two rising ember sparks
		for (int i = 0; i < 2; i++) {
			float phase = (float) ((t * 1.45 + i * 0.5) % 1.0);
			float sparkScale = Math.max(0.15f, 1.0f - phase);
			float angle = (float) (t * 4.5 + i * Math.PI);
			poseStack.pushPose();
			poseStack.translate(
					Mth.cos(angle) * 0.08f * (0.4f + phase),
					-0.18f - phase * 0.42f,
					Mth.sin(angle) * 0.08f * (0.4f + phase)
			);
			poseStack.scale(sparkScale, sparkScale, sparkScale);
			DEKU_STICK_FLAME_EMBER.render(poseStack, flameVc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}

		poseStack.popPose();
	}

	/**
	 * Renders any 3D item spinning cleanly in world space (chest reveal cutscene & entity drops).
	 */
	public static void renderStandingSpin(String itemId, boolean lit, float bobSeconds,
										  PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		boolean isLong = "deku_stick".equals(itemId) || "deku_stick_lit".equals(itemId)
				|| "kokiri_sword".equals(itemId) || "wooden_sword".equals(itemId);
		poseStack.pushPose();
		poseStack.translate(0.0f, Mth.sin(bobSeconds * 3.0f) * 0.04f, 0.0f);
		poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
		if (isLong) {
			// Center long items vertically above chest / head
			poseStack.translate(0.0f, 0.55f, 0.0f);
			poseStack.scale(0.68f, 0.68f, 0.68f);
		} else if ("bomb".equals(itemId)) {
			poseStack.scale(0.66f, 0.66f, 0.66f);
		} else {
			poseStack.scale(1.05f, 1.05f, 1.05f);
		}
		int light = lit ? LightTexture.FULL_BRIGHT : packedLight;
		VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(textureFor(itemId, lit)));
		modelFor(itemId, lit).render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
		if (lit && ("deku_stick".equals(itemId) || "deku_stick_lit".equals(itemId))) {
			renderAnimatedFlame(poseStack, buffer, 0.0f, false);
		}
		poseStack.popPose();
	}

	private static ModelPart buildDekuStickModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("grip_butt",
				CubeListBuilder.create().texOffs(32, 0)
						.addBox(-0.90F, 0.80F, -0.90F, 1.80F, 1.20F, 1.80F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shaft_lower",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-1.10F, -9.50F, -1.10F, 2.20F, 10.50F, 2.20F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shaft_mid",
				CubeListBuilder.create().texOffs(8, 0)
						.addBox(-0.95F, -10.00F, -0.95F, 1.90F, 10.30F, 1.90F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.00F, -9.50F, 0.00F, 0.0000F, 0.0000F, 0.0524F));
		root.addOrReplaceChild("shaft_upper",
				CubeListBuilder.create().texOffs(16, 0)
						.addBox(-0.80F, -8.40F, -0.75F, 1.60F, 8.60F, 1.60F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.20F, -19.40F, 0.00F, 0.0000F, 0.0000F, -0.0698F));
		root.addOrReplaceChild("cut_tip",
				CubeListBuilder.create().texOffs(32, 4)
						.addBox(-0.75F, -9.00F, -0.70F, 1.50F, 0.90F, 1.50F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.20F, -19.40F, 0.00F, 0.0000F, 0.0000F, -0.0698F));
		root.addOrReplaceChild("branch_knot_right",
				CubeListBuilder.create().texOffs(24, 0)
						.addBox(-0.20F, -1.40F, -0.65F, 2.30F, 1.80F, 1.30F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.90F, -16.20F, 0.00F, 0.0000F, 0.0000F, -0.4538F));
		root.addOrReplaceChild("sprout_leaf",
				CubeListBuilder.create().texOffs(48, 0)
						.addBox(-0.10F, -1.20F, -0.55F, 1.70F, 1.40F, 1.10F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(2.50F, -17.40F, 0.00F, 0.0000F, 0.1745F, -0.3142F));
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildDekuStickLitWoodModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("grip_butt",
				CubeListBuilder.create().texOffs(32, 0)
						.addBox(-0.90F, 0.80F, -0.90F, 1.80F, 1.20F, 1.80F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shaft_lower",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-1.10F, -9.50F, -1.10F, 2.20F, 10.50F, 2.20F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shaft_mid",
				CubeListBuilder.create().texOffs(8, 0)
						.addBox(-0.95F, -10.00F, -0.95F, 1.90F, 10.30F, 1.90F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.00F, -9.50F, 0.00F, 0.0000F, 0.0000F, 0.0524F));
		root.addOrReplaceChild("shaft_charred",
				CubeListBuilder.create().texOffs(32, 16)
						.addBox(-0.80F, -7.80F, -0.75F, 1.60F, 8.00F, 1.60F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.20F, -19.40F, 0.00F, 0.0000F, 0.0000F, -0.0698F));
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildDekuNutModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("shell_core",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-3.50F, -3.20F, -3.50F, 7.00F, 7.20F, 7.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shell_mid_band",
				CubeListBuilder.create().texOffs(0, 12)
						.addBox(-4.20F, -1.80F, -4.20F, 8.40F, 4.40F, 8.40F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("shell_base_tip",
				CubeListBuilder.create().texOffs(16, 0)
						.addBox(-2.50F, 3.80F, -2.50F, 5.00F, 1.40F, 5.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("leaf_crown",
				CubeListBuilder.create().texOffs(40, 0)
						.addBox(-3.00F, -4.60F, -3.00F, 6.00F, 1.60F, 6.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("stem_nub",
				CubeListBuilder.create().texOffs(48, 0)
						.addBox(-0.80F, -1.70F, -0.80F, 1.60F, 1.70F, 1.60F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.00F, -4.50F, 0.00F, 0.0000F, 0.0000F, -0.2094F));
		root.addOrReplaceChild("flash_seam_glow",
				CubeListBuilder.create().texOffs(40, 16)
						.addBox(-4.40F, 0.20F, -4.40F, 8.80F, 0.60F, 8.80F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildSlingshotModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("grip",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-1.00F, 0.50F, -1.00F, 2.00F, 6.50F, 2.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("pommel_wrap",
				CubeListBuilder.create().texOffs(32, 0)
						.addBox(-1.20F, 5.80F, -1.20F, 2.40F, 1.60F, 2.40F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("fork_left",
				CubeListBuilder.create().texOffs(8, 0)
						.addBox(-3.30F, -6.30F, -0.90F, 1.80F, 6.50F, 1.80F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(-0.50F, 0.80F, 0.00F, 0.0000F, 0.0000F, -0.3142F));
		root.addOrReplaceChild("fork_right",
				CubeListBuilder.create().texOffs(16, 0)
						.addBox(1.50F, -6.30F, -0.90F, 1.80F, 6.50F, 1.80F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.50F, 0.80F, 0.00F, 0.0000F, 0.0000F, 0.3142F));
		root.addOrReplaceChild("band_left",
				CubeListBuilder.create().texOffs(32, 4)
						.addBox(-4.60F, -5.40F, -1.10F, 2.00F, 1.20F, 2.20F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("band_right",
				CubeListBuilder.create().texOffs(32, 8)
						.addBox(2.60F, -5.40F, -1.10F, 2.00F, 1.20F, 2.20F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("elastic_cord",
				CubeListBuilder.create().texOffs(48, 0)
						.addBox(-3.80F, -5.10F, -0.30F, 7.60F, 0.60F, 0.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildBombModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("body_core",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-4.50F, -3.00F, -4.50F, 9.00F, 9.00F, 9.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("body_wide_x",
				CubeListBuilder.create().texOffs(0, 12)
						.addBox(-5.20F, -1.80F, -3.80F, 10.40F, 6.60F, 7.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("body_wide_z",
				CubeListBuilder.create().texOffs(16, 12)
						.addBox(-3.80F, -1.80F, -5.20F, 7.60F, 6.60F, 10.40F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("neck_collar",
				CubeListBuilder.create().texOffs(40, 0)
						.addBox(-2.00F, -4.40F, -2.00F, 4.00F, 1.60F, 4.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("fuse_stem",
				CubeListBuilder.create().texOffs(40, 16)
						.addBox(-0.60F, -2.40F, -0.60F, 1.20F, 2.60F, 1.20F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.00F, -4.40F, 0.00F, -0.2618F, 0.0000F, 0.3142F));
		root.addOrReplaceChild("fuse_spark",
				CubeListBuilder.create().texOffs(48, 16)
						.addBox(-0.40F, -8.20F, -0.80F, 1.80F, 1.60F, 1.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildOcarinaModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("chamber_main",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-3.50F, -2.50F, -2.80F, 7.50F, 5.00F, 5.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("chamber_tail",
				CubeListBuilder.create().texOffs(0, 12)
						.addBox(-2.40F, -1.60F, -1.80F, 2.60F, 3.20F, 3.60F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(-3.20F, 0.00F, 0.00F, 0.0000F, 0.0000F, 0.1745F));
		root.addOrReplaceChild("mouthpiece",
				CubeListBuilder.create().texOffs(40, 16)
						.addBox(-1.30F, -1.20F, -0.30F, 2.60F, 2.40F, 3.60F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(0.30F, 1.00F, 2.50F, 0.1745F, 0.3142F, 0.0000F));
		root.addOrReplaceChild("gold_ring",
				CubeListBuilder.create().texOffs(40, 0)
						.addBox(2.60F, -2.00F, -3.00F, 1.60F, 4.00F, 6.00F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("nose_cap",
				CubeListBuilder.create().texOffs(16, 0)
						.addBox(4.00F, -1.40F, -1.80F, 1.60F, 2.80F, 3.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}

	private static ModelPart buildKokiriSwordModel() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("blade_base",
				CubeListBuilder.create().texOffs(0, 0)
						.addBox(-0.80F, -12.00F, -0.40F, 1.60F, 8.50F, 0.80F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("blade_tip",
				CubeListBuilder.create().texOffs(8, 0)
						.addBox(-0.60F, -13.80F, -0.25F, 1.20F, 2.00F, 0.50F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crossguard_center",
				CubeListBuilder.create().texOffs(32, 0)
						.addBox(-2.00F, -3.80F, -0.80F, 4.00F, 1.40F, 1.60F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("crossguard_wing_l",
				CubeListBuilder.create().texOffs(32, 6)
						.addBox(-1.60F, -1.00F, -0.60F, 1.80F, 1.30F, 1.20F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(-1.80F, -3.20F, 0.00F, 0.0000F, 0.0000F, 0.4363F));
		root.addOrReplaceChild("crossguard_wing_r",
				CubeListBuilder.create().texOffs(32, 6)
						.addBox(-0.20F, -1.00F, -0.60F, 1.80F, 1.30F, 1.20F, CubeDeformation.NONE),
				PartPose.offsetAndRotation(1.80F, -3.20F, 0.00F, 0.0000F, 0.0000F, -0.4363F));
		root.addOrReplaceChild("leather_grip",
				CubeListBuilder.create().texOffs(48, 0)
						.addBox(-0.60F, -2.60F, -0.60F, 1.20F, 3.10F, 1.20F, CubeDeformation.NONE),
				PartPose.ZERO);
		root.addOrReplaceChild("ruby_pommel",
				CubeListBuilder.create().texOffs(32, 16)
						.addBox(-0.90F, 0.30F, -0.90F, 1.80F, 1.40F, 1.80F, CubeDeformation.NONE),
				PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 32).bakeRoot();
	}
}