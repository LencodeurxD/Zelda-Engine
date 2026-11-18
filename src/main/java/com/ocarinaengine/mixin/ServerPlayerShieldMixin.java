package com.ocarinaengine.mixin;

import com.ocarinaengine.combat.ZeldaShieldServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class ServerPlayerShieldMixin {
	@Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
	private void ocarina$blockDamageWithZeldaShield(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && ZeldaShieldServer.tryBlockIncomingDamage(player, source)) {
			cir.setReturnValue(false);
		}
	}
}
