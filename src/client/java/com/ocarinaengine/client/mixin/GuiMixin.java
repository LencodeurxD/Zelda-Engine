package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.rules.ZeldaRules;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides vanilla Minecraft HUD elements:
 * health/hunger/armor/air bars (replaced by ZeldaHud), hotbar, and experience.
 * "require = 0": if a method name does not exist in this game version, ignore it without crashing.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true, require = 0)
	private void ocarina$hideStatusBars(CallbackInfo ci) {
		if (ZeldaRules.zeldaHearts && EngineState.isActive()) {
			ci.cancel();
		}
	}

	// In case this Minecraft version renders the food bar in its own separate method.
	@Inject(method = "renderFood", at = @At("HEAD"), cancellable = true, require = 0)
	private void ocarina$hideFood(CallbackInfo ci) {
		if (ZeldaRules.disableHunger && EngineState.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = {"renderItemHotbar", "renderSelectedItemName"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void ocarina$hideHotbar(CallbackInfo ci) {
		if (ZeldaRules.hideHotbar && EngineState.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = {"renderExperienceBar", "renderExperienceLevel"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void ocarina$hideExperience(CallbackInfo ci) {
		if (ZeldaRules.hideExperienceBar && EngineState.isActive()) {
			ci.cancel();
		}
	}
}
