package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client right after creating a new world in CreateWorldScreen to lock its initial game type. */
public record InitWorldGameTypePayload(int gameTypeOrdinal) implements CustomPacketPayload {
	public static final Type<InitWorldGameTypePayload> TYPE =
			new Type<>(OcarinaEngine.id("init_world_game_type"));

	public static final StreamCodec<RegistryFriendlyByteBuf, InitWorldGameTypePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, InitWorldGameTypePayload::gameTypeOrdinal,
					InitWorldGameTypePayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
