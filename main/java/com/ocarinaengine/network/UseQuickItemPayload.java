package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when the player activates, aims, waves, or puts away a Zelda Quick Item. */
public record UseQuickItemPayload(String itemId, int actionType, int targetEntityId, float aimYaw, float aimPitch)
		implements CustomPacketPayload {
	public static final int ACTION_USE = 0;
	public static final int ACTION_PUT_AWAY = 1;
	public static final int ACTION_SWING_STICK = 2;
	public static final int ACTION_WAVE_STICK = 3;
	public static final int ACTION_AIM_START = 4;

	public static final Type<UseQuickItemPayload> TYPE =
			new Type<>(OcarinaEngine.id("use_quick_item"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UseQuickItemPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, UseQuickItemPayload::itemId,
					ByteBufCodecs.VAR_INT, UseQuickItemPayload::actionType,
					ByteBufCodecs.VAR_INT, UseQuickItemPayload::targetEntityId,
					ByteBufCodecs.FLOAT, UseQuickItemPayload::aimYaw,
					ByteBufCodecs.FLOAT, UseQuickItemPayload::aimPitch,
					UseQuickItemPayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}