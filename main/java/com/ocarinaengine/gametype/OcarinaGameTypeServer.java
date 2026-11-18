package com.ocarinaengine.gametype;

import com.ocarinaengine.network.InitWorldGameTypePayload;
import com.ocarinaengine.network.SyncGameTypePayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Server-side authority for per-world and per-player OcarinaGameType (Zelda vs. Minecraft):
 * - Persists world default and per-player overrides in {@link OcarinaWorldData}.
 * - Restores vanilla 20 HP / hunger when switching into Minecraft mode, and applies 12 HP / locked food in Zelda mode.
 * - Keeps clients synchronized on world join and /Gametype command execution.
 */
public final class OcarinaGameTypeServer {
	private static final double ZELDA_MAX_HEALTH = 12.0D;
	private static final double VANILLA_MAX_HEALTH = 20.0D;
	private static final int ZELDA_FOOD_LEVEL = 17;

	private static final Map<UUID, OcarinaGameType> LAST_APPLIED_BODY = new HashMap<>();

	private OcarinaGameTypeServer() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			OcarinaGameType type = getForPlayer(server, player.getUUID());
			applyBodyAttributes(player, type, true);
			ServerPlayNetworking.send(player, new SyncGameTypePayload(type.ordinal()));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_APPLIED_BODY.remove(handler.getPlayer().getUUID()));

		ServerPlayNetworking.registerGlobalReceiver(InitWorldGameTypePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.server;
			if (!server.isSingleplayer() && !player.hasPermissions(2)) {
				return;
			}
			OcarinaWorldData data = OcarinaWorldData.get(server);
			if (!data.isWorldDefaultExplicit()) {
				OcarinaGameType chosen = OcarinaGameType.byOrdinal(payload.gameTypeOrdinal());
				data.setWorldDefault(chosen);
				for (ServerPlayer online : server.getPlayerList().getPlayers()) {
					OcarinaGameType effective = data.getForPlayer(online.getUUID());
					applyBodyAttributes(online, effective, false);
					ServerPlayNetworking.send(online, new SyncGameTypePayload(effective.ordinal()));
				}
			}
		});
	}

	public static OcarinaGameType getForPlayer(MinecraftServer server, UUID playerId) {
		return OcarinaWorldData.get(server).getForPlayer(playerId);
	}

	public static boolean isZelda(ServerPlayer player) {
		if (player == null || player.server == null) {
			return true;
		}
		return getForPlayer(player.server, player.getUUID()).isZelda();
	}

	public static void setPlayerGameType(ServerPlayer target, OcarinaGameType type) {
		MinecraftServer server = target.server;
		OcarinaWorldData data = OcarinaWorldData.get(server);
		data.setForPlayer(target.getUUID(), type);
		if (server.isSingleplayer() && !server.isPublished()) {
			data.setWorldDefault(type);
		}
		applyBodyAttributes(target, type, false);
		ServerPlayNetworking.send(target, new SyncGameTypePayload(type.ordinal()));
	}

	private static void applyBodyAttributes(ServerPlayer player, OcarinaGameType type, boolean onJoin) {
		UUID id = player.getUUID();
		OcarinaGameType prev = LAST_APPLIED_BODY.put(id, type);
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);

		if (type.isZelda()) {
			if (maxHealth != null && maxHealth.getBaseValue() != ZELDA_MAX_HEALTH) {
				maxHealth.setBaseValue(ZELDA_MAX_HEALTH);
			}
			if (player.getHealth() > (float) ZELDA_MAX_HEALTH) {
				player.setHealth((float) ZELDA_MAX_HEALTH);
			}
			if (player.getFoodData().getFoodLevel() != ZELDA_FOOD_LEVEL) {
				player.getFoodData().setFoodLevel(ZELDA_FOOD_LEVEL);
			}
		} else {
			// Transitioning from Zelda -> Minecraft, or joining a Minecraft world with leftover 12 HP Zelda cap.
			boolean wasZelda = (prev == OcarinaGameType.ZELDA)
					|| (onJoin && maxHealth != null && Math.abs(maxHealth.getBaseValue() - ZELDA_MAX_HEALTH) < 0.01D);
			if (wasZelda) {
				if (maxHealth != null && maxHealth.getBaseValue() < VANILLA_MAX_HEALTH) {
					maxHealth.setBaseValue(VANILLA_MAX_HEALTH);
				}
				if (player.getHealth() >= (float) ZELDA_MAX_HEALTH - 0.1f) {
					player.setHealth((float) VANILLA_MAX_HEALTH);
				} else {
					player.setHealth(Math.min((float) VANILLA_MAX_HEALTH,
							player.getHealth() * (float) (VANILLA_MAX_HEALTH / ZELDA_MAX_HEALTH)));
				}
				if (player.getFoodData().getFoodLevel() == ZELDA_FOOD_LEVEL) {
					player.getFoodData().setFoodLevel(20);
					player.getFoodData().setSaturation(5.0f);
				}
			}
		}
	}
}
