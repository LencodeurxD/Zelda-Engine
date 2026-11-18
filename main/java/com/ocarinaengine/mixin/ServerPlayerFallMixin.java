package com.ocarinaengine.mixin;

import com.ocarinaengine.combat.ZeldaFallServer;
import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Zelda fall damage rules:
 * - Increases safe fall height by +4 blocks before taking damage.
 * - Cancels fall damage completely on drops <= 20 blocks when Link lands with a roll.
 */
@Mixin(LivingEntity.class)
public abstract class ServerPlayerFallMixin {
	@ModifyVariable(
			method = "causeFallDamage(FFLnet/minecraft/world/damagesource/DamageSource;)Z",
			at = @At("HEAD"),
			ordinal = 0,
			argsOnly = true
	)
	private float ocarina$adjustZeldaFallDistance(float fallDistance) {
		if ((Object) this instanceof ServerPlayer player && OcarinaGameTypeServer.isZelda(player)) {
			if (ZeldaFallServer.tryNegateFallDamageWithRoll(player, fallDistance)) {
				return 0.0f;
			}
			return Math.max(0.0f, fallDistance - ZeldaFallServer.ZELDA_SAFE_FALL_BONUS);
		}
		return fallDistance;
	}
}
