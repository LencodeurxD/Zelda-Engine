package com.ocarinaengine.inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world {@link SavedData} storing each player's Zelda inventory (equipment, quick items, rupees)
 * inside {@code <world>/data/ocarina_engine_inventory.dat} so inventories persist per world and never leak across worlds.
 * In pure singleplayer, also maintains a world-local singleplayer fallback snapshot so offline dev UUID rotations
 * (e.g. {@code ./gradlew runClient}) still restore the world's inventory cleanly.
 */
public final class OcarinaInventoryData extends SavedData {
	private static final String DATA_NAME = "ocarina_engine_inventory";

	private static volatile MinecraftServer activeServer = null;
	private static volatile OcarinaInventoryData activeData = null;

	private final Map<UUID, ZeldaInventorySnapshot> playerInventories = new ConcurrentHashMap<>();
	private volatile ZeldaInventorySnapshot singleplayerSnapshot = null;

	public OcarinaInventoryData() {}

	public static Factory<OcarinaInventoryData> factory() {
		return new Factory<>(OcarinaInventoryData::new, OcarinaInventoryData::load, null);
	}

	public static OcarinaInventoryData get(MinecraftServer server) {
		OcarinaInventoryData data = server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
		activeServer = server;
		activeData = data;
		return data;
	}

	/**
	 * Thread-safe helper for singleplayer disconnect/pause-screen flushes when the integrated server's
	 * {@link OcarinaInventoryData} instance is already loaded for {@code server}.
	 */
	public static void flushSingleplayerImmediate(MinecraftServer server, UUID playerId, ZeldaInventorySnapshot snapshot) {
		if (server == null || playerId == null || snapshot == null) {
			return;
		}
		if (activeServer == server && activeData != null) {
			activeData.setForPlayer(server, playerId, snapshot);
		}
	}

	public static void clearActiveServer(MinecraftServer server) {
		if (activeServer == server) {
			activeServer = null;
			activeData = null;
		}
	}

	public ZeldaInventorySnapshot getForPlayer(UUID playerId) {
		return getForPlayer(null, playerId);
	}

	public ZeldaInventorySnapshot getForPlayer(MinecraftServer server, UUID playerId) {
		ZeldaInventorySnapshot direct = this.playerInventories.get(playerId);
		if (direct != null) {
			return direct;
		}
		if (server != null && server.isSingleplayer() && !server.isPublished()) {
			ZeldaInventorySnapshot fallback = this.singleplayerSnapshot;
			if (fallback == null && this.playerInventories.size() == 1) {
				fallback = this.playerInventories.values().iterator().next();
				this.singleplayerSnapshot = fallback;
			}
			if (fallback != null) {
				this.playerInventories.put(playerId, fallback);
				this.setDirty();
				return fallback;
			}
		}
		return ZeldaInventorySnapshot.empty();
	}

	public void setForPlayer(UUID playerId, ZeldaInventorySnapshot snapshot) {
		setForPlayer(null, playerId, snapshot);
	}

	public void setForPlayer(MinecraftServer server, UUID playerId, ZeldaInventorySnapshot snapshot) {
		ZeldaInventorySnapshot safe = snapshot != null ? snapshot : ZeldaInventorySnapshot.empty();
		this.playerInventories.put(playerId, safe);
		if (server == null || (server.isSingleplayer() && !server.isPublished())) {
			this.singleplayerSnapshot = safe;
		}
		this.setDirty();
	}

	public static OcarinaInventoryData load(CompoundTag tag, HolderLookup.Provider registries) {
		OcarinaInventoryData data = new OcarinaInventoryData();
		if (tag.contains("Players", Tag.TAG_COMPOUND)) {
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
		if (tag.contains("SingleplayerSnapshot", Tag.TAG_COMPOUND)) {
			data.singleplayerSnapshot = ZeldaInventorySnapshot.fromNbt(tag.getCompound("SingleplayerSnapshot"));
		} else if (data.playerInventories.size() == 1) {
			data.singleplayerSnapshot = data.playerInventories.values().iterator().next();
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
		if (this.singleplayerSnapshot != null) {
			tag.put("SingleplayerSnapshot", this.singleplayerSnapshot.toNbt());
		}
		return tag;
	}
}
