package com.ocarinaengine.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ocarinaengine.client.animation.ArticulatedPlayerModel;
import java.util.EnumSet;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Splits PlayerModel's arms and legs into articulated 6px + 6px upper/lower segments
 * (forearms + sleeves, shins + pants) with seal-matched joint pivots and accurate
 * 64x64 skin UVs, supporting both Classic (4px) and Slim (3px) player skins.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> implements ArticulatedPlayerModel {
	@Shadow
	@Final
	public ModelPart leftSleeve;

	@Shadow
	@Final
	public ModelPart rightSleeve;

	@Shadow
	@Final
	public ModelPart leftPants;

	@Shadow
	@Final
	public ModelPart rightPants;

	@Shadow
	@Final
	private boolean slim;

	@Unique
	private ModelPart ocarina$rightForearm;

	@Unique
	private ModelPart ocarina$leftForearm;

	@Unique
	private ModelPart ocarina$rightShin;

	@Unique
	private ModelPart ocarina$leftShin;

	@Unique
	private ModelPart ocarina$rightForearmSleeve;

	@Unique
	private ModelPart ocarina$leftForearmSleeve;

	@Unique
	private ModelPart ocarina$rightShinPants;

	@Unique
	private ModelPart ocarina$leftShinPants;

	protected PlayerModelMixin(ModelPart root) {
		super(root);
	}

	@Inject(method = "createMesh", at = @At("RETURN"))
	private static void ocarina$splitLimbs(CubeDeformation cubeDeformation, boolean slim,
										   CallbackInfoReturnable<MeshDefinition> cir) {
		PartDefinition root = cir.getReturnValue().getRoot();
		CubeDeformation outerDeform = cubeDeformation.extend(0.25F);

		float armWidth = slim ? 3.0F : 4.0F;
		float armPivotY = slim ? 2.5F : 2.0F;
		float rightArmX0 = slim ? -2.0F : -3.0F;
		float leftArmX0 = -1.0F;

		// Right Arm + Sleeve (elbow hinge at back edge z = +2 so forward bend never opens a gap)
		buildArticulatedLimb(root, "right_arm", "right_forearm",
				40, 16, rightArmX0, -2.0F, armWidth, 4.0F,
				-5.0F, armPivotY, 0.0F, 4.0F, 2.0F, cubeDeformation);
		buildArticulatedLimb(root, "right_sleeve", "right_forearm_sleeve",
				40, 32, rightArmX0, -2.0F, armWidth, 4.0F,
				-5.0F, armPivotY, 0.0F, 4.0F, 2.0F, outerDeform);

		// Left Arm + Sleeve
		buildArticulatedLimb(root, "left_arm", "left_forearm",
				32, 48, leftArmX0, -2.0F, armWidth, 4.0F,
				5.0F, armPivotY, 0.0F, 4.0F, 2.0F, cubeDeformation);
		buildArticulatedLimb(root, "left_sleeve", "left_forearm_sleeve",
				48, 48, leftArmX0, -2.0F, armWidth, 4.0F,
				5.0F, armPivotY, 0.0F, 4.0F, 2.0F, outerDeform);

		// Right Leg + Pants (knee hinge at front edge z = -2 so backward bend never opens a gap)
		buildArticulatedLimb(root, "right_leg", "right_shin",
				0, 16, -2.0F, 0.0F, 4.0F, 4.0F,
				-1.9F, 12.0F, 0.0F, 6.0F, -2.0F, cubeDeformation);
		buildArticulatedLimb(root, "right_pants", "right_shin_pants",
				0, 32, -2.0F, 0.0F, 4.0F, 4.0F,
				-1.9F, 12.0F, 0.0F, 6.0F, -2.0F, outerDeform);

		// Left Leg + Pants
		buildArticulatedLimb(root, "left_leg", "left_shin",
				16, 48, -2.0F, 0.0F, 4.0F, 4.0F,
				1.9F, 12.0F, 0.0F, 6.0F, -2.0F, cubeDeformation);
		buildArticulatedLimb(root, "left_pants", "left_shin_pants",
				0, 48, -2.0F, 0.0F, 4.0F, 4.0F,
				1.9F, 12.0F, 0.0F, 6.0F, -2.0F, outerDeform);
	}

	@Unique
	private static void buildArticulatedLimb(
			PartDefinition root,
			String upperName,
			String lowerName,
			int texU,
			int texV,
			float x0,
			float y0,
			float width,
			float depth,
			float pivotX,
			float pivotY,
			float pivotZ,
			float jointPivotY,
			float jointPivotZ,
			CubeDeformation deform
	) {
		int dInt = (int) depth;
		boolean exactZeroDeform = (deform == CubeDeformation.NONE);

		// Upper 6 pixels: top face + upper 6 side rows from vanilla UV,
		// and bottom joint cap sampled from the upper limb's side texture at the cut.
		CubeListBuilder upperBuilder = CubeListBuilder.create();
		if (exactZeroDeform) {
			upperBuilder
					.texOffs(texU, texV)
					.addBox(x0, y0, -2.0F, width, 6.0F, depth, EnumSet.complementOf(EnumSet.of(Direction.DOWN)))
					.texOffs(texU, texV + dInt + 2)
					.addBox(x0, y0, -2.0F, width, 6.0F, depth, EnumSet.of(Direction.DOWN));
		} else {
			upperBuilder
					.texOffs(texU, texV)
					.addBox(x0, y0, -2.0F, width, 6.0F, depth, deform);
		}

		PartDefinition upperPart = root.addOrReplaceChild(
				upperName,
				upperBuilder,
				PartPose.offset(pivotX, pivotY, pivotZ)
		);

		// Lower 6 pixels: lower 6 side rows (texV + 6), original hand/foot sole (texV),
		// and top joint cap sampled from the lower limb's front texture right below the joint.
		float lowerZ0 = -2.0F - jointPivotZ;
		CubeListBuilder lowerBuilder = CubeListBuilder.create();
		if (exactZeroDeform) {
			lowerBuilder
					.texOffs(texU, texV + 6)
					.addBox(x0, 0.0F, lowerZ0, width, 6.0F, depth, EnumSet.complementOf(EnumSet.of(Direction.UP, Direction.DOWN)))
					.texOffs(texU, texV)
					.addBox(x0, 0.0F, lowerZ0, width, 6.0F, depth, EnumSet.of(Direction.DOWN))
					.texOffs(texU, texV + dInt + 6)
					.addBox(x0, 0.0F, lowerZ0, width, 6.0F, depth, EnumSet.of(Direction.UP));
		} else {
			lowerBuilder
					.texOffs(texU, texV + 6)
					.addBox(x0, 0.0F, lowerZ0, width, 6.0F, depth, deform);
		}

		upperPart.addOrReplaceChild(
				lowerName,
				lowerBuilder,
				PartPose.offset(0.0F, jointPivotY, jointPivotZ)
		);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ocarina$cacheChildLimbs(ModelPart root, boolean slim, CallbackInfo ci) {
		this.ocarina$rightForearm = this.rightArm.hasChild("right_forearm") ? this.rightArm.getChild("right_forearm") : null;
		this.ocarina$leftForearm = this.leftArm.hasChild("left_forearm") ? this.leftArm.getChild("left_forearm") : null;
		this.ocarina$rightShin = this.rightLeg.hasChild("right_shin") ? this.rightLeg.getChild("right_shin") : null;
		this.ocarina$leftShin = this.leftLeg.hasChild("left_shin") ? this.leftLeg.getChild("left_shin") : null;

		this.ocarina$rightForearmSleeve = this.rightSleeve.hasChild("right_forearm_sleeve") ? this.rightSleeve.getChild("right_forearm_sleeve") : null;
		this.ocarina$leftForearmSleeve = this.leftSleeve.hasChild("left_forearm_sleeve") ? this.leftSleeve.getChild("left_forearm_sleeve") : null;
		this.ocarina$rightShinPants = this.rightPants.hasChild("right_shin_pants") ? this.rightPants.getChild("right_shin_pants") : null;
		this.ocarina$leftShinPants = this.leftPants.hasChild("left_shin_pants") ? this.leftPants.getChild("left_shin_pants") : null;
	}

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
	private void ocarina$resetArticulatedJoints(T entity, float limbSwing, float limbSwingAmount,
												float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		resetRot(this.ocarina$rightForearm);
		resetRot(this.ocarina$leftForearm);
		resetRot(this.ocarina$rightShin);
		resetRot(this.ocarina$leftShin);
	}

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void ocarina$syncArticulatedOverlays(T entity, float limbSwing, float limbSwingAmount,
												 float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		copyRot(this.ocarina$rightForearm, this.ocarina$rightForearmSleeve);
		copyRot(this.ocarina$leftForearm, this.ocarina$leftForearmSleeve);
		copyRot(this.ocarina$rightShin, this.ocarina$rightShinPants);
		copyRot(this.ocarina$leftShin, this.ocarina$leftShinPants);
	}

	@Inject(method = "translateToHand", at = @At("TAIL"))
	private void ocarina$translateThroughForearm(HumanoidArm arm, PoseStack poseStack, CallbackInfo ci) {
		ModelPart forearm = (arm == HumanoidArm.RIGHT) ? this.ocarina$rightForearm : this.ocarina$leftForearm;
		if (forearm != null) {
			forearm.translateAndRotate(poseStack);
			// Compensate elbow pivot offset (0, +4px, +2px) so vanilla's -0.625 hand offset stays at the wrist.
			poseStack.translate(0.0F, -0.25F, -0.125F);
		}
	}

	@Unique
	private static void resetRot(ModelPart part) {
		if (part != null) {
			part.xRot = 0.0F;
			part.yRot = 0.0F;
			part.zRot = 0.0F;
		}
	}

	@Unique
	private static void copyRot(ModelPart src, ModelPart dst) {
		if (src != null && dst != null) {
			dst.copyFrom(src);
		}
	}

	@Override
	public ModelPart ocarina$rightForearm() {
		return this.ocarina$rightForearm;
	}

	@Override
	public ModelPart ocarina$leftForearm() {
		return this.ocarina$leftForearm;
	}

	@Override
	public ModelPart ocarina$rightShin() {
		return this.ocarina$rightShin;
	}

	@Override
	public ModelPart ocarina$leftShin() {
		return this.ocarina$leftShin;
	}

	@Override
	public ModelPart ocarina$rightForearmSleeve() {
		return this.ocarina$rightForearmSleeve;
	}

	@Override
	public ModelPart ocarina$leftForearmSleeve() {
		return this.ocarina$leftForearmSleeve;
	}

	@Override
	public ModelPart ocarina$rightShinPants() {
		return this.ocarina$rightShinPants;
	}

	@Override
	public ModelPart ocarina$leftShinPants() {
		return this.ocarina$leftShinPants;
	}

	@Override
	public boolean ocarina$isSlim() {
		return this.slim;
	}
}
