package com.ocarinaengine.client.equipment;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An equippable item in the Equipment menu.
 * "icon" is a Minecraft item used as a placeholder image until real Zelda icons are added.
 */
public record ZeldaItem(String id, EquipSlot slot, String name, String description, Item icon) {
	public ItemStack stack() {
		return new ItemStack(icon);
	}
}