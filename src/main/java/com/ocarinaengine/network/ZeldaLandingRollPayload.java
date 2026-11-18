package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when Link performs an Ocarina-style landing roll from a fall <= 20 blocks. */
public record ZeldaLandingRollPayload(float fallDistance) implements CustomPacketPayload {
	public static final Type<ZeldaLandingRollPayload> TYPE =
			new Type<>(OcarinaEngine.id("zelda_landing_roll"));

	public static final StreamCodec<FriendlyByteBuf, ZeldaLandingRollPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, ZeldaLandingRollPayload::fallDistance,
			ZeldaLandingRollPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
