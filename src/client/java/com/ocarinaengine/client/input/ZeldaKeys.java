package com.ocarinaengine.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Engine keybindings (configurable in Options > Controls). Gamepad: ZL (LT), RB, and RT. */
public final class ZeldaKeys {
	private static final String CATEGORY = "key.categories.ocarina_engine";

	/** R button: hold to open the quick items menu (pauses the game). */
	public static KeyMapping quickMenu;
	/** ZR button: uses the selected item. */
	public static KeyMapping useQuickItem;
	/** Z / ZL button: Z-Target lock onto enemies or center the camera behind Link. */
	public static KeyMapping targetLock;

	private ZeldaKeys() {}

	public static void register() {
		quickMenu = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.ocarina_engine.quick_menu", GLFW.GLFW_KEY_R, CATEGORY));
		useQuickItem = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.ocarina_engine.use_quick_item", GLFW.GLFW_KEY_G, CATEGORY));
		targetLock = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.ocarina_engine.target_lock", GLFW.GLFW_KEY_Z, CATEGORY));
	}

	/** True while the quick menu key/mouse button is physically held down. */
	public static boolean isQuickMenuHeld() {
		return isBindingHeld(quickMenu);
	}

	/** True while Z-Target lock key/mouse button is physically held down. */
	public static boolean isTargetLockHeld() {
		return isBindingHeld(targetLock);
	}

	private static boolean isBindingHeld(KeyMapping mapping) {
		if (mapping == null) {
			return false;
		}
		InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
		long window = Minecraft.getInstance().getWindow().getWindow();
		if (bound.getType() == InputConstants.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
		}
		if (bound.getType() == InputConstants.Type.KEYSYM) {
			return InputConstants.isKeyDown(window, bound.getValue());
		}
		return false;
	}

	/** Label shown on screen: "R" / "ZR" / "ZL" on gamepad, otherwise the bound key name. */
	public static String label(KeyMapping key, String padName) {
		return Gamepad.connected ? padName : key.getTranslatedKeyMessage().getString().toUpperCase();
	}
}