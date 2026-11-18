package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mouse no longer rotates the player: it rotates the camera instead.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void ocarina$mouseMovesCamera(double yRot, double xRot, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && EngineState.isActive()) {
			if (!ZeldaChestCutscene.isActive() && !ZeldaTargetingSystem.isFocusActive()) {
				EngineState.addCameraRotation((float) yRot * 0.15f, (float) xRot * 0.15f);
			}
			ci.cancel();
		}
	}
}
