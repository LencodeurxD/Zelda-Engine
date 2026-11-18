package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ZeldaShieldStatePayload(boolean guarding, String shieldId, boolean crouching)
		implements CustomPacketPayload {
	public static final Type<ZeldaShieldStatePayload> TYPE =
			new Type<>(OcarinaEngine.id("zelda_shield_state"));

	public static final StreamCodec<FriendlyByteBuf, ZeldaShieldStatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ZeldaShieldStatePayload::guarding,
			ByteBufCodecs.STRING_UTF8, ZeldaShieldStatePayload::shieldId,
			ByteBufCodecs.BOOL, ZeldaShieldStatePayload::crouching,
			ZeldaShieldStatePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
