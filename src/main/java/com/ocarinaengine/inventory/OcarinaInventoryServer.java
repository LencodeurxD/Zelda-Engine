package com.ocarinaengine.inventory;

import com.ocarinaengine.network.SaveZeldaInventoryPayload;
import com.ocarinaengine.network.SyncZeldaInventoryPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
			ZeldaInventorySnapshot snapshot = OcarinaInventoryData.get(server).getForPlayer(player.getUUID());
			ServerPlayNetworking.send(player, new SyncZeldaInventoryPayload(snapshot));
		});

		ServerPlayNetworking.registerGlobalReceiver(SaveZeldaInventoryPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.server == null) {
				return;
			}
			OcarinaInventoryData.get(player.server).setForPlayer(player.getUUID(), payload.snapshot());
		});
	}
}
