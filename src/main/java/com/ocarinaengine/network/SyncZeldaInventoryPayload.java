package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the server on join to restore the player's Zelda inventory for this specific world. */
public record SyncZeldaInventoryPayload(ZeldaInventorySnapshot snapshot) implements CustomPacketPayload {
	public static final Type<SyncZeldaInventoryPayload> TYPE =
			new Type<>(OcarinaEngine.id("sync_zelda_inventory"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncZeldaInventoryPayload> CODEC =
			StreamCodec.of(
					(buf, payload) -> payload.snapshot().write(buf),
					buf -> new SyncZeldaInventoryPayload(ZeldaInventorySnapshot.read(buf))
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
