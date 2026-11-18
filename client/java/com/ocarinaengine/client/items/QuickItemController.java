package com.ocarinaengine.client.items;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.rules.ZeldaRules;
import com.ocarinaengine.client.screen.ZeldaQuickMenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * In-game: hold R to open the quick item menu, ZR to use the selected item (or hold ZR to aim Slingshot).
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
			ZeldaQuickItems.cancelSlingshotAim();
			return;
		}
		Gamepad.poll();
		boolean bumper = Gamepad.rightBumper && !prevBumper;
		boolean trigger = Gamepad.rightTrigger && !prevTrigger;
		prevBumper = Gamepad.rightBumper;
		prevTrigger = Gamepad.rightTrigger;

		if (!ZeldaRules.quickItems || !ZeldaQuickItems.hasItems() || com.ocarinaengine.client.chest.ZeldaChestCutscene.isActive()) {
			ZeldaQuickItems.cancelSlingshotAim();
			while (ZeldaKeys.quickMenu.consumeClick()) {}
			while (ZeldaKeys.useQuickItem.consumeClick()) {}
			return;
		}
		if (mc.screen != null) {
			ZeldaQuickItems.cancelSlingshotAim();
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
			ZeldaQuickItems.cancelSlingshotAim();
			mc.setScreen(new ZeldaQuickMenuScreen(openByPad && !openByKey));
			return;
		}

		QuickItem current = ZeldaQuickItems.current();
		if (use) {
			if (current != null && "fairy_slingshot".equals(current.id())) {
				ZeldaQuickItems.beginSlingshotAim();
			} else {
				ZeldaQuickItems.useCurrent();
			}
		}

		if (ZeldaQuickItems.isAimingSlingshot()) {
			boolean held = mc.isWindowActive() && ZeldaKeys.isUseQuickItemHeld();
			ZeldaQuickItems.tickSlingshotAim(mc, held);
		}
	}
}