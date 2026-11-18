package com.ocarinaengine.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaBladeTracker;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.screen.ZeldaEquipmentScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * Coordinates all procedural Zelda-style player animations across both HumanoidModel joints and PoseStack transforms.
 */
public final class ZeldaPlayerAnimator {
	private static float lastPartialTick = 1.0f;

	private ZeldaPlayerAnimator() {}

	public static boolean shouldAnimate(LivingEntity entity) {
		return (entity == Minecraft.getInstance().player && OcarinaGameTypeClient.isZelda())
				|| ZeldaEquipmentScreen.isPreviewEntity(entity);
	}

	public static void applyBodyTransform(AbstractClientPlayer entity, PoseStack poseStack, float partialTicks) {
		if (!OcarinaGameTypeClient.isZelda()) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (entity != player || player == null || ZeldaChestCutscene.isActive() || ZeldaEquipmentScreen.isPreviewEntity(entity)) {
			return;
		}
		if (player.isSwimming() || player.isFallFlying() || player.isSleeping()
				|| player.isAutoSpinAttack() || player.isPassenger() || player.isDeadOrDying()) {
			return;
		}

		lastPartialTick = partialTicks;
		ZeldaAnimationEngine.ensureWorldUpdated(player, partialTicks, -1.0f);

		float shiftX = ZeldaAnimationEngine.getWholeShiftRightBlocks();
		float offsetY = ZeldaAnimationEngine.getWholeOffsetYBlocks() + ZeldaQuickItems.getWholeOffsetYBlocks();
		float leanForward = ZeldaAnimationEngine.getWholeLeanForwardRad() + ZeldaQuickItems.getWholeLeanForwardRad();
		float bankRight = ZeldaAnimationEngine.getWholeBankRightRad();
		float spinYaw = ZeldaAnimationEngine.getWholeSpinYawRad();
		float flipPitch = ZeldaAnimationEngine.getWholeFlipPitchRad();
		float rollZ = ZeldaAnimationEngine.getWholeRollZRad();
		float scaleY = ZeldaAnimationEngine.getWholeScaleY();

		if (Math.abs(shiftX) > 1.0e-4f || Math.abs(offsetY) > 1.0e-4f) {
			poseStack.translate(shiftX, offsetY, 0.0f);
		}
		if (Math.abs(spinYaw) > 1.0e-4f) {
			poseStack.mulPose(Axis.YP.rotation(spinYaw));
		}
		if (Math.abs(leanForward) > 1.0e-4f) {
			poseStack.mulPose(Axis.XP.rotation(-leanForward));
		}
		if (Math.abs(bankRight) > 1.0e-4f) {
			poseStack.mulPose(Axis.ZP.rotation(-bankRight));
		}
		if (Math.abs(flipPitch) > 1.0e-4f) {
			poseStack.translate(0.0f, 0.90f, 0.0f);
			poseStack.mulPose(Axis.XP.rotation(flipPitch));
			poseStack.translate(0.0f, -0.90f, 0.0f);
		}
		if (Math.abs(rollZ) > 1.0e-4f) {
			poseStack.translate(0.0f, 0.90f, 0.0f);
			poseStack.mulPose(Axis.ZP.rotation(-rollZ));
			poseStack.translate(0.0f, -0.90f, 0.0f);
		}
		if (Math.abs(scaleY - 1.0f) > 1.0e-3f) {
			float sy = 1.0f + (Mth.clamp(scaleY, 0.80f, 1.15f) - 1.0f) * 0.55f;
			float sxz = 1.0f / (float) Math.sqrt(sy);
			poseStack.scale(sxz, sy, sxz);
		}
	}

