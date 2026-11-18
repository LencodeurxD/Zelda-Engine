package com.ocarinaengine.items;

import com.ocarinaengine.entity.DekuNutEntity;
import com.ocarinaengine.entity.DekuSeedEntity;
import com.ocarinaengine.entity.ZeldaBombEntity;
import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.inventory.OcarinaInventoryData;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import com.ocarinaengine.network.QuickItemEffectPayload;
import com.ocarinaengine.network.SyncZeldaInventoryPayload;
import com.ocarinaengine.network.UseQuickItemPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Authoritative server logic for all Quick Items:
 * - Deku Stick (NO sword sounds, forward torch reach, 20s burn, cobweb/campfire ignition)
 * - Deku Nut (slammed straight at Link's feet -> blinding flash stun)
 * - Bomb (lift overhead -> throw ZeldaBombEntity with 3.5s fuse)
 * - Fairy Slingshot (shoots fast DekuSeedEntity straight / at Z-Target)
 * - Fairy Ocarina (plays 3-note OoT flute phrase + note particles)
 */
public final class ZeldaQuickItemsServer {
	public static final int DEKU_STICK_BURN_TICKS = 400;

	private static final float[][] OCARINA_MELODIES = {
		{0.94f, 1.12f, 0.84f}, // Zelda's Lullaby (B - D - A)
		{0.84f, 0.56f, 0.67f}, // Song of Time (A - D - F)
		{0.67f, 0.84f, 0.94f}  // Saria's Song (F - A - B)
	};

	private static final class PlayerItemState {
		boolean holdingStick = false;
		int stickLitTicks = 0;
		boolean holdingBomb = false;
		int ocarinaTicksLeft = 0;
		int ocarinaMelodyIndex = 0;
	}

	private static final Map<UUID, PlayerItemState> STATES = new ConcurrentHashMap<>();

	private ZeldaQuickItemsServer() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(UseQuickItemPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleUse(context.player(), payload)));

		ServerTickEvents.END_SERVER_TICK.register(ZeldaQuickItemsServer::tickAll);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				STATES.remove(handler.getPlayer().getUUID()));
	}

	private static void handleUse(ServerPlayer player, UseQuickItemPayload payload) {
		if (!player.isAlive() || player.isSpectator() || !OcarinaGameTypeServer.isZelda(player)) {
			return;
		}
		String id = payload.itemId();
		if ("deku_stick".equals(id)) {
			handleDekuStick(player, payload.actionType(), payload.targetEntityId());
		} else if ("deku_nut".equals(id) && payload.actionType() == UseQuickItemPayload.ACTION_USE) {
			handleDekuNutAtFeet(player);
		} else if ("bomb".equals(id)) {
			handleBomb(player, payload.actionType(), payload.targetEntityId(), payload.aimYaw(), payload.aimPitch());
		} else if ("fairy_slingshot".equals(id)) {
			handleSlingshot(player, payload.actionType(), payload.targetEntityId(), payload.aimYaw(), payload.aimPitch());
		} else if ("fairy_ocarina".equals(id)) {
			handleOcarina(player, payload.actionType());
		}
	}

	private static void handleDekuStick(ServerPlayer player, int actionType, int targetEntityId) {
		if (!hasItem(player, "deku_stick")) {
			return;
		}
		ServerLevel level = player.serverLevel();
		PlayerItemState state = STATES.computeIfAbsent(player.getUUID(), k -> new PlayerItemState());

		if (actionType == UseQuickItemPayload.ACTION_PUT_AWAY) {
			state.holdingStick = false;
			state.stickLitTicks = 0;
			ServerPlayNetworking.send(player, new QuickItemEffectPayload(
					QuickItemEffectPayload.EFFECT_STICK_EXTINGUISHED, player.getX(), player.getY(), player.getZ(), 0));
			return;
		}

		state.holdingBomb = false;

		if (actionType == UseQuickItemPayload.ACTION_WAVE_STICK) {
			state.holdingStick = true;
			waveStickInAirOrTowardTorch(player, level, state);
			return;
		}

		if (actionType == UseQuickItemPayload.ACTION_SWING_STICK && state.holdingStick) {
			swingStickInWorld(player, level, state, targetEntityId);
			return;
		}

		if (!state.holdingStick) {
			state.holdingStick = true;
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.35f, 1.4f);
			ServerPlayNetworking.send(player, new QuickItemEffectPayload(
					QuickItemEffectPayload.EFFECT_STICK_HELD, player.getX(), player.getY(), player.getZ(), 0));
			tryIgniteNearbyFire(player, level, state, false);
		} else {
			waveStickInAirOrTowardTorch(player, level, state);
		}
	}

	private static void waveStickInAirOrTowardTorch(ServerPlayer player, ServerLevel level, PlayerItemState state) {
		// Soft air/torch sound (NO sword slash sound!)
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				state.stickLitTicks > 0 ? SoundEvents.FIRECHARGE_USE : SoundEvents.AZALEA_LEAVES_STEP,
				SoundSource.PLAYERS, state.stickLitTicks > 0 ? 0.30f : 0.40f, state.stickLitTicks > 0 ? 1.45f : 1.25f);

		if (state.stickLitTicks <= 0) {
			tryIgniteNearbyFire(player, level, state, true);
		} else {
			applyFlameAtReach(player, level);
		}
	}

	private static void applyFlameAtReach(ServerPlayer player, ServerLevel level) {
		Vec3 look = player.getLookAngle().normalize();
		BlockPos reachPos = BlockPos.containing(player.getEyePosition().add(look.scale(2.1)));
		for (BlockPos pos : BlockPos.betweenClosed(reachPos.offset(-1, -1, -1), reachPos.offset(1, 1, 1))) {
			BlockState bs = level.getBlockState(pos);
			if (bs.is(Blocks.COBWEB)) {
				level.destroyBlock(pos, false);
				level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
						16, 0.25, 0.25, 0.25, 0.03);
				level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.8f, 1.15f);
			} else if (bs.is(Blocks.CAMPFIRE) && !bs.getValue(CampfireBlock.LIT)) {
				level.setBlock(pos, bs.setValue(CampfireBlock.LIT, true), 3);
				level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.9f, 1.0f);
			}
		}
	}

	private static void swingStickInWorld(ServerPlayer player, ServerLevel level, PlayerItemState state, int targetEntityId) {
		if (state.stickLitTicks > 0) {
			applyFlameAtReach(player, level);
		}
		Entity target = targetEntityId >= 0 ? level.getEntity(targetEntityId) : null;
		if (target instanceof LivingEntity living && living.isAlive() && player.distanceToSqr(living) <= 16.0) {
			living.invulnerableTime = 0;
			float dmg = state.stickLitTicks > 0 ? 5.0f : 3.0f;
			living.hurt(player.damageSources().playerAttack(player), dmg);
			if (state.stickLitTicks > 0) {
				living.igniteForSeconds(3);
			}
			burnOutStick(player, level, state);
		}
	}

	/**
	 * In Ocarina of Time, Link slams the Deku Nut right at his feet (~1 block in front of his toes).
	 */
	private static void handleDekuNutAtFeet(ServerPlayer player) {
		if (!consumeOneItem(player, "deku_nut")) {
			return;
		}
		ServerLevel level = player.serverLevel();
		Vec3 forward = Vec3.directionFromRotation(0.0f, player.getYRot()).normalize();

		DekuNutEntity nut = new DekuNutEntity(level, player);
		nut.setPos(player.getX() + forward.x * 0.42, player.getY() + 0.92, player.getZ() + forward.z * 0.42);
		nut.shoot(forward.x * 0.30, -0.82, forward.z * 0.30, 0.56f, 0.0f);
		level.addFreshEntity(nut);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.PLAYERS, 0.7f, 1.35f);
	}

	private static void handleBomb(ServerPlayer player, int actionType, int targetEntityId, float aimYaw, float aimPitch) {
		PlayerItemState state = STATES.computeIfAbsent(player.getUUID(), k -> new PlayerItemState());
		if (actionType == UseQuickItemPayload.ACTION_PUT_AWAY) {
			state.holdingBomb = false;
			return;
		}
		if (!hasItem(player, "bomb")) {
			state.holdingBomb = false;
			return;
		}
		ServerLevel level = player.serverLevel();
		state.holdingStick = false;
		state.stickLitTicks = 0;

		if (!state.holdingBomb) {
			state.holdingBomb = true;
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.55f, 1.2f);
		} else {
			if (!consumeOneItem(player, "bomb")) {
				state.holdingBomb = false;
				return;
			}
			state.holdingBomb = false;

			float throwYaw = Float.isFinite(aimYaw) ? aimYaw : player.getYRot();
			Vec3 forward = Vec3.directionFromRotation(0.0f, throwYaw).normalize();
			ZeldaBombEntity bomb = new ZeldaBombEntity(level, player);
			boolean sneakSet = player.isShiftKeyDown();
			double spawnX = player.getX() + forward.x * (sneakSet ? 0.52 : 0.62);
			double spawnY = player.getY() + (sneakSet ? 0.16 : 1.72);
			double spawnZ = player.getZ() + forward.z * (sneakSet ? 0.52 : 0.62);
			bomb.setPos(spawnX, spawnY, spawnZ);
			bomb.setYRot(throwYaw);

			Vec3 playerVel = player.getDeltaMovement();
			Entity target = targetEntityId >= 0 ? level.getEntity(targetEntityId) : null;
			if (sneakSet) {
				// Sneak-set heavy bomb directly onto the floor in front of Link's feet
				bomb.setDeltaMovement(forward.x * 0.05, -0.02, forward.z * 0.05);
			} else if (target instanceof LivingEntity living && living.isAlive() && player.distanceToSqr(living) <= 324.0) {
				// High-arching heavy Z-Target lob toward enemy
				Vec3 toTarget = living.position().subtract(spawnX, spawnY, spawnZ);
				double horizDist = Math.max(0.5, Math.hypot(toTarget.x, toTarget.z));
				double speed = Math.min(0.74, 0.36 + horizDist * 0.042);
				double inv = speed / horizDist;
				double arcY = Math.min(0.46, 0.28 + horizDist * 0.018);
				bomb.setDeltaMovement(toTarget.x * inv, arcY, toTarget.z * inv);
			} else {
				// Crisp OoT overhead throw: launches unmistakably UP & forward, then drops fast under heavy gravity
				float pitchClamped = Math.max(-45.0f, Math.min(45.0f, Float.isFinite(aimPitch) ? aimPitch : 0.0f));
				double speed = 0.58 - Math.max(0.0, pitchClamped) * 0.004;
				double arcY = Math.max(0.18, Math.min(0.50, 0.36 - (pitchClamped / 45.0) * 0.14));
				bomb.setDeltaMovement(
						forward.x * speed + playerVel.x * 0.30,
						arcY,
						forward.z * speed + playerVel.z * 0.30
				);
			}
			bomb.hasImpulse = true;
			level.addFreshEntity(bomb);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 0.75f, 1.25f);
			ServerPlayNetworking.send(player, new QuickItemEffectPayload(
					QuickItemEffectPayload.EFFECT_BOMB_THROWN, player.getX(), player.getY(), player.getZ(), 0));
		}
	}

	private static void handleSlingshot(ServerPlayer player, int actionType, int targetEntityId, float aimYaw, float aimPitch) {
		if (!hasItem(player, "fairy_slingshot")) {
			return;
		}
		ServerLevel level = player.serverLevel();
		if (actionType == UseQuickItemPayload.ACTION_AIM_START) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.LEASH_KNOT_PLACE, SoundSource.PLAYERS, 0.65f, 1.35f);
			return;
		}
		if (actionType != UseQuickItemPayload.ACTION_USE) {
			return;
		}

		float yaw = Float.isFinite(aimYaw) ? aimYaw : player.getYRot();
		float pitch = Float.isFinite(aimPitch) ? aimPitch : player.getXRot();
		Vec3 dir = Vec3.directionFromRotation(pitch, yaw).normalize();

		Entity target = targetEntityId >= 0 ? level.getEntity(targetEntityId) : null;
		if (target instanceof LivingEntity living && living.isAlive() && player.distanceToSqr(living) <= 625.0) {
			Vec3 targetCenter = living.position().add(0.0, living.getBbHeight() * 0.55, 0.0);
			Vec3 toTarget = targetCenter.subtract(player.getEyePosition()).normalize();
			// Snap onto Z-Target if player isn't actively aiming strongly elsewhere
			if (toTarget.dot(dir) > 0.80) {
				dir = toTarget;
			}
		}

		DekuSeedEntity seed = new DekuSeedEntity(level, player);
		seed.setPos(player.getX() + dir.x * 0.35, player.getEyeY() - 0.10 + dir.y * 0.35, player.getZ() + dir.z * 0.35);
		seed.shoot(dir.x, dir.y, dir.z, 2.65f, 0.2f);
		level.addFreshEntity(seed);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.75f, 1.65f);
	}

	private static void handleOcarina(ServerPlayer player, int actionType) {
		PlayerItemState state = STATES.computeIfAbsent(player.getUUID(), k -> new PlayerItemState());
		if (actionType == UseQuickItemPayload.ACTION_PUT_AWAY) {
			state.ocarinaTicksLeft = 0;
			return;
		}
		if (!hasItem(player, "fairy_ocarina")) {
			return;
		}
		state.holdingStick = false;
		state.holdingBomb = false;
		state.ocarinaTicksLeft = 24;
		state.ocarinaMelodyIndex = (state.ocarinaMelodyIndex + 1) % OCARINA_MELODIES.length;
	}

	private static void tickAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerItemState state = STATES.get(player.getUUID());
			if (state == null) {
				continue;
			}
			if (!OcarinaGameTypeServer.isZelda(player)) {
				state.holdingStick = false;
				state.stickLitTicks = 0;
				state.holdingBomb = false;
				state.ocarinaTicksLeft = 0;
				continue;
			}
			ServerLevel level = player.serverLevel();

			// Ocarina 3-note phrase playback
			if (state.ocarinaTicksLeft > 0) {
				int elapsed = 24 - state.ocarinaTicksLeft;
				if (elapsed == 0 || elapsed == 8 || elapsed == 16) {
					int noteIdx = elapsed / 8;
					float pitch = OCARINA_MELODIES[state.ocarinaMelodyIndex][noteIdx];
					level.playSound(null, player.getX(), player.getEyeY(), player.getZ(),
							SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 0.9f, pitch);
					level.sendParticles(ParticleTypes.NOTE,
							player.getX(), player.getEyeY() + 0.45, player.getZ(),
							1, 0.25, 0.15, 0.25, 0.5);
				}
				state.ocarinaTicksLeft--;
			}

			if (!state.holdingStick) {
				continue;
			}
			if (!hasItem(player, "deku_stick")) {
				state.holdingStick = false;
				state.stickLitTicks = 0;
				continue;
			}
			if (state.stickLitTicks > 0) {
				if (player.isInWaterRainOrBubble()) {
					state.stickLitTicks = 0;
					level.playSound(null, player.getX(), player.getY(), player.getZ(),
							SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.7f, 1.1f);
					ServerPlayNetworking.send(player, new QuickItemEffectPayload(
							QuickItemEffectPayload.EFFECT_STICK_EXTINGUISHED, player.getX(), player.getY(), player.getZ(), 0));
					continue;
				}
				state.stickLitTicks--;
				if (state.stickLitTicks <= 0) {
					burnOutStick(player, level, state);
				}
			} else {
				tryIgniteNearbyFire(player, level, state, false);
			}
		}
	}

	private static void tryIgniteNearbyFire(ServerPlayer player, ServerLevel level, PlayerItemState state, boolean extendedReach) {
		if (state.stickLitTicks > 0 || player.isInWaterRainOrBubble()) {
			return;
		}
		Vec3 look = player.getLookAngle().normalize();
		BlockPos base = extendedReach
				? BlockPos.containing(player.getEyePosition().add(look.scale(1.9)))
				: player.blockPosition();
		int radius = extendedReach ? 2 : 1;
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-radius, -1, -radius), base.offset(radius, 2, radius))) {
			BlockState bs = level.getBlockState(pos);
			boolean isHeatSource = bs.is(Blocks.FIRE) || bs.is(Blocks.SOUL_FIRE)
					|| bs.is(Blocks.TORCH) || bs.is(Blocks.WALL_TORCH)
					|| bs.is(Blocks.SOUL_TORCH) || bs.is(Blocks.SOUL_WALL_TORCH)
					|| bs.is(Blocks.LANTERN) || bs.is(Blocks.LAVA)
					|| (bs.is(Blocks.CAMPFIRE) && bs.getValue(CampfireBlock.LIT));
			if (isHeatSource) {
				state.stickLitTicks = DEKU_STICK_BURN_TICKS;
				level.playSound(null, player.getX(), player.getY(), player.getZ(),
						SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.7f, 1.35f);
				ServerPlayNetworking.send(player, new QuickItemEffectPayload(
						QuickItemEffectPayload.EFFECT_STICK_IGNITED, player.getX(), player.getY(), player.getZ(), DEKU_STICK_BURN_TICKS));
				return;
			}
		}
	}

	private static void burnOutStick(ServerPlayer player, ServerLevel level, PlayerItemState state) {
		state.holdingStick = false;
		state.stickLitTicks = 0;
		consumeOneItem(player, "deku_stick");
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.WOOD_BREAK, SoundSource.PLAYERS, 0.75f, 1.15f);
		ServerPlayNetworking.send(player, new QuickItemEffectPayload(
				QuickItemEffectPayload.EFFECT_STICK_BURNED_OUT, player.getX(), player.getY(), player.getZ(), 0));
	}

	private static boolean hasItem(ServerPlayer player, String itemId) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return false;
		}
		ZeldaInventorySnapshot snap = OcarinaInventoryData.get(server).getForPlayer(server, player.getUUID());
		for (ZeldaInventorySnapshot.QuickItemData item : snap.quickItems()) {
			if (itemId.equals(item.id()) && item.count() > 0) {
				return true;
			}
		}
		return false;
	}

	private static boolean consumeOneItem(ServerPlayer player, String itemId) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return false;
		}
		OcarinaInventoryData data = OcarinaInventoryData.get(server);
		ZeldaInventorySnapshot snap = data.getForPlayer(server, player.getUUID());
		List<ZeldaInventorySnapshot.QuickItemData> next = new ArrayList<>(snap.quickItems().size());
		boolean consumed = false;

		for (ZeldaInventorySnapshot.QuickItemData entry : snap.quickItems()) {
			if (!consumed && itemId.equals(entry.id()) && entry.count() > 0) {
				consumed = true;
				int remaining = entry.count() - 1;
				if (remaining > 0) {
					next.add(new ZeldaInventorySnapshot.QuickItemData(
							entry.slot(), entry.id(), entry.name(), entry.description(),
							entry.iconId(), remaining, entry.maxCount()));
				}
			} else {
				next.add(entry);
			}
		}
		if (!consumed) {
			return false;
		}
		int nextSelected = next.isEmpty() ? 0 : Math.min(snap.selectedQuickItem(), next.size() - 1);
		ZeldaInventorySnapshot updated = new ZeldaInventorySnapshot(
				snap.rupees(), snap.ownedEquipmentIds(), snap.equippedSlots(), nextSelected, next);
		data.setForPlayer(server, player.getUUID(), updated);
		ServerPlayNetworking.send(player, new SyncZeldaInventoryPayload(updated));
		return true;
	}
}