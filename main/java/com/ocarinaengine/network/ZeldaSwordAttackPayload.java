package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when a Zelda sword attack hits a target entity. */
public record ZeldaSwordAttackPayload(int targetEntityId, int moveOrdinal) implements CustomPacketPayload {
	public static final Type<ZeldaSwordAttackPayload> TYPE =
			new Type<>(OcarinaEngine.id("zelda_sword_attack"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ZeldaSwordAttackPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, ZeldaSwordAttackPayload::targetEntityId,
					ByteBufCodecs.VAR_INT, ZeldaSwordAttackPayload::moveOrdinal,
					ZeldaSwordAttackPayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
