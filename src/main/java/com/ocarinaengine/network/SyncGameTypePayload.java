package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the server to synchronize the player's active OcarinaGameType (Zelda or Minecraft). */
public record SyncGameTypePayload(int gameTypeOrdinal) implements CustomPacketPayload {
	public static final Type<SyncGameTypePayload> TYPE =
			new Type<>(OcarinaEngine.id("sync_game_type"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncGameTypePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, SyncGameTypePayload::gameTypeOrdinal,
					SyncGameTypePayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
