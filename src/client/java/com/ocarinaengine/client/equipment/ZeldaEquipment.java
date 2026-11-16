package com.ocarinaengine.client.equipment;

import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Equipment owned and currently equipped by the player (Swords, Shields, Outfits, Masks, Boots).
 * Persisted strictly per-world via server SavedData ({@link ZeldaInventoryClient}) instead of a global config file.
 */
public final class ZeldaEquipment {
	public static final int MAX_RUPEES = 200;
	public static int rupees = 0;

	public static final ZeldaItem WOODEN_SWORD = new ZeldaItem(
			"wooden_sword",
			EquipSlot.SWORD,
			"Wooden Sword",
			"A well-balanced wooden blade. Swing with Attack or hold to charge a Spin Attack.",
			Items.WOODEN_SWORD
	);

	private static final Map<String, ZeldaItem> CATALOG = new LinkedHashMap<>();
	static {
		registerCatalog(WOODEN_SWORD);
	}

	public static final List<ZeldaItem> OWNED = new ArrayList<>();
	private static final Map<EquipSlot, String> EQUIPPED = new EnumMap<>(EquipSlot.class);

	private ZeldaEquipment() {}

	private static void registerCatalog(ZeldaItem item) {
		CATALOG.put(item.id(), item);
	}

	public static boolean isEquipmentStack(ItemStack stack) {
		return mapEquipmentStack(stack) != null;
	}

	public static ZeldaItem previewEquipment(ItemStack stack) {
		return mapEquipmentStack(stack);
	}

	/** Maps a treasure-chest ItemStack to its Zelda equipment item, or null if it is a Quick Item. */
	public static ZeldaItem mapEquipmentStack(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		String rawName = customName != null ? customName.getString() : stack.getHoverName().getString();
		if (stack.is(Items.WOODEN_SWORD)
				|| rawName.equalsIgnoreCase("Wooden Sword")
				|| rawName.equalsIgnoreCase("Kokiri Sword")) {
			return WOODEN_SWORD;
		}
		return null;
	}

	/** Grants an equipment item found inside a chest, auto-equips if slot is empty, and saves to the current world. */
	public static ZeldaItem grantFromChest(ItemStack stack) {
		ZeldaItem item = mapEquipmentStack(stack);
		if (item == null) {
			return null;
		}
		boolean alreadyOwned = false;
		for (ZeldaItem owned : OWNED) {
			if (owned.id().equals(item.id())) {
				alreadyOwned = true;
				break;
			}
		}
		if (!alreadyOwned) {
			OWNED.add(item);
		}
		if (equipped(item.slot()) == null) {
			EQUIPPED.put(item.slot(), item.id());
		}
		save();
		return item;
	}

	/** Owned items for a given slot, in order. */
	public static List<ZeldaItem> owned(EquipSlot slot) {
		List<ZeldaItem> list = new ArrayList<>();
		for (ZeldaItem item : OWNED) {
			if (item.slot() == slot) {
				list.add(item);
			}
		}
		return list;
	}

	public static boolean isEquipped(ZeldaItem item) {
		return item.id().equals(EQUIPPED.get(item.slot()));
	}

	public static ZeldaItem equipped(EquipSlot slot) {
		String id = EQUIPPED.get(slot);
		if (id == null) {
			return null;
		}
		for (ZeldaItem item : OWNED) {
			if (item.id().equals(id)) {
				return item;
			}
		}
		return null;
	}

	public static ZeldaItem equippedSword() {
		return equipped(EquipSlot.SWORD);
	}

	public static boolean hasEquippedSword() {
		return equippedSword() != null;
	}

	/** Equips the item, or removes it if already equipped (an outfit always stays equipped). */
	public static void toggle(ZeldaItem item) {
		if (isEquipped(item)) {
			if (item.slot() == EquipSlot.OUTFIT) {
				return;
			}
			EQUIPPED.remove(item.slot());
		} else {
			EQUIPPED.put(item.slot(), item.id());
		}
		save();
	}

	public static void clear() {
		rupees = 0;
		OWNED.clear();
		EQUIPPED.clear();
	}

	public static void applySnapshot(int savedRupees, List<String> ownedIds, Map<String, String> equippedMap) {
		clear();
		rupees = Mth.clamp(savedRupees, 0, MAX_RUPEES);
		if (ownedIds != null) {
			for (String rawId : ownedIds) {
				if (rawId == null) {
					continue;
				}
				ZeldaItem catalogItem = CATALOG.get(rawId.trim());
				if (catalogItem != null && !OWNED.contains(catalogItem)) {
					OWNED.add(catalogItem);
				}
			}
		}
		if (equippedMap != null) {
			for (EquipSlot slot : EquipSlot.values()) {
				String id = equippedMap.get(slot.name());
				if (id == null || id.isEmpty()) {
					continue;
				}
				for (ZeldaItem item : OWNED) {
					if (item.slot() == slot && item.id().equals(id)) {
						EQUIPPED.put(slot, id);
						break;
					}
				}
			}
		}
	}

	public static List<String> toOwnedIds() {
		List<String> ids = new ArrayList<>(OWNED.size());
		for (ZeldaItem item : OWNED) {
			ids.add(item.id());
		}
		return ids;
	}

	public static Map<String, String> toEquippedMap() {
		Map<String, String> map = new LinkedHashMap<>();
		for (EquipSlot slot : EquipSlot.values()) {
			String id = EQUIPPED.get(slot);
			if (id != null && !id.isEmpty()) {
				map.put(slot.name(), id);
			}
		}
		return map;
	}

	public static void load() {
		clear();
	}

	public static void save() {
		ZeldaInventoryClient.sendSnapshot();
	}
}
