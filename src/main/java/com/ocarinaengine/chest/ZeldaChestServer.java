package com.ocarinaengine.chest;

import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.network.ChestCutscenePayload;
import com.ocarinaengine.network.FinishZeldaChestPayload;
import com.ocarinaengine.network.OpenZeldaChestPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side chest handler for Zelda mode:
 * - Extracts only the first non-empty item found inside the chest (single or double).
 * - Animates the chest lid open and keeps it open until the player confirms the reveal
 *   (with a generous safety timeout if the client disconnects).
 */
public final class ZeldaChestServer {
	private static final int SAFETY_TIMEOUT_TICKS = 1200;

	private static final class OpenChest {
		final UUID playerId;
		final ServerLevel level;
		final BlockPos pos;
		final BlockPos otherPos;
		final boolean doubleChest;
		int ticksLeft;

		OpenChest(UUID playerId, ServerLevel level, BlockPos pos, BlockPos otherPos, boolean doubleChest, int ticksLeft) {
			this.playerId = playerId;
			this.level = level;
			this.pos = pos;
			this.otherPos = otherPos;
			this.doubleChest = doubleChest;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final List<OpenChest> OPEN_CHESTS = new ArrayList<>();

	private ZeldaChestServer() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(OpenZeldaChestPayload.TYPE, (payload, context) ->
				handleOpen(context.player(), payload.pos()));
		ServerPlayNetworking.registerGlobalReceiver(FinishZeldaChestPayload.TYPE, (payload, context) ->
				handleFinish(context.player(), payload.pos()));
		ServerTickEvents.END_SERVER_TICK.register(ZeldaChestServer::tick);
	}

	private static void handleOpen(ServerPlayer player, BlockPos clickedPos) {
		if (!OcarinaGameTypeServer.isZelda(player)) {
			return;
		}
		ServerLevel level = player.serverLevel();
		if (player.distanceToSqr(Vec3.atCenterOf(clickedPos)) > 36.0) {
			return;
		}
		BlockState state = level.getBlockState(clickedPos);
		if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
			return;
		}

		ChestType type = state.getValue(ChestBlock.TYPE);
		boolean isDouble = type != ChestType.SINGLE;
		Direction facing = state.getValue(ChestBlock.FACING);

		BlockPos primaryPos = clickedPos;
		BlockPos otherPos = clickedPos;
		if (isDouble) {
			BlockPos connected = clickedPos.relative(ChestBlock.getConnectedDirection(state));
			BlockState connectedState = level.getBlockState(connected);
			if (connectedState.getBlock() instanceof ChestBlock && connectedState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				otherPos = connected;
			} else {
				isDouble = false;
			}
		}

		// Extract ONLY the first non-empty item in the chest container.
		ItemStack reward = ItemStack.EMPTY;
		Container container = ChestBlock.getContainer(chestBlock, state, level, primaryPos, true);
		if (container != null) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack stack = container.getItem(i);
				if (!stack.isEmpty()) {
					reward = stack.copy();
					container.setItem(i, ItemStack.EMPTY);
					container.setChanged();
					break;
				}
			}
		}

		// Animate the chest lid open.
		level.blockEvent(primaryPos, chestBlock, 1, 1);
		if (isDouble) {
			level.blockEvent(otherPos, chestBlock, 1, 1);
		}
		OPEN_CHESTS.add(new OpenChest(player.getUUID(), level, primaryPos, otherPos, isDouble, SAFETY_TIMEOUT_TICKS));

		ServerPlayNetworking.send(player, new ChestCutscenePayload(primaryPos, otherPos, facing, isDouble, reward));
	}

	private static void handleFinish(ServerPlayer player, BlockPos pos) {
		Iterator<OpenChest> it = OPEN_CHESTS.iterator();
		while (it.hasNext()) {
			OpenChest oc = it.next();
			if (oc.playerId.equals(player.getUUID()) && (oc.pos.equals(pos) || oc.otherPos.equals(pos))) {
				closeLid(oc);
				it.remove();
			}
		}
	}

	private static void tick(MinecraftServer server) {
		Iterator<OpenChest> it = OPEN_CHESTS.iterator();
		while (it.hasNext()) {
			OpenChest oc = it.next();
			oc.ticksLeft--;
			if (oc.ticksLeft <= 0) {
				closeLid(oc);
				it.remove();
			}
		}
	}

	private static void closeLid(OpenChest oc) {
		BlockState s1 = oc.level.getBlockState(oc.pos);
		if (s1.getBlock() instanceof ChestBlock cb) {
			oc.level.blockEvent(oc.pos, cb, 1, 0);
		}
		if (oc.doubleChest) {
			BlockState s2 = oc.level.getBlockState(oc.otherPos);
			if (s2.getBlock() instanceof ChestBlock cb2) {
				oc.level.blockEvent(oc.otherPos, cb2, 1, 0);
			}
		}
	}
}
