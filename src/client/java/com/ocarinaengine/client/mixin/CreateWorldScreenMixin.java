package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the player's Game Type choice ("Zelda" vs "Minecraft") across CreateWorldScreen
 * lifecycle events and arms the selection when world generation begins.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
	@Unique
	private boolean ocarina$initializedChoice = false;

	@Inject(method = "init()V", at = @At("HEAD"))
	private void ocarina$resetGameTypeOnce(CallbackInfo ci) {
		if (!this.ocarina$initializedChoice) {
			this.ocarina$initializedChoice = true;
			OcarinaGameTypeClient.resetCreateScreenSelection();
		}
	}

	@Inject(method = "onCreate()V", at = @At("HEAD"))
	private void ocarina$armGameTypeOnCreate(CallbackInfo ci) {
		OcarinaGameTypeClient.armNewWorldCreation();
	}

	@Inject(method = "popScreen()V", at = @At("HEAD"), require = 0)
	private void ocarina$clearOnCancel(CallbackInfo ci) {
		OcarinaGameTypeClient.clearPendingNewWorld();
	}
}
