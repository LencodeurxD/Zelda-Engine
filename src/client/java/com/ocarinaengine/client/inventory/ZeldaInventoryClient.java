package com.ocarinaengine.client.inventory;

import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.inventory.OcarinaInventoryData;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import com.ocarinaengine.network.RequestZeldaInventoryPayload;
import com.ocarinaengine.network.SaveZeldaInventoryPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Coordinates per-world synchronization of {@link ZeldaEquipment} and {@link ZeldaQuickItems}
 * with the active server world, preventing inventory state from leaking across worlds while
 * guaranteeing changes made during pause screens or right before disconnect are never lost.
 */
public final class ZeldaInventoryClient {
	private static boolean syncedForCurrentWorld = false;
	private static boolean dirtyWhileUnsynced = false;
	private static int syncRetryTicks = 0;
	private static UUID activePlayerUuid = null;

	private ZeldaInventoryClient() {}

	public static boolean isSynced() {
		return syncedForCurrentWorld;
	}

	/** Called during ClientPlayConnectionEvents.JOIN to track the active player and ensure sync arrives. */
	public static void onClientJoin(Minecraft mc) {
		if (mc != null && mc.player != null) {
			activePlayerUuid = mc.player.getUUID();
		}
		syncRetryTicks = 0;
		if (!syncedForCurrentWorld && mc != null && mc.getConnection() != null) {
			ClientPlayNetworking.send(new RequestZeldaInventoryPayload());
		}
	}

	/** Periodically retries requesting sync on client tick if the initial join packet has not arrived yet. */
	public static void tickEnsureSynced(Minecraft mc) {
		if (mc == null || mc.player == null || mc.level == null || mc.getConnection() == null) {
			return;
		}
		activePlayerUuid = mc.player.getUUID();
		if (syncedForCurrentWorld) {
			return;
		}
		syncRetryTicks++;
		if (syncRetryTicks == 1 || (syncRetryTicks <= 100 && syncRetryTicks % 20 == 0)) {
			ClientPlayNetworking.send(new RequestZeldaInventoryPayload());
		}
	}

	/** Applies the authoritative inventory snapshot sent by the server when joining a world. */
	public static void applyServerSnapshot(ZeldaInventorySnapshot snapshot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			activePlayerUuid = mc.player.getUUID();
		}
		if (snapshot == null) {
			reset();
			return;
		}
		if (dirtyWhileUnsynced) {
			ZeldaInventorySnapshot merged = mergeWithLocalState(snapshot);
			ZeldaEquipment.applySnapshot(merged.rupees(), merged.ownedEquipmentIds(), merged.equippedSlots());
			ZeldaQuickItems.applySnapshot(merged.selectedQuickItem(), merged.quickItems());
			syncedForCurrentWorld = true;
			dirtyWhileUnsynced = false;
			sendSnapshot();
			return;
		}
		ZeldaEquipment.applySnapshot(snapshot.rupees(), snapshot.ownedEquipmentIds(), snapshot.equippedSlots());
		ZeldaQuickItems.applySnapshot(snapshot.selectedQuickItem(), snapshot.quickItems());
		syncedForCurrentWorld = true;
	}

	/** Pushes the player's updated equipment, quick items, and rupees to the active world's server save. */
	public static void sendSnapshot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			activePlayerUuid = mc.player.getUUID();
		}
		if (!syncedForCurrentWorld) {
			dirtyWhileUnsynced = true;
			if (mc != null && mc.getConnection() != null) {
				ClientPlayNetworking.send(new RequestZeldaInventoryPayload());
			}
			return;
		}
		if (mc == null || mc.player == null) {
			return;
		}
		ZeldaInventorySnapshot snapshot = captureSnapshot();
		if (mc.getConnection() != null) {
			ClientPlayNetworking.send(new SaveZeldaInventoryPayload(snapshot));
		}
		MinecraftServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			UUID playerId = mc.player.getUUID();
			OcarinaInventoryData.flushSingleplayerImmediate(sp, playerId, snapshot);
			sp.execute(() -> OcarinaInventoryData.get(sp).setForPlayer(sp, playerId, snapshot));
		}
	}

	/** Flushes any latest or pending inventory state to the singleplayer server before reset on disconnect. */
	public static void flushOnDisconnect(Minecraft mc) {
		if (!syncedForCurrentWorld && !dirtyWhileUnsynced) {
			return;
		}
		if (mc == null) {
			return;
		}
		UUID playerId = mc.player != null ? mc.player.getUUID() : activePlayerUuid;
		ZeldaInventorySnapshot snapshot = captureSnapshot();
		if (mc.getConnection() != null && mc.player != null) {
			ClientPlayNetworking.send(new SaveZeldaInventoryPayload(snapshot));
		}
		MinecraftServer sp = mc.getSingleplayerServer();
		if (sp != null && playerId != null) {
			OcarinaInventoryData.flushSingleplayerImmediate(sp, playerId, snapshot);
		}
	}

	/** Clears all in-memory Zelda equipment, quick items, and rupees when disconnecting from a world. */
	public static void reset() {
		syncedForCurrentWorld = false;
		dirtyWhileUnsynced = false;
		syncRetryTicks = 0;
		activePlayerUuid = null;
		ZeldaEquipment.clear();
		ZeldaQuickItems.clear();
	}

	private static ZeldaInventorySnapshot captureSnapshot() {
		return new ZeldaInventorySnapshot(
				ZeldaEquipment.rupees,
				ZeldaEquipment.toOwnedIds(),
				ZeldaEquipment.toEquippedMap(),
				ZeldaQuickItems.selectedIndex(),
				ZeldaQuickItems.toSnapshotItems()
		);
	}

	private static ZeldaInventorySnapshot mergeWithLocalState(ZeldaInventorySnapshot serverSnapshot) {
		int mergedRupees = Math.max(ZeldaEquipment.rupees, serverSnapshot.rupees());

		List<String> mergedOwned = new ArrayList<>(serverSnapshot.ownedEquipmentIds());
		for (String localId : ZeldaEquipment.toOwnedIds()) {
			if (!mergedOwned.contains(localId)) {
				mergedOwned.add(localId);
			}
		}

		Map<String, String> mergedEquipped = new LinkedHashMap<>(serverSnapshot.equippedSlots());
		mergedEquipped.putAll(ZeldaEquipment.toEquippedMap());

		List<ZeldaInventorySnapshot.QuickItemData> mergedItems = new ArrayList<>(serverSnapshot.quickItems());
		for (ZeldaInventorySnapshot.QuickItemData localItem : ZeldaQuickItems.toSnapshotItems()) {
			boolean replaced = false;
			for (int i = 0; i < mergedItems.size(); i++) {
				if (mergedItems.get(i).id().equals(localItem.id())) {
					if (localItem.count() > mergedItems.get(i).count()) {
						mergedItems.set(i, localItem);
					}
					replaced = true;
					break;
				}
			}
			if (!replaced) {
				mergedItems.add(localItem);
			}
		}

		int selected = mergedItems.isEmpty() ? 0 : Math.clamp(ZeldaQuickItems.selectedIndex(), 0, mergedItems.size() - 1);
		return new ZeldaInventorySnapshot(mergedRupees, mergedOwned, mergedEquipped, selected, mergedItems);
	}
}
