package com.ocarinaengine.client.mixin;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.gametype.OcarinaGameType;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "Game Type: Zelda / Minecraft" CycleButton directly to the Game tab
 * of CreateWorldScreen beneath the Allow Commands toggle (row 4).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class CreateWorldScreenGameTabMixin extends GridLayoutTab {
	protected CreateWorldScreenGameTabMixin(Component title) {
		super(title);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ocarina$addGameTypeButton(CallbackInfo ci) {
		CycleButton<OcarinaGameType> gameTypeButton = CycleButton.<OcarinaGameType>builder(OcarinaGameType::displayComponent)
				.withValues(OcarinaGameType.values())
				.withInitialValue(OcarinaGameTypeClient.getCreateScreenSelection())
				.withTooltip(value -> Tooltip.create(Component.translatable(
						value.isZelda()
								? "ocarina_engine.create_world.gametype.info.zelda"
								: "ocarina_engine.create_world.gametype.info.minecraft"
				)))
				.create(
						0,
						0,
						210,
						20,
						Component.translatable("ocarina_engine.create_world.gametype"),
						(button, value) -> OcarinaGameTypeClient.setCreateScreenSelection(value)
				);
		// Rows 0..3 are populated by vanilla GameTab (Name, Game Mode, Difficulty, Allow Commands).
		this.layout.addChild(gameTypeButton, 4, 0);
	}
}
