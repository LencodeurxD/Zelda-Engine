package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Briefly freezes client game ticks during combat hitstop frames for crisp,
 * modern Ocarina of Time impact weight while keeping render-thread timers alive.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void ocarina$freezeTickDuringHitstop(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.level != null && mc.player != null && mc.screen == null && !mc.isPaused() && ZeldaCombatSystem.isInHitstop()) {
			ci.cancel();
		}
	}
}
