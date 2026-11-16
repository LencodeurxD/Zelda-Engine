package com.ocarinaengine.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world {@link SavedData} storing each player's Zelda inventory (equipment, quick items, rupees)
 * inside {@code <world>/data/ocarina_engine_inventory.dat} so inventories never leak across worlds.
 */
public final class OcarinaInventoryData extends SavedData {
	private static final String DATA_NAME = "ocarina_engine_inventory";

	private final Map<UUID, ZeldaInventorySnapshot> playerInventories = new HashMap<>();

	public OcarinaInventoryData() {}

	public static Factory<OcarinaInventoryData> factory() {
		return new Factory<>(OcarinaInventoryData::new, OcarinaInventoryData::load, null);
	}

	public static OcarinaInventoryData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
	}

	public ZeldaInventorySnapshot getForPlayer(UUID playerId) {
		return this.playerInventories.getOrDefault(playerId, ZeldaInventorySnapshot.empty());
	}

	public void setForPlayer(UUID playerId, ZeldaInventorySnapshot snapshot) {
		this.playerInventories.put(playerId, snapshot != null ? snapshot : ZeldaInventorySnapshot.empty());
		this.setDirty();
	}

	public static OcarinaInventoryData load(CompoundTag tag, HolderLookup.Provider registries) {
		OcarinaInventoryData data = new OcarinaInventoryData();
		if (tag.contains("Players")) {
			CompoundTag playersTag = tag.getCompound("Players");
			for (String key : playersTag.getAllKeys()) {
				try {
					UUID uuid = UUID.fromString(key);
					ZeldaInventorySnapshot snapshot = ZeldaInventorySnapshot.fromNbt(playersTag.getCompound(key));
					data.playerInventories.put(uuid, snapshot);
				} catch (IllegalArgumentException ignored) {
					// Ignore malformed UUID entries.
				}
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		CompoundTag playersTag = new CompoundTag();
		for (Map.Entry<UUID, ZeldaInventorySnapshot> entry : this.playerInventories.entrySet()) {
			playersTag.put(entry.getKey().toString(), entry.getValue().toNbt());
		}
		tag.put("Players", playersTag);
		return tag;
	}
}
