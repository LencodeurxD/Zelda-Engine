package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client on join if needed to ensure the per-world Zelda inventory snapshot is synced. */
public record RequestZeldaInventoryPayload() implements CustomPacketPayload {
	public static final Type<RequestZeldaInventoryPayload> TYPE =
			new Type<>(OcarinaEngine.id("request_zelda_inventory"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RequestZeldaInventoryPayload> CODEC =
			StreamCodec.unit(new RequestZeldaInventoryPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
