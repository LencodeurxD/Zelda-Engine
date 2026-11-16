package com.ocarinaengine.client.items;

import com.ocarinaengine.client.hud.QuickItemHud;
import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The list of items owned by the player (shared between the Items tab and the R quick menu)
 * and which one is currently selected.
 * Persisted strictly per-world via server SavedData ({@link ZeldaInventoryClient}) instead of a global config file.
 */
public final class ZeldaQuickItems {
	public static final List<QuickItem> ITEMS = new ArrayList<>();
	private static int selected = 0;
	private static QuickItem activeHeldItem = null;

	private ZeldaQuickItems() {}

	public static boolean hasItems() {
		return !ITEMS.isEmpty();
	}

	public static int size() {
		return ITEMS.size();
	}

	public static int selectedIndex() {
		return ITEMS.isEmpty() ? 0 : Mth.clamp(selected, 0, ITEMS.size() - 1);
	}

	/** Currently selected item, or null if none exist. */
	public static QuickItem current() {
		return ITEMS.isEmpty() ? null : ITEMS.get(selectedIndex());
	}

	/** Returns the item assigned to a given slot (0..21) in the Items tab, or null if empty. */
	public static QuickItem inSlot(int slot) {
		for (QuickItem item : ITEMS) {
			if (item.slot() == slot) {
				return item;
			}
		}
		return null;
	}

	public static boolean isSelected(QuickItem item) {
		return item != null && item == current();
	}

	public static void selectItem(QuickItem item) {
		int idx = ITEMS.indexOf(item);
		if (idx >= 0) {
			selected = idx;
			save();
		}
	}

	public static void select(int index) {
		if (!ITEMS.isEmpty()) {
			selected = Mth.clamp(index, 0, ITEMS.size() - 1);
			save();
		}
	}

	/** Scrolls selection (-1 = previous, +1 = next), without looping. */
	public static void scroll(int delta) {
		select(selectedIndex() + delta);
	}

	/** Grants an item found in a chest into the player's Items inventory, selects it, and saves to the current world. */
	public static QuickItem grantFromChest(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		QuickItem mapped = mapStack(stack);
		for (int i = 0; i < ITEMS.size(); i++) {
			QuickItem existing = ITEMS.get(i);
			if (existing.id().equals(mapped.id())) {
				int newCount = Math.min(existing.maxCount(), existing.count() + mapped.count());
				QuickItem updated = new QuickItem(
						existing.slot(),
						existing.id(),
						existing.name(),
						existing.description(),
						existing.icon(),
						newCount,
						existing.maxCount()
				);
				ITEMS.set(i, updated);
				selected = i;
				save();
				return updated;
			}
		}
		ITEMS.add(mapped);
		selected = ITEMS.size() - 1;
		save();
		return mapped;
	}

	/** Resolves a Minecraft ItemStack into its Zelda QuickItem definition without mutating state. */
	public static QuickItem mapStack(ItemStack stack) {
		int qty = Math.max(1, stack.getCount());
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		String rawName = customName != null ? customName.getString() : stack.getHoverName().getString();

		if (stack.is(Items.STICK) || rawName.equalsIgnoreCase("Deku Stick")) {
			return new QuickItem(
					0,
					"deku_stick",
					"Deku Stick",
					"A light wooden stick found in the forest. Catches fire near flames.",
					Items.STICK,
					Math.min(10, qty),
					10
			);
		}
		if (stack.is(Items.COCOA_BEANS) || rawName.equalsIgnoreCase("Deku Nut")) {
			return new QuickItem(
					3,
					"deku_nut",
					"Deku Nut",
					"A hard-shelled nut. Releases a blinding flash on impact to stun enemies.",
					Items.COCOA_BEANS,
					Math.min(20, qty),
					20
			);
		}

		int freeSlot = firstFreeSlot();
		String id = stack.getItem().toString().toLowerCase().replace(':', '_');
		return new QuickItem(
				freeSlot,
				id,
				rawName,
				"An item discovered inside a treasure chest.",
				stack.getItem(),
				Math.min(99, qty),
				99
		);
	}

	private static int firstFreeSlot() {
		for (int s = 0; s < 22; s++) {
			if (inSlot(s) == null) {
				return s;
			}
		}
		return 0;
	}

	public static void useCurrent() {
		QuickItem item = current();
		if (item != null) {
			QuickItemHud.triggerUseAnimation();
			use(item);
		}
	}

	/** Currently active held quick item (if any), e.g. Deku Stick or Ocarina. */
	public static QuickItem activeItem() {
		return activeHeldItem;
	}

	/** Puts away any currently active held quick item without changing the ZR slot selection. */
	public static boolean cancelActiveItem() {
		if (activeHeldItem != null) {
			activeHeldItem = null;
			return true;
		}
		return false;
	}

	/** What happens when an item is used (ZR). Currently does nothing. */
	public static void use(QuickItem item) {
		// Nothing yet.
	}

	public static void clear() {
		ITEMS.clear();
		selected = 0;
		activeHeldItem = null;
	}

	public static void applySnapshot(int savedSelected, List<ZeldaInventorySnapshot.QuickItemData> entries) {
		clear();
		if (entries != null) {
			for (ZeldaInventorySnapshot.QuickItemData entry : entries) {
				if (entry.id().isEmpty() || entry.name().isEmpty()) {
					continue;
				}
				Item icon = Items.STICK;
				ResourceLocation parsed = ResourceLocation.tryParse(entry.iconId());
				if (parsed != null) {
					Item resolved = BuiltInRegistries.ITEM.get(parsed);
					if (resolved != Items.AIR) {
						icon = resolved;
					}
				}
				int slot = Mth.clamp(entry.slot(), 0, 21);
				int count = Math.max(1, entry.count());
				int maxCount = Math.max(count, entry.maxCount());
				String desc = entry.description().isEmpty()
						? "An item discovered inside a treasure chest."
						: entry.description();
				ITEMS.add(new QuickItem(slot, entry.id(), entry.name(), desc, icon, count, maxCount));
			}
		}
		selected = ITEMS.isEmpty() ? 0 : Mth.clamp(savedSelected, 0, ITEMS.size() - 1);
	}

	public static List<ZeldaInventorySnapshot.QuickItemData> toSnapshotItems() {
		List<ZeldaInventorySnapshot.QuickItemData> list = new ArrayList<>(ITEMS.size());
		for (QuickItem item : ITEMS) {
			list.add(new ZeldaInventorySnapshot.QuickItemData(
					item.slot(),
					item.id(),
					item.name(),
					item.description(),
					BuiltInRegistries.ITEM.getKey(item.icon()).toString(),
					item.count(),
					item.maxCount()
			));
		}
		return list;
	}

	public static void load() {
		clear();
	}

	public static void save() {
		ZeldaInventoryClient.sendSnapshot();
	}
}
