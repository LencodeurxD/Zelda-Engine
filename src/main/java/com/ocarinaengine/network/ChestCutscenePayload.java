package com.ocarinaengine.network;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Sent by the server to trigger the Zelda chest opening cutscene and grant the first found item. */
public record ChestCutscenePayload(
		BlockPos pos,
		BlockPos otherPos,
		Direction facing,
		boolean doubleChest,
		ItemStack reward
) implements CustomPacketPayload {
	public static final Type<ChestCutscenePayload> TYPE =
			new Type<>(OcarinaEngine.id("chest_cutscene"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ChestCutscenePayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ChestCutscenePayload::pos,
					BlockPos.STREAM_CODEC, ChestCutscenePayload::otherPos,
					Direction.STREAM_CODEC, ChestCutscenePayload::facing,
					ByteBufCodecs.BOOL, ChestCutscenePayload::doubleChest,
					ItemStack.OPTIONAL_STREAM_CODEC, ChestCutscenePayload::reward,
					ChestCutscenePayload::new
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
