package com.ocarinaengine.combat;

import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.network.ZeldaSwordAttackPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Server-side handler for Zelda sword combat hits:
 * validates reach and Zelda mode, clears invulnerability frames for full Ocarina combos,
 * and applies Switch 2 style stagger & finisher knockback.
 */
public final class ZeldaCombatServer {
	public static final int MOVE_SLASH_HORIZ = 1;
	public static final int MOVE_SLASH_BACKHAND = 2;
	public static final int MOVE_SLASH_VERTICAL = 3;
	public static final int MOVE_THRUST = 4;
	public static final int MOVE_JUMP_ATTACK = 5;
	public static final int MOVE_SPIN_ATTACK = 6;

	private ZeldaCombatServer() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ZeldaSwordAttackPayload.TYPE, (payload, context) ->
				handleAttack(context.player(), payload.targetEntityId(), payload.moveOrdinal()));
	}

	private static void handleAttack(ServerPlayer player, int targetEntityId, int moveOrdinal) {
		if (!player.isAlive() || player.isSpectator() || !OcarinaGameTypeServer.isZelda(player)) {
			return;
		}
		ServerLevel level = player.serverLevel();
		Entity target = level.getEntity(targetEntityId);
		if (!(target instanceof LivingEntity living) || !living.isAlive() || living == player) {
			return;
		}
		if (player.distanceToSqr(living) > 28.0D) {
			return;
		}

		float damage;
		float knockback;
		boolean heavyFinisher = false;
		switch (moveOrdinal) {
			case MOVE_SLASH_BACKHAND -> {
				damage = 3.5f;
				knockback = 0.23f;
			}
			case MOVE_SLASH_VERTICAL -> {
				damage = 5.5f;
				knockback = 0.56f;
				heavyFinisher = true;
			}
			case MOVE_THRUST -> {
				damage = 4.5f;
				knockback = 0.48f;
			}
			case MOVE_JUMP_ATTACK -> {
				damage = 7.5f;
				knockback = 0.68f;
				heavyFinisher = true;
			}
			case MOVE_SPIN_ATTACK -> {
				damage = 6.5f;
				knockback = 0.80f;
				heavyFinisher = true;
			}
			default -> {
				damage = 3.5f;
				knockback = 0.21f;
			}
		}

		// Clear vanilla invulnerability frames before and after impact so full Ocarina combos connect cleanly.
		living.invulnerableTime = 0;
		boolean hurt = living.hurt(player.damageSources().playerAttack(player), damage);
		if (hurt) {
			living.invulnerableTime = 0;
			double dx = player.getX() - living.getX();
			double dz = player.getZ() - living.getZ();
			living.knockback(knockback, dx, dz);
			living.hurtMarked = true;

			float pitch = heavyFinisher ? 0.84f : (moveOrdinal == MOVE_THRUST ? 1.02f : 1.16f);
			level.playSound(null, living.getX(), living.getY(), living.getZ(),
					SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, heavyFinisher ? 0.95f : 0.78f, pitch);
			if (heavyFinisher) {
				level.playSound(null, living.getX(), living.getY(), living.getZ(),
						SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.75f, 0.88f);
			}
		}
	}
}
