package com.ocarinaengine.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Registers /place chest and /place double_chest test commands.
 * Places a single or double chest in front of the player containing a Deku Stick.
 */
public final class ZeldaChestCommands {
	private ZeldaChestCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("place")
				.then(Commands.literal("chest")
						.executes(ctx -> placeChest(ctx.getSource(), false)))
				.then(Commands.literal("double_chest")
						.executes(ctx -> placeChest(ctx.getSource(), true))));
	}

	private static int placeChest(CommandSourceStack source, boolean doubleChest) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can run this command."));
			return 0;
		}

		ServerLevel level = player.serverLevel();
		Direction playerDir = player.getDirection();
		Direction chestFacing = playerDir.getOpposite();
		BlockPos basePos = player.blockPosition().relative(playerDir, 2);

		if (doubleChest) {
			BlockPos rightPos = basePos.relative(chestFacing.getClockWise());
			BlockState leftState = Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, chestFacing)
					.setValue(ChestBlock.TYPE, ChestType.LEFT);
			BlockState rightState = Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, chestFacing)
					.setValue(ChestBlock.TYPE, ChestType.RIGHT);

			level.setBlock(basePos, leftState, 3);
			level.setBlock(rightPos, rightState, 3);
			fillWithWoodenSword(level, basePos);
			clearChest(level, rightPos);

			source.sendSuccess(() -> Component.literal("Placed a double chest containing a Wooden Sword."), true);
		} else {
			BlockState singleState = Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, chestFacing)
					.setValue(ChestBlock.TYPE, ChestType.SINGLE);

			level.setBlock(basePos, singleState, 3);
			fillWithDekuStick(level, basePos);

			source.sendSuccess(() -> Component.literal("Placed a chest containing a Deku Stick."), true);
		}

		return 1;
	}

	private static void fillWithDekuStick(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			chest.clearContent();
			ItemStack stick = new ItemStack(Items.STICK, 1);
			stick.set(DataComponents.CUSTOM_NAME, Component.literal("Deku Stick").withStyle(s -> s.withItalic(false)));
			chest.setItem(0, stick);
			chest.setChanged();
		}
	}

	private static void fillWithWoodenSword(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			chest.clearContent();
			ItemStack sword = new ItemStack(Items.WOODEN_SWORD, 1);
			sword.set(DataComponents.CUSTOM_NAME, Component.literal("Wooden Sword").withStyle(s -> s.withItalic(false)));
			chest.setItem(0, sword);
			chest.setChanged();
		}
	}

	private static void clearChest(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			chest.clearContent();
			chest.setChanged();
		}
	}
}
