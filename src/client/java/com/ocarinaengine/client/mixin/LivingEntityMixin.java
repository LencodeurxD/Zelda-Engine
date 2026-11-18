package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies animation-driven Root Motion to the local player during grounded Walk and Jog
 * cycles and delegates zero-gravity ledge hang/climb physics to {@link ZeldaLedgeSystem}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Unique
	private boolean ocarina$rootMotionApplied = false;

	@Unique
	private float ocarina$savedFrictionFactor = 0.546f;

	@Unique
	private boolean ocarina$wasOnGroundBeforeTravel = true;

	@Unique
	private float ocarina$preTravelFallDistance = 0.0f;

	@Unique
	private double ocarina$preTravelY = 0.0D;

	@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
	private void ocarina$handleLedgeTravel(Vec3 travelVector, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player || mc.player == null) {
			return;
		}
		LocalPlayer player = mc.player;
		this.ocarina$wasOnGroundBeforeTravel = player.onGround();
		this.ocarina$preTravelY = player.getY();
		if (!player.onGround() && !player.isInWater() && !player.isInLava()
				&& !player.onClimbable() && !player.getAbilities().flying
				&& !player.isPassenger() && !ZeldaLedgeSystem.isActive()) {
			this.ocarina$preTravelFallDistance = player.fallDistance;
		} else {
			this.ocarina$preTravelFallDistance = 0.0f;
		}
		if (ZeldaLedgeSystem.handleTravel(player)) {
			this.ocarina$rootMotionApplied = false;
			this.ocarina$preTravelFallDistance = 0.0f;
			this.ocarina$wasOnGroundBeforeTravel = false;
			ZeldaAnimationEngine.syncRootMotionFromPhysics(player);
			ci.cancel();
		}
	}

	@ModifyVariable(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
	private Vec3 ocarina$applyRootMotion(Vec3 travelVector) {
		this.ocarina$rootMotionApplied = false;
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player || mc.player == null) {
			return travelVector;
		}
		LocalPlayer player = mc.player;
		if (ZeldaLedgeSystem.isActive() || !ZeldaAnimationEngine.shouldDriveRootMotion(player)) {
			ZeldaAnimationEngine.syncRootMotionFromPhysics(player);
			return travelVector;
		}

		BlockPos below = BlockPos.containing(player.getX(), player.getBoundingBox().minY - 0.500001D, player.getZ());
		float blockFriction = player.level().getBlockState(below).getBlock().getFriction();
		if (blockFriction > 0.65f) {
			// Preserve natural slipping physics on ice blocks.
			ZeldaAnimationEngine.syncRootMotionFromPhysics(player);
			return travelVector;
		}

		this.ocarina$savedFrictionFactor = player.onGround() ? blockFriction * 0.91f : 0.91f;
		this.ocarina$rootMotionApplied = true;

		Vec3 current = player.getDeltaMovement();
		Vec3 rootVel = ZeldaAnimationEngine.stepRootMotionTick(player);
		player.setDeltaMovement(rootVel.x, current.y, rootVel.z);
		return Vec3.ZERO;
	}

	@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
	private void ocarina$finishRootMotion(Vec3 travelVector, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this == mc.player && mc.player != null) {
			LocalPlayer player = mc.player;
			if (!this.ocarina$wasOnGroundBeforeTravel && player.onGround() && !ZeldaLedgeSystem.isActive()) {
				float totalFall = this.ocarina$preTravelFallDistance + (float) Math.max(0.0D, this.ocarina$preTravelY - player.getY());
				ZeldaCombatSystem.tryTriggerLandingRoll(mc, player, totalFall);
			}
		}
		if (!this.ocarina$rootMotionApplied) {
			return;
		}
		this.ocarina$rootMotionApplied = false;
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.shouldDiscardFriction() && this.ocarina$savedFrictionFactor > 0.05f) {
			Vec3 current = self.getDeltaMovement();
			double inv = 1.0D / (double) this.ocarina$savedFrictionFactor;
			self.setDeltaMovement(current.x * inv, current.y, current.z * inv);
		}
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void ocarina$lockStrafeTorsoYaw(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player || mc.player == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (ZeldaLedgeSystem.isActive()) {
			float wallYaw = ZeldaLedgeSystem.getHangYawDeg();
			player.setYRot(wallYaw);
			player.yBodyRot = wallYaw;
			player.yHeadRot = wallYaw;
		} else if (ZeldaTargetingSystem.isFocusActive() || ZeldaCombatSystem.isSwinging() || ZeldaCombatSystem.isDodging()) {
			float lockedYaw = player.getYRot();
			player.yBodyRot = lockedYaw;
			player.yHeadRot = lockedYaw;
		}
	}
}
