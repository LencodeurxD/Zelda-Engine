package com.ocarinaengine.gametype;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists the world's chosen default game type (Zelda vs. Minecraft)
 * and per-player overrides changed in-game via /Gametype.
 */
public final class OcarinaWorldData extends SavedData {
	private static final String DATA_NAME = "ocarina_engine_gametype";

	private OcarinaGameType worldDefault = OcarinaGameType.ZELDA;
	private boolean worldDefaultExplicit = false;
	private final Map<UUID, OcarinaGameType> playerOverrides = new HashMap<>();

	public OcarinaWorldData() {}

	public static Factory<OcarinaWorldData> factory() {
		return new Factory<>(OcarinaWorldData::new, OcarinaWorldData::load, null);
	}

	public static OcarinaWorldData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
	}

	public OcarinaGameType getWorldDefault() {
		return this.worldDefault;
	}

	public boolean isWorldDefaultExplicit() {
		return this.worldDefaultExplicit;
	}

	public void setWorldDefault(OcarinaGameType type) {
		this.worldDefault = type;
		this.worldDefaultExplicit = true;
		this.setDirty();
	}

	public OcarinaGameType getForPlayer(UUID playerId) {
		return this.playerOverrides.getOrDefault(playerId, this.worldDefault);
	}

	public void setForPlayer(UUID playerId, OcarinaGameType type) {
		this.playerOverrides.put(playerId, type);
		this.worldDefaultExplicit = true;
		this.setDirty();
	}

	public static OcarinaWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
		OcarinaWorldData data = new OcarinaWorldData();
		if (tag.contains("WorldDefault")) {
			OcarinaGameType parsed = OcarinaGameType.parse(tag.getString("WorldDefault"));
			if (parsed != null) {
				data.worldDefault = parsed;
			}
		}
		data.worldDefaultExplicit = tag.getBoolean("WorldDefaultExplicit");

		if (tag.contains("Players")) {
			CompoundTag playersTag = tag.getCompound("Players");
			for (String key : playersTag.getAllKeys()) {
				try {
					UUID uuid = UUID.fromString(key);
					OcarinaGameType parsed = OcarinaGameType.parse(playersTag.getString(key));
					if (parsed != null) {
						data.playerOverrides.put(uuid, parsed);
					}
				} catch (IllegalArgumentException ignored) {
					// Ignore malformed legacy UUID entries.
				}
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putString("WorldDefault", this.worldDefault.id());
		tag.putBoolean("WorldDefaultExplicit", this.worldDefaultExplicit);

		CompoundTag playersTag = new CompoundTag();
		for (Map.Entry<UUID, OcarinaGameType> entry : this.playerOverrides.entrySet()) {
			playersTag.putString(entry.getKey().toString(), entry.getValue().id());
		}
		tag.put("Players", playersTag);
		return tag;
	}
}
