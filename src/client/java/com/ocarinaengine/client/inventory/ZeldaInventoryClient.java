package com.ocarinaengine.client.inventory;

import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import com.ocarinaengine.network.SaveZeldaInventoryPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Coordinates per-world synchronization of {@link ZeldaEquipment} and {@link ZeldaQuickItems}
 * with the active server world, preventing inventory state from leaking across worlds.
 */
public final class ZeldaInventoryClient {
	private static boolean syncedForCurrentWorld = false;

	private ZeldaInventoryClient() {}

	public static boolean isSynced() {
		return syncedForCurrentWorld;
	}

	/** Applies the authoritative inventory snapshot sent by the server when joining a world. */
	public static void applyServerSnapshot(ZeldaInventorySnapshot snapshot) {
		if (snapshot == null) {
			reset();
			return;
		}
		ZeldaEquipment.applySnapshot(snapshot.rupees(), snapshot.ownedEquipmentIds(), snapshot.equippedSlots());
		ZeldaQuickItems.applySnapshot(snapshot.selectedQuickItem(), snapshot.quickItems());
		syncedForCurrentWorld = true;
	}

	/** Pushes the player's updated equipment, quick items, and rupees to the active world's server save. */
	public static void sendSnapshot() {
		if (!syncedForCurrentWorld) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.player == null) {
			return;
		}
		ZeldaInventorySnapshot snapshot = new ZeldaInventorySnapshot(
				ZeldaEquipment.rupees,
				ZeldaEquipment.toOwnedIds(),
				ZeldaEquipment.toEquippedMap(),
				ZeldaQuickItems.selectedIndex(),
				ZeldaQuickItems.toSnapshotItems()
		);
		ClientPlayNetworking.send(new SaveZeldaInventoryPayload(snapshot));
	}

	/** Clears all in-memory Zelda equipment, quick items, and rupees when disconnecting from a world. */
	public static void reset() {
		syncedForCurrentWorld = false;
		ZeldaEquipment.clear();
		ZeldaQuickItems.clear();
	}
}
