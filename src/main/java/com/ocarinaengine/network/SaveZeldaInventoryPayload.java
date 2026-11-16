package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when Zelda equipment, quick items, or rupees change in the active world. */
public record SaveZeldaInventoryPayload(ZeldaInventorySnapshot snapshot) implements CustomPacketPayload {
	public static final Type<SaveZeldaInventoryPayload> TYPE =
			new Type<>(OcarinaEngine.id("save_zelda_inventory"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SaveZeldaInventoryPayload> CODEC =
			StreamCodec.of(
					(buf, payload) -> payload.snapshot().write(buf),
					buf -> new SaveZeldaInventoryPayload(ZeldaInventorySnapshot.read(buf))
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
