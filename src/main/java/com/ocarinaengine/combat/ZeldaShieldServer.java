package com.ocarinaengine.combat;

import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.network.ZeldaShieldBlockedPayload;
import com.ocarinaengine.network.ZeldaShieldStatePayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Authoritative server-side Ocarina of Time shield blocking, projectile deflection,
 * Deku Shield fire-burn, Hylian 360-degree crouch-turtle, and Mirror Shield reflection.
 */
public final class ZeldaShieldServer {
	private record ShieldState(boolean guarding, String shieldId, boolean crouching) {}

	private static final Map<UUID, ShieldState> STATES = new ConcurrentHashMap<>();

	private ZeldaShieldServer() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ZeldaShieldStatePayload.TYPE, (payload, context) ->
				STATES.put(context.player().getUUID(),
						new ShieldState(payload.guarding(), payload.shieldId(), payload.crouching())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				STATES.remove(handler.getPlayer().getUUID()));
	}

	/** Returns true if the incoming damage was completely blocked by Link's shield. */
	public static boolean tryBlockIncomingDamage(ServerPlayer player, DamageSource source) {
		if (!player.isAlive() || player.isSpectator() || !OcarinaGameTypeServer.isZelda(player)) {
			return false;
		}
		ShieldState state = STATES.get(player.getUUID());
		if (state == null || !state.guarding() || state.shieldId().isEmpty()) {
			return false;
		}
		if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.STARVE)
				|| source.is(DamageTypes.DROWN) || source.is(DamageTypes.GENERIC_KILL)
				|| source.is(DamageTypeTags.IS_FALL)) {
			return false;
		}

		boolean isDeku = "deku_shield".equals(state.shieldId());
		boolean isHylian = "hylian_shield".equals(state.shieldId());
		boolean isMirror = "mirror_shield".equals(state.shieldId());
		boolean turtle360 = isHylian && state.crouching();

		Vec3 srcPos = source.getSourcePosition();
		if (!turtle360 && srcPos != null) {
			float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
			double lookX = -Mth.sin(yawRad);
			double lookZ = Mth.cos(yawRad);
			double dx = srcPos.x - player.getX();
			double dz = srcPos.z - player.getZ();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			if (horiz > 0.08) {
				double dot = (dx * lookX + dz * lookZ) / horiz;
				// ~140-degree frontal shield arc (cos 70 deg ~= 0.342)
				if (dot < 0.34) {
					return false;
				}
			}
		}

		ServerLevel level = player.serverLevel();
		Vec3 impactPos = player.getEyePosition().add(player.getLookAngle().scale(0.62));

		// OoT Deku Shield rule: fire damage burns it away!
		if (isDeku && source.is(DamageTypeTags.IS_FIRE)) {
			STATES.put(player.getUUID(), new ShieldState(false, "", false));
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.9f, 0.9f);
			ServerPlayNetworking.send(player,
					new ZeldaShieldBlockedPayload(impactPos.x, impactPos.y, impactPos.z, true, false));
			return true;
		}

		// Projectile deflection / Mirror reflection
		boolean reflectedMirror = false;
		Entity direct = source.getDirectEntity();
		if (direct instanceof Projectile proj) {
			if (isMirror) {
				Vec3 reflectDir = player.getLookAngle().normalize();
				proj.setOwner(player);
				proj.setDeltaMovement(reflectDir.scale(1.65));
				proj.hurtMarked = true;
				reflectedMirror = true;
			} else {
				Vec3 bounce = proj.getDeltaMovement().scale(-0.35).add(0.0, 0.22, 0.0);
				proj.setDeltaMovement(bounce);
				proj.hurtMarked = true;
			}
		}

		// OoT braced knockback pushback on block
		Entity attacker = source.getEntity();
		if (attacker instanceof LivingEntity livingAttacker && attacker != player) {
			double kx = player.getX() - livingAttacker.getX();
			double kz = player.getZ() - livingAttacker.getZ();
			livingAttacker.knockback(0.45, -kx, -kz);
			player.knockback(isHylian ? 0.24 : 0.34, -kx, -kz);
			player.hurtMarked = true;
		}

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, isDeku ? 0.80f : 1.12f);
		ServerPlayNetworking.send(player,
				new ZeldaShieldBlockedPayload(impactPos.x, impactPos.y, impactPos.z, false, reflectedMirror));
		return true;
	}
}
