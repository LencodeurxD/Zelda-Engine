package com.ocarinaengine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ocarinaengine.client.animation.ArticulatedPlayerModel;
import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.animation.ZeldaPlayerAnimator;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.equipment.ZeldaItem;
import com.ocarinaengine.client.render.ZeldaShieldRenderer;
import com.ocarinaengine.client.screen.ZeldaEquipmentScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders Link's equipped Zelda sword (right hand or back scabbard) and equipped Zelda shield
 * (left arm while guarding / in combat / in Equipment preview, or slung on Link's back when exploring).
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin<T extends LivingEntity, M extends EntityModel<T> & ArmedModel>
		extends RenderLayer<T, M> {
	protected ItemInHandLayerMixin(RenderLayerParent<T, M> renderer) {
		super(renderer);
	}

	@Shadow
	protected abstract void renderArmWithItem(LivingEntity livingEntity, ItemStack itemStack,
											  ItemDisplayContext displayContext, HumanoidArm arm,
											  PoseStack poseStack, MultiBufferSource buffer, int packedLight);

	@Inject(
		method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ocarina$renderEquippedZeldaSword(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
												  T livingEntity, float limbSwing, float limbSwingAmount,
												  float partialTicks, float ageInTicks,
												  float netHeadYaw, float headPitch, CallbackInfo ci) {
		if (!ZeldaPlayerAnimator.shouldAnimate(livingEntity)) {
			return;
		}
		ci.cancel();

		if (ZeldaChestCutscene.isActive()) {
			return;
		}

		boolean isPreview = ZeldaEquipmentScreen.isPreviewEntity(livingEntity);

		// --- 1. Sword (Right Hand or Diagonal Back Scabbard) ---
		if (ZeldaEquipment.hasEquippedSword()) {
			ZeldaItem sword = ZeldaEquipment.equippedSword();
			if (sword != null && !sword.stack().isEmpty()) {
				ItemStack swordStack = sword.stack();
				if (isPreview || ZeldaCombatSystem.isSwordDrawn()) {
					float wristPitchDeg = !isPreview ? ZeldaAnimationEngine.getSwordWristPitchDeg() : 0.0f;
					float wristYawDeg = !isPreview ? ZeldaAnimationEngine.getSwordWristYawDeg() : 0.0f;
					float wristRollDeg = !isPreview ? ZeldaAnimationEngine.getSwordWristRollDeg() : 0.0f;
					if (!isPreview
							&& (Math.abs(wristPitchDeg) > 0.1f || Math.abs(wristYawDeg) > 0.1f || Math.abs(wristRollDeg) > 0.1f)
							&& this.getParentModel() instanceof HumanoidModel<?> hm) {
						poseStack.pushPose();
						hm.translateToHand(HumanoidArm.RIGHT, poseStack);
						poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
						poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
						poseStack.translate(1.0f / 16.0f, 0.125f, -0.625f);
						if (Math.abs(wristRollDeg) > 0.1f) {
							poseStack.mulPose(Axis.ZP.rotationDegrees(wristRollDeg));
						}
						if (Math.abs(wristYawDeg) > 0.1f) {
							poseStack.mulPose(Axis.YP.rotationDegrees(wristYawDeg));
						}
						if (Math.abs(wristPitchDeg) > 0.1f) {
							poseStack.mulPose(Axis.XP.rotationDegrees(-wristPitchDeg));
						}
						Minecraft.getInstance().getItemRenderer().renderStatic(
								swordStack,
								ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
								packedLight,
								OverlayTexture.NO_OVERLAY,
								poseStack,
								buffer,
								livingEntity.level(),
								livingEntity.getId()
						);
						poseStack.popPose();
					} else {
						this.renderArmWithItem(
								livingEntity,
								swordStack,
								ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
								HumanoidArm.RIGHT,
								poseStack,
								buffer,
								packedLight
						);
					}
				} else {
					poseStack.pushPose();
					if (this.getParentModel() instanceof HumanoidModel<?> hm) {
						hm.body.translateAndRotate(poseStack);
					}
					// Positioned diagonally across Link's back with the hilt rising behind his right shoulder.
					poseStack.translate(-0.02f, 0.34f, 0.165f);
					poseStack.mulPose(Axis.ZP.rotationDegrees(176.0f));
					poseStack.scale(0.68f, 0.68f, 0.68f);
					Minecraft.getInstance().getItemRenderer().renderStatic(
							swordStack,
							ItemDisplayContext.FIXED,
							packedLight,
							OverlayTexture.NO_OVERLAY,
							poseStack,
							buffer,
							livingEntity.level(),
							livingEntity.getId()
					);
					poseStack.popPose();
				}
			}
		}

		// --- 2. Shield (Left Forearm in Guard/Combat/Preview, or Slung Across Back) ---
		if (ZeldaEquipment.hasEquippedShield()) {
			ZeldaItem shield = ZeldaEquipment.equippedShield();
			if (shield != null) {
				boolean slim = this.getParentModel() instanceof ArticulatedPlayerModel apm && apm.ocarina$isSlim();
				if (isPreview || ZeldaCombatSystem.isShieldVisibleInHand()) {
					if (this.getParentModel() instanceof HumanoidModel<?> hm) {
						poseStack.pushPose();
						hm.translateToHand(HumanoidArm.LEFT, poseStack);
						float raiseBlend = isPreview ? 0.0f : ZeldaAnimationEngine.getShieldRaiseBlend();
						float impactProgress = isPreview ? -1.0f : ZeldaCombatSystem.getShieldImpactProgress();
						ZeldaShieldRenderer.renderInLeftHand(
								shield.id(), slim, raiseBlend, impactProgress, poseStack, buffer, packedLight);
						poseStack.popPose();
					}
				} else if (this.getParentModel() instanceof HumanoidModel<?> hm) {
					poseStack.pushPose();
					hm.body.translateAndRotate(poseStack);
					ZeldaShieldRenderer.renderOnBack(shield.id(), poseStack, buffer, packedLight);
					poseStack.popPose();
				}
			}
		}
	}
}
