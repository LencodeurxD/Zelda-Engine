package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ZeldaShieldBlockedPayload(double x, double y, double z, boolean burnedDeku, boolean reflectedMirror)
		implements CustomPacketPayload {
	public static final Type<ZeldaShieldBlockedPayload> TYPE =
			new Type<>(OcarinaEngine.id("zelda_shield_blocked"));

	public static final StreamCodec<FriendlyByteBuf, ZeldaShieldBlockedPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, ZeldaShieldBlockedPayload::x,
			ByteBufCodecs.DOUBLE, ZeldaShieldBlockedPayload::y,
			ByteBufCodecs.DOUBLE, ZeldaShieldBlockedPayload::z,
			ByteBufCodecs.BOOL, ZeldaShieldBlockedPayload::burnedDeku,
			ByteBufCodecs.BOOL, ZeldaShieldBlockedPayload::reflectedMirror,
			ZeldaShieldBlockedPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
