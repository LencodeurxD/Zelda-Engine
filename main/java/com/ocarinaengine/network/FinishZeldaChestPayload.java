package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when the player confirms the chest item reveal (A button). */
public record FinishZeldaChestPayload(BlockPos pos) implements CustomPacketPayload {
	public static final Type<FinishZeldaChestPayload> TYPE =
			new Type<>(OcarinaEngine.id("finish_zelda_chest"));

	public static final StreamCodec<RegistryFriendlyByteBuf, FinishZeldaChestPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, FinishZeldaChestPayload::pos,
					FinishZeldaChestPayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
