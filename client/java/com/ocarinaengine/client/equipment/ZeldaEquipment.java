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
 * Swords (row 1) and Shields (row 2) equip independently so Link can wield both together like in OoT.
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

	public static final ZeldaItem DEKU_SHIELD = new ZeldaItem(
			"deku_shield",
			EquipSlot.SHIELD,
			"Deku Shield",
			"A light Kokiri wooden shield. Hold Guard to block frontal hits and projectiles, but beware of fire!",
			Items.SHIELD
	);

	public static final ZeldaItem HYLIAN_SHIELD = new ZeldaItem(
			"hylian_shield",
			EquipSlot.SHIELD,
			"Hylian Shield",
			"A sturdy fireproof steel kite shield. Hold Guard while stationary to crouch-turtle under it.",
			Items.SHIELD
	);

	public static final ZeldaItem MIRROR_SHIELD = new ZeldaItem(
			"mirror_shield",
			EquipSlot.SHIELD,
			"Mirror Shield",
			"A sacred Gerudo mirror shield. Fireproof and reflects magic beams & fireballs back at foes.",
			Items.SHIELD
	);

	private static final Map<String, ZeldaItem> CATALOG = new LinkedHashMap<>();
	static {
		registerCatalog(WOODEN_SWORD);
		registerCatalog(DEKU_SHIELD);
		registerCatalog(HYLIAN_SHIELD);
		registerCatalog(MIRROR_SHIELD);
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
		if (rawName.equalsIgnoreCase("Deku Shield")
				|| rawName.equalsIgnoreCase("Kokiri Shield")
				|| rawName.equalsIgnoreCase("Bouclier Mojo")) {
			return DEKU_SHIELD;
		}
		if (rawName.equalsIgnoreCase("Mirror Shield")
				|| rawName.equalsIgnoreCase("Bouclier Miroir")) {
			return MIRROR_SHIELD;
		}
		if (stack.is(Items.SHIELD)
				|| rawName.equalsIgnoreCase("Hylian Shield")
				|| rawName.equalsIgnoreCase("Bouclier Hylien")) {
			return HYLIAN_SHIELD;
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

	public static ZeldaItem equippedShield() {
		return equipped(EquipSlot.SHIELD);
	}

	public static boolean hasEquippedShield() {
		return equippedShield() != null;
	}

	public static String equippedShieldId() {
		ZeldaItem shield = equippedShield();
		return shield != null ? shield.id() : "";
	}

	public static boolean isDekuShieldEquipped() {
		ZeldaItem shield = equippedShield();
		return shield != null && shield.id().equals(DEKU_SHIELD.id());
	}

	public static boolean isHylianShieldEquipped() {
		ZeldaItem shield = equippedShield();
		return shield != null && shield.id().equals(HYLIAN_SHIELD.id());
	}

	public static boolean isMirrorShieldEquipped() {
		ZeldaItem shield = equippedShield();
		return shield != null && shield.id().equals(MIRROR_SHIELD.id());
	}

	/** OoT rule: fire damage burns up an equipped Deku Shield and removes it from inventory. */
	public static void burnDekuShield() {
		OWNED.removeIf(item -> item.id().equals(DEKU_SHIELD.id()));
		if (DEKU_SHIELD.id().equals(EQUIPPED.get(EquipSlot.SHIELD))) {
			EQUIPPED.remove(EquipSlot.SHIELD);
		}
		save();
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
