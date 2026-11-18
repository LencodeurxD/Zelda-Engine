package com.ocarinaengine.inventory;

import com.ocarinaengine.network.RequestZeldaInventoryPayload;
import com.ocarinaengine.network.SaveZeldaInventoryPayload;
import com.ocarinaengine.network.SyncZeldaInventoryPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side handler for per-world Zelda inventory loading on player join
 * and saving whenever the player's equipment, items, or rupees change.
 */
public final class OcarinaInventoryServer {
	private OcarinaInventoryServer() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			ZeldaInventorySnapshot snapshot = OcarinaInventoryData.get(server).getForPlayer(server, player.getUUID());
			sender.sendPacket(new SyncZeldaInventoryPayload(snapshot));
		});

		ServerPlayNetworking.registerGlobalReceiver(RequestZeldaInventoryPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			if (server == null) {
				return;
			}
			ZeldaInventorySnapshot snapshot = OcarinaInventoryData.get(server).getForPlayer(server, player.getUUID());
			ServerPlayNetworking.send(player, new SyncZeldaInventoryPayload(snapshot));
		});

		ServerPlayNetworking.registerGlobalReceiver(SaveZeldaInventoryPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			if (server == null) {
				return;
			}
			OcarinaInventoryData.get(server).setForPlayer(server, player.getUUID(), payload.snapshot());
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(OcarinaInventoryData::clearActiveServer);
	}
}
