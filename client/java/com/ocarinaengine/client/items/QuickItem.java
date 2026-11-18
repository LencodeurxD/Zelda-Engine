package com.ocarinaengine.client.items;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An item shared between the Items tab and the R quick menu.
 * "icon" is a Minecraft item used as a placeholder image until real Zelda icons are added.
 */
public record QuickItem(int slot, String id, String name, String description, Item icon, int count, int maxCount) {
	public ItemStack stack() {
		return new ItemStack(icon);
	}
}