package com.ocarinaengine.client.items;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.rules.ZeldaRules;
import com.ocarinaengine.client.screen.ZeldaQuickMenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * In-game: hold R to open the quick item menu, ZR to use the selected item.
 * Keyboard: R and G keys (rebindable). Gamepad: RB and RT.
 * Completely inactive when the player owns no items (equipment does not count).
 */
public final class QuickItemController {
	private static boolean prevBumper = false;
	private static boolean prevTrigger = false;

	private QuickItemController() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(QuickItemController::tick);
	}

	private static void tick(Minecraft mc) {
		if (!OcarinaGameTypeClient.isZelda() || mc.player == null) {
			prevBumper = false;
			prevTrigger = false;
			return;
		}

		Gamepad.poll();
		boolean bumper = Gamepad.rightBumper && !prevBumper;
		boolean trigger = Gamepad.rightTrigger && !prevTrigger;
		prevBumper = Gamepad.rightBumper;
		prevTrigger = Gamepad.rightTrigger;

		if (!ZeldaRules.quickItems || !ZeldaQuickItems.hasItems() || com.ocarinaengine.client.chest.ZeldaChestCutscene.isActive()) {
			while (ZeldaKeys.quickMenu.consumeClick()) {
				// Ignore quick menu presses when the player has no items or during a cutscene.
			}
			while (ZeldaKeys.useQuickItem.consumeClick()) {
				// Ignore quick item use presses when the player has no items or during a cutscene.
			}
			return;
		}

		if (mc.screen != null) {
			return;
		}

		boolean openByKey = false;
		boolean openByPad = false;
		boolean use = false;
		while (ZeldaKeys.quickMenu.consumeClick()) {
			openByKey = true;
		}
		while (ZeldaKeys.useQuickItem.consumeClick()) {
			use = true;
		}
		if (mc.isWindowActive()) {
			openByPad |= bumper;
			use |= trigger;
		}

		if (openByKey || openByPad) {
			mc.setScreen(new ZeldaQuickMenuScreen(openByPad && !openByKey));
		} else if (use) {
			ZeldaQuickItems.useCurrent();
		}
	}
}