	public static void apply(HumanoidModel<?> model, LivingEntity entity, float limbSwingAmount) {
		if (ZeldaEquipmentScreen.isPreviewEntity(entity)) {
			ZeldaAnimationEngine.applyMenuIdle(model);
			return;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (entity != player || player == null) {
			return;
		}

		if (ZeldaChestCutscene.isActive()) {
			ZeldaAnimationEngine.resetWorld();
			if (ZeldaChestCutscene.isBigChest()) {
				applyBigChest(model, ZeldaChestCutscene.elapsedSeconds());
			} else {
				applySmallChest(model, ZeldaChestCutscene.elapsedSeconds());
			}
			return;
		}

		ZeldaAnimationEngine.recordLimbSwing(limbSwingAmount);
		ZeldaAnimationEngine.ensureWorldUpdated(player, lastPartialTick, limbSwingAmount);
		ZeldaAnimationEngine.applyWorldToModel(model);
		ZeldaQuickItems.applyHeldPose(model, entity, limbSwingAmount);
		ZeldaBladeTracker.capture(model, player, lastPartialTick);
	}

	private static void applySmallChest(HumanoidModel<?> m, float t) {
		resetPose(m);
		if (t < 0.42f) {
			float p = easeOut(t / 0.42f);
			float swing = (float) Math.sin(p * Math.PI);
			m.body.xRot = 0.28f * swing;
			m.head.xRot = 0.16f * swing;
			m.rightArm.xRot = -0.92f * swing;
			m.leftArm.xRot = -0.78f * swing;
			m.rightArm.yRot = -0.10f * swing;
			m.leftArm.yRot = 0.10f * swing;
			m.rightLeg.xRot = -0.14f * swing;
			m.leftLeg.xRot = 0.14f * swing;
			setArticulated(m, -0.58f * swing, -0.50f * swing, 0.24f * swing, 0.18f * swing);
		} else {
			float blend = easeOut(Mth.clamp((t - 0.42f) / 0.30f, 0.0f, 1.0f));
			applyPresentingPose(m, blend, t);
		}
		m.hat.copyFrom(m.head);
	}

	private static void applyBigChest(HumanoidModel<?> m, float t) {
		resetPose(m);
		if (t < 0.75f) {
			float p = easeOut(t / 0.75f);
			m.body.xRot = 0.36f * p;
			m.head.xRot = 0.22f * p;
			m.rightArm.xRot = -0.78f * p;
			m.leftArm.xRot = -0.78f * p;
			m.rightArm.yRot = -0.18f * p;
			m.leftArm.yRot = 0.18f * p;
			m.rightLeg.xRot = -0.22f * p;
			m.leftLeg.xRot = 0.10f * p;
			setArticulated(m, -0.68f * p, -0.68f * p, 0.34f * p, 0.28f * p);
		} else if (t < 2.05f) {
			float p = easeInOut((t - 0.75f) / 1.30f);
			m.body.xRot = Mth.lerp(p, 0.36f, -0.06f);
			m.head.xRot = Mth.lerp(p, 0.22f, -0.25f);
			m.rightArm.xRot = Mth.lerp(p, -0.78f, -1.96f);
			m.leftArm.xRot = Mth.lerp(p, -0.78f, -1.96f);
			m.rightArm.yRot = -0.18f * (1.0f - p);
			m.leftArm.yRot = 0.18f * (1.0f - p);
			m.rightArm.zRot = 0.12f * p;
			m.leftArm.zRot = -0.12f * p;
			setArticulated(m, Mth.lerp(p, -0.68f, -0.34f), Mth.lerp(p, -0.68f, -0.34f),
					Mth.lerp(p, 0.34f, 0.08f), Mth.lerp(p, 0.28f, 0.08f));
		} else {
			float p = easeOut(Mth.clamp((t - 2.05f) / 0.35f, 0.0f, 1.0f));
			applyPresentingPoseFrom(m, p, t, -1.96f, -0.34f);
		}
		m.hat.copyFrom(m.head);
	}

	private static void applyPresentingPose(HumanoidModel<?> m, float blend, float t) {
		applyPresentingPoseFrom(m, blend, t, 0.0f, 0.0f);
	}

	private static void applyPresentingPoseFrom(HumanoidModel<?> m, float blend, float t, float startArmX, float startForearmX) {
		float sway = (float) Math.sin(t * 2.6f) * 0.025f * blend;
		m.head.xRot = Mth.lerp(blend, 0.0f, -0.14f);
		m.body.xRot = Mth.lerp(blend, 0.0f, -0.04f);
		m.rightArm.xRot = Mth.lerp(blend, startArmX, -1.56f) + sway;
		m.leftArm.xRot = Mth.lerp(blend, startArmX, -1.56f) + sway;
		m.rightArm.yRot = Mth.lerp(blend, 0.0f, 0.46f);
		m.leftArm.yRot = Mth.lerp(blend, 0.0f, -0.46f);
		m.rightArm.zRot = Mth.lerp(blend, 0.0f, 0.14f);
		m.leftArm.zRot = Mth.lerp(blend, 0.0f, -0.14f);
		m.rightLeg.xRot = 0.03f * blend;
		m.leftLeg.xRot = -0.06f * blend;
		float forearmTarget = Mth.lerp(blend, startForearmX, -0.36f);
		setArticulated(m, forearmTarget, forearmTarget, 0.08f * blend, 0.10f * blend);
	}

	private static void setArticulated(HumanoidModel<?> m, float rForearmX, float lForearmX, float rShinX, float lShinX) {
		if (m instanceof ArticulatedPlayerModel apm) {
			setRotX(apm.ocarina$rightForearm(), rForearmX);
			setRotX(apm.ocarina$leftForearm(), lForearmX);
			setRotX(apm.ocarina$rightShin(), rShinX);
			setRotX(apm.ocarina$leftShin(), lShinX);
		}
	}

	private static void setRotX(ModelPart part, float xRot) {
		if (part != null) {
			part.xRot = xRot;
			part.yRot = 0.0f;
			part.zRot = 0.0f;
		}
	}

	private static void resetPose(HumanoidModel<?> m) {
		boolean slim = (m instanceof ArticulatedPlayerModel apm && apm.ocarina$isSlim());
		float baseShoulderY = slim ? 2.5f : 2.0f;
		m.head.xRot = 0.0f;
		m.head.yRot = 0.0f;
		m.head.zRot = 0.0f;
		m.head.y = 0.0f;
		m.body.xRot = 0.0f;
		m.body.yRot = 0.0f;
		m.body.zRot = 0.0f;
		m.body.y = 0.0f;
		m.rightArm.yRot = 0.0f;
		m.rightArm.zRot = 0.0f;
		m.rightArm.x = -5.0f;
		m.rightArm.y = baseShoulderY;
		m.rightArm.z = 0.0f;
		m.leftArm.yRot = 0.0f;
		m.leftArm.zRot = 0.0f;
		m.leftArm.x = 5.0f;
		m.leftArm.y = baseShoulderY;
		m.leftArm.z = 0.0f;
		m.rightLeg.xRot = 0.0f;
		m.rightLeg.yRot = 0.0f;
		m.rightLeg.zRot = 0.0f;
		m.rightLeg.z = 0.0f;
		m.leftLeg.xRot = 0.0f;
		m.leftLeg.yRot = 0.0f;
		m.leftLeg.zRot = 0.0f;
		m.leftLeg.z = 0.0f;
		setArticulated(m, 0.0f, 0.0f, 0.0f, 0.0f);
	}

	private static float easeOut(float t) {
		float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
		return 1.0f - u * u * u;
	}

	private static float easeInOut(float t) {
		float x = Mth.clamp(t, 0.0f, 1.0f);
		return x * x * (3.0f - 2.0f * x);
	}
}