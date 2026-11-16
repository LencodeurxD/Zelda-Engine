package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when the player interacts with a chest in Zelda mode. */
public record OpenZeldaChestPayload(BlockPos pos) implements CustomPacketPayload {
	public static final Type<OpenZeldaChestPayload> TYPE =
			new Type<>(OcarinaEngine.id("open_zelda_chest"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OpenZeldaChestPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, OpenZeldaChestPayload::pos,
					OpenZeldaChestPayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
