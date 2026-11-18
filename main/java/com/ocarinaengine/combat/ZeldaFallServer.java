package com.ocarinaengine.combat;

import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.network.ZeldaLandingRollPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Authoritative server-side Zelda fall damage & landing-roll handler:
 * - Increases safe fall threshold by +4 blocks (total 7 blocks safe in Zelda mode).
 * - Negates all fall damage on drops <= 20 blocks when Link executes a landing roll.
 */
public final class ZeldaFallServer {
	public static final float ZELDA_SAFE_FALL_BONUS = 4.0f;
	public static final float MAX_ROLL_FALL_DISTANCE = 20.5f;
	private static final long ROLL_TOKEN_TTL_NANOS = 650_000_000L;

	private static final Map<UUID, Long> PENDING_LANDING_ROLLS = new ConcurrentHashMap<>();

	private ZeldaFallServer() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ZeldaLandingRollPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!OcarinaGameTypeServer.isZelda(player)) {
				return;
			}
			if (payload.fallDistance() >= 0.0f && payload.fallDistance() <= MAX_ROLL_FALL_DISTANCE) {
				PENDING_LANDING_ROLLS.put(player.getUUID(), System.nanoTime());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				PENDING_LANDING_ROLLS.remove(handler.getPlayer().getUUID()));
	}

	/**
	 * Consumes a pending landing-roll token if valid for this fall and <= 20 blocks.
	 * Returns true when all fall damage should be negated.
	 */
	public static boolean tryNegateFallDamageWithRoll(ServerPlayer player, float serverFallDistance) {
		if (!player.isAlive() || player.isSpectator() || !OcarinaGameTypeServer.isZelda(player)) {
			return false;
		}
		Long tokenNanos = PENDING_LANDING_ROLLS.remove(player.getUUID());
		if (tokenNanos == null) {
			return false;
		}
		long age = System.nanoTime() - tokenNanos;
		return age >= 0L && age <= ROLL_TOKEN_TTL_NANOS && serverFallDistance <= MAX_ROLL_FALL_DISTANCE;
	}
}
