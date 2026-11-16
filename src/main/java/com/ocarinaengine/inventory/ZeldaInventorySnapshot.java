package com.ocarinaengine.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Immutable per-world, per-player Zelda inventory snapshot shared between common server persistence
 * ({@link OcarinaInventoryData}) and client equipment/quick-item managers.
 */
public record ZeldaInventorySnapshot(
		int rupees,
		List<String> ownedEquipmentIds,
		Map<String, String> equippedSlots,
		int selectedQuickItem,
		List<QuickItemData> quickItems
) {
	private static final int MAX_ENTRIES = 64;

	public record QuickItemData(
			int slot,
			String id,
			String name,
			String description,
			String iconId,
			int count,
			int maxCount
	) {}

	public ZeldaInventorySnapshot {
		rupees = Math.max(0, rupees);
		ownedEquipmentIds = List.copyOf(ownedEquipmentIds);
		equippedSlots = Map.copyOf(equippedSlots);
		selectedQuickItem = Math.max(0, selectedQuickItem);
		quickItems = List.copyOf(quickItems);
	}

	public static ZeldaInventorySnapshot empty() {
		return new ZeldaInventorySnapshot(0, List.of(), Map.of(), 0, List.of());
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(this.rupees);

		int ownedCount = Math.min(MAX_ENTRIES, this.ownedEquipmentIds.size());
		buf.writeVarInt(ownedCount);
		for (int i = 0; i < ownedCount; i++) {
			buf.writeUtf(this.ownedEquipmentIds.get(i), 128);
		}

		int equippedCount = Math.min(MAX_ENTRIES, this.equippedSlots.size());
		buf.writeVarInt(equippedCount);
		int writtenEq = 0;
		for (Map.Entry<String, String> entry : this.equippedSlots.entrySet()) {
			if (writtenEq >= equippedCount) {
				break;
			}
			buf.writeUtf(entry.getKey(), 64);
			buf.writeUtf(entry.getValue(), 128);
			writtenEq++;
		}

		buf.writeVarInt(this.selectedQuickItem);
		int itemCount = Math.min(MAX_ENTRIES, this.quickItems.size());
		buf.writeVarInt(itemCount);
		for (int i = 0; i < itemCount; i++) {
			QuickItemData item = this.quickItems.get(i);
			buf.writeVarInt(item.slot());
			buf.writeUtf(item.id(), 128);
			buf.writeUtf(item.name(), 256);
			buf.writeUtf(item.description(), 512);
			buf.writeUtf(item.iconId(), 256);
			buf.writeVarInt(item.count());
			buf.writeVarInt(item.maxCount());
		}
	}

	public static ZeldaInventorySnapshot read(RegistryFriendlyByteBuf buf) {
		int rupees = Math.max(0, buf.readVarInt());

		int ownedCount = Math.clamp(buf.readVarInt(), 0, MAX_ENTRIES);
		List<String> owned = new ArrayList<>(ownedCount);
		for (int i = 0; i < ownedCount; i++) {
			String id = buf.readUtf(128);
			if (!id.isEmpty() && !owned.contains(id)) {
				owned.add(id);
			}
		}

		int equippedCount = Math.clamp(buf.readVarInt(), 0, MAX_ENTRIES);
		Map<String, String> equipped = new LinkedHashMap<>();
		for (int i = 0; i < equippedCount; i++) {
			String slotName = buf.readUtf(64);
			String itemId = buf.readUtf(128);
			if (!slotName.isEmpty() && !itemId.isEmpty()) {
				equipped.put(slotName, itemId);
			}
		}

		int selected = Math.max(0, buf.readVarInt());
		int itemCount = Math.clamp(buf.readVarInt(), 0, MAX_ENTRIES);
		List<QuickItemData> items = new ArrayList<>(itemCount);
		for (int i = 0; i < itemCount; i++) {
			int slot = Math.clamp(buf.readVarInt(), 0, 21);
			String id = buf.readUtf(128);
			String name = buf.readUtf(256);
			String description = buf.readUtf(512);
			String iconId = buf.readUtf(256);
			int count = Math.max(1, buf.readVarInt());
			int maxCount = Math.max(count, buf.readVarInt());
			if (!id.isEmpty() && !name.isEmpty()) {
				items.add(new QuickItemData(slot, id, name, description, iconId, count, maxCount));
			}
		}

		int safeSelected = items.isEmpty() ? 0 : Math.clamp(selected, 0, items.size() - 1);
		return new ZeldaInventorySnapshot(rupees, owned, equipped, safeSelected, items);
	}

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Rupees", this.rupees);

		ListTag ownedList = new ListTag();
		for (String id : this.ownedEquipmentIds) {
			ownedList.add(StringTag.valueOf(id));
		}
		tag.put("OwnedEquipment", ownedList);

		CompoundTag equippedTag = new CompoundTag();
		for (Map.Entry<String, String> entry : this.equippedSlots.entrySet()) {
			equippedTag.putString(entry.getKey(), entry.getValue());
		}
		tag.put("Equipped", equippedTag);

		tag.putInt("SelectedQuickItem", this.selectedQuickItem);
		ListTag itemsList = new ListTag();
		for (QuickItemData item : this.quickItems) {
			CompoundTag itemTag = new CompoundTag();
			itemTag.putInt("Slot", item.slot());
			itemTag.putString("Id", item.id());
			itemTag.putString("Name", item.name());
			itemTag.putString("Description", item.description());
			itemTag.putString("Icon", item.iconId());
			itemTag.putInt("Count", item.count());
			itemTag.putInt("MaxCount", item.maxCount());
			itemsList.add(itemTag);
		}
		tag.put("QuickItems", itemsList);
		return tag;
	}

	public static ZeldaInventorySnapshot fromNbt(CompoundTag tag) {
		int rupees = Math.max(0, tag.getInt("Rupees"));

		List<String> owned = new ArrayList<>();
		if (tag.contains("OwnedEquipment", Tag.TAG_LIST)) {
			ListTag ownedList = tag.getList("OwnedEquipment", Tag.TAG_STRING);
			for (int i = 0; i < ownedList.size() && i < MAX_ENTRIES; i++) {
				String id = ownedList.getString(i).trim();
				if (!id.isEmpty() && !owned.contains(id)) {
					owned.add(id);
				}
			}
		}

		Map<String, String> equipped = new LinkedHashMap<>();
		if (tag.contains("Equipped", Tag.TAG_COMPOUND)) {
			CompoundTag equippedTag = tag.getCompound("Equipped");
			for (String slotKey : equippedTag.getAllKeys()) {
				String itemId = equippedTag.getString(slotKey).trim();
				if (!itemId.isEmpty()) {
					equipped.put(slotKey, itemId);
				}
			}
		}

		int selected = Math.max(0, tag.getInt("SelectedQuickItem"));
		List<QuickItemData> items = new ArrayList<>();
		if (tag.contains("QuickItems", Tag.TAG_LIST)) {
			ListTag itemsList = tag.getList("QuickItems", Tag.TAG_COMPOUND);
			for (int i = 0; i < itemsList.size() && i < MAX_ENTRIES; i++) {
				CompoundTag itemTag = itemsList.getCompound(i);
				int slot = Math.clamp(itemTag.getInt("Slot"), 0, 21);
				String id = itemTag.getString("Id").trim();
				String name = itemTag.getString("Name").trim();
				String description = itemTag.getString("Description");
				String iconId = itemTag.getString("Icon");
				int count = Math.max(1, itemTag.getInt("Count"));
				int maxCount = Math.max(count, itemTag.getInt("MaxCount"));
				if (!id.isEmpty() && !name.isEmpty()) {
					items.add(new QuickItemData(slot, id, name, description, iconId, count, maxCount));
				}
			}
		}

		int safeSelected = items.isEmpty() ? 0 : Math.clamp(selected, 0, items.size() - 1);
		return new ZeldaInventorySnapshot(rupees, owned, equipped, safeSelected, items);
	}
}
