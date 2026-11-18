package com.ocarinaengine.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ocarinaengine.OcarinaEngine;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Replaces vanilla placeholder ItemStack icons (Items.STICK, Items.COCOA_BEANS...)
 * with crisp 16x16 Zelda pixel-art textures from assets/ocarina_engine/textures/item/.
 */
public final class ZeldaItemIcons {
	private ZeldaItemIcons() {}

	public static ResourceLocation iconTextureFor(String itemId) {
		if ("deku_stick".equals(itemId) && ZeldaQuickItems.isStickLit()) {
			return OcarinaEngine.id("textures/item/deku_stick_lit.png");
		}
		return switch (itemId) {
			case "deku_stick", "deku_stick_lit", "deku_nut",
				 "fairy_slingshot", "bomb", "fairy_ocarina", "kokiri_sword" ->
					OcarinaEngine.id("textures/item/" + itemId + ".png");
			case "wooden_sword" -> OcarinaEngine.id("textures/item/kokiri_sword.png");
			default -> null;
		};
	}

	public static void draw16(GuiGraphics g, String itemId, ItemStack fallbackStack, int x, int y) {
		draw16(g, itemId, fallbackStack, x, y, 1.0f);
	}

	public static void draw16(GuiGraphics g, String itemId, ItemStack fallbackStack, int x, int y, float alpha) {
		ResourceLocation tex = iconTextureFor(itemId);
		if (tex != null) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			g.setColor(1.0f, 1.0f, 1.0f, alpha);
			g.blit(tex, x, y, 0.0f, 0.0f, 16, 16, 16, 16);
			g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
			RenderSystem.disableBlend();
		} else if (fallbackStack != null && !fallbackStack.isEmpty()) {
			g.renderItem(fallbackStack, x, y);
		}
	}
}