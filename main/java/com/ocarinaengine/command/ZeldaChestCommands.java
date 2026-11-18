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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Registers /place chest, /place double_chest, /place shield_chest,
 * /place item_chest <item>, and /place test_chests commands.
 */
public final class ZeldaChestCommands {
	private ZeldaChestCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("place")
				.then(Commands.literal("chest")
						.executes(ctx -> placeChest(ctx.getSource(), false)))
				.then(Commands.literal("double_chest")
						.executes(ctx -> placeChest(ctx.getSource(), true)))
				.then(Commands.literal("shield_chest")
						.then(Commands.literal("deku").executes(ctx -> placeShieldChest(ctx.getSource(), "Deku Shield")))
						.then(Commands.literal("hylian").executes(ctx -> placeShieldChest(ctx.getSource(), "Hylian Shield")))
						.then(Commands.literal("mirror").executes(ctx -> placeShieldChest(ctx.getSource(), "Mirror Shield"))))
				.then(Commands.literal("item_chest")
						.then(Commands.literal("deku_stick").executes(ctx -> placeItemChest(ctx.getSource(), Items.STICK, 5, "Deku Stick")))
						.then(Commands.literal("deku_nut").executes(ctx -> placeItemChest(ctx.getSource(), Items.COCOA_BEANS, 10, "Deku Nut")))
						.then(Commands.literal("slingshot").executes(ctx -> placeItemChest(ctx.getSource(), Items.BOW, 1, "Fairy Slingshot")))
						.then(Commands.literal("bomb").executes(ctx -> placeItemChest(ctx.getSource(), Items.TNT, 10, "Bomb")))
						.then(Commands.literal("ocarina").executes(ctx -> placeItemChest(ctx.getSource(), Items.AMETHYST_SHARD, 1, "Fairy Ocarina"))))
				.then(Commands.literal("test_chests")
						.executes(ctx -> placeAllTestChests(ctx.getSource()))));
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
			fillWithItem(level, basePos, Items.WOODEN_SWORD, 1, "Wooden Sword");
			clearChest(level, rightPos);
			source.sendSuccess(() -> Component.literal("Placed a double chest containing a Wooden Sword."), true);
		} else {
			BlockState singleState = Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, chestFacing)
					.setValue(ChestBlock.TYPE, ChestType.SINGLE);
			level.setBlock(basePos, singleState, 3);
			fillWithItem(level, basePos, Items.STICK, 5, "Deku Stick");
			source.sendSuccess(() -> Component.literal("Placed a chest containing 5 Deku Sticks."), true);
		}
		return 1;
	}

	private static int placeItemChest(CommandSourceStack source, Item item, int count, String customName) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can run this command."));
			return 0;
		}
		ServerLevel level = player.serverLevel();
		Direction playerDir = player.getDirection();
		Direction chestFacing = playerDir.getOpposite();
		BlockPos pos = player.blockPosition().relative(playerDir, 2);
		level.setBlock(pos, Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, chestFacing)
				.setValue(ChestBlock.TYPE, ChestType.SINGLE), 3);
		fillWithItem(level, pos, item, count, customName);
		source.sendSuccess(() -> Component.literal("Placed a chest containing " + count + "x " + customName + "."), true);
		return 1;
	}

	private static int placeAllTestChests(CommandSourceStack source) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can run this command."));
			return 0;
		}
		ServerLevel level = player.serverLevel();
		Direction playerDir = player.getDirection();
		Direction chestFacing = playerDir.getOpposite();
		Direction right = playerDir.getClockWise();
		BlockPos center = player.blockPosition().relative(playerDir, 3);

		Item[] items = {Items.STICK, Items.COCOA_BEANS, Items.BOW, Items.TNT, Items.AMETHYST_SHARD};
		int[] counts = {5, 10, 1, 10, 1};
		String[] names = {"Deku Stick", "Deku Nut", "Fairy Slingshot", "Bomb", "Fairy Ocarina"};

		for (int i = 0; i < items.length; i++) {
			BlockPos pos = center.relative(right, (i - 2) * 2);
			level.setBlock(pos, Blocks.CHEST.defaultBlockState()
					.setValue(ChestBlock.FACING, chestFacing)
					.setValue(ChestBlock.TYPE, ChestType.SINGLE), 3);
			fillWithItem(level, pos, items[i], counts[i], names[i]);
		}
		source.sendSuccess(() -> Component.literal("Placed 5 Zelda item chests in front of you!"), true);
		return 1;
	}

	private static int placeShieldChest(CommandSourceStack source, String shieldName) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only a player can run this command."));
			return 0;
		}
		ServerLevel level = player.serverLevel();
		Direction playerDir = player.getDirection();
		Direction chestFacing = playerDir.getOpposite();
		BlockPos basePos = player.blockPosition().relative(playerDir, 2);
		BlockPos rightPos = basePos.relative(chestFacing.getClockWise());
		BlockState leftState = Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, chestFacing)
				.setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockState rightState = Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, chestFacing)
				.setValue(ChestBlock.TYPE, ChestType.RIGHT);
		level.setBlock(basePos, leftState, 3);
		level.setBlock(rightPos, rightState, 3);
		fillWithItem(level, basePos, Items.SHIELD, 1, shieldName);
		clearChest(level, rightPos);
		source.sendSuccess(() -> Component.literal("Placed a shield chest containing a " + shieldName + "."), true);
		return 1;
	}

	private static void fillWithItem(ServerLevel level, BlockPos pos, Item item, int count, String customName) {
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
			chest.clearContent();
			ItemStack stack = new ItemStack(item, count);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(customName).withStyle(s -> s.withItalic(false)));
			chest.setItem(0, stack);
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