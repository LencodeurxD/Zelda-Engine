package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Broadcast S2C when a quick item triggers a world effect. */
public record QuickItemEffectPayload(int effectType, double x, double y, double z, int remainingTicks)
		implements CustomPacketPayload {

	public static final int EFFECT_STICK_HELD = 0;
	public static final int EFFECT_STICK_IGNITED = 1;
	public static final int EFFECT_STICK_EXTINGUISHED = 2;
	public static final int EFFECT_STICK_BURNED_OUT = 3;
	public static final int EFFECT_DEKU_NUT_FLASH = 4;
	public static final int EFFECT_BOMB_THROWN = 5;

	public static final Type<QuickItemEffectPayload> TYPE =
			new Type<>(OcarinaEngine.id("quick_item_effect"));

	public static final StreamCodec<RegistryFriendlyByteBuf, QuickItemEffectPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, QuickItemEffectPayload::effectType,
					ByteBufCodecs.DOUBLE, QuickItemEffectPayload::x,
					ByteBufCodecs.DOUBLE, QuickItemEffectPayload::y,
					ByteBufCodecs.DOUBLE, QuickItemEffectPayload::z,
					ByteBufCodecs.VAR_INT, QuickItemEffectPayload::remainingTicks,
					QuickItemEffectPayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}