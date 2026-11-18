package com.ocarinaengine.entity;

import com.ocarinaengine.OcarinaEngine;
import com.ocarinaengine.network.QuickItemEffectPayload;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Throwable Deku Nut projectile: detonates right at Link's feet with a blinding flash that stuns nearby enemies.
 */
public class DekuNutEntity extends ThrowableItemProjectile {
	public static EntityType<DekuNutEntity> TYPE;

	public DekuNutEntity(EntityType<? extends DekuNutEntity> type, Level level) {
		super(type, level);
	}

	public DekuNutEntity(Level level, LivingEntity shooter) {
		super(TYPE, shooter, level);
	}

	public static void register() {
		TYPE = Registry.register(
				BuiltInRegistries.ENTITY_TYPE,
				OcarinaEngine.id("deku_nut"),
				EntityType.Builder.<DekuNutEntity>of(DekuNutEntity::new, MobCategory.MISC)
						.sized(0.28f, 0.28f)
						.clientTrackingRange(4)
						.updateInterval(10)
						.build());
	}

	@Override
	protected Item getDefaultItem() {
		return Items.COCOA_BEANS;
	}

	@Override
	protected void onHit(HitResult result) {
		super.onHit(result);
		if (this.level() instanceof ServerLevel serverLevel) {
			detonateFlash(serverLevel, result.getLocation());
			this.discard();
		}
	}

	private void detonateFlash(ServerLevel level, Vec3 pos) {
		level.sendParticles(ParticleTypes.FLASH, pos.x, pos.y + 0.15, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.2, pos.z, 26, 0.45, 0.35, 0.45, 0.15);
		level.playSound(null, pos.x, pos.y, pos.z,
				SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0f, 1.45f);

		AABB box = new AABB(pos.x - 4.5, pos.y - 2.5, pos.z - 4.5, pos.x + 4.5, pos.y + 3.0, pos.z + 4.5);
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box);
		for (LivingEntity entity : targets) {
			if (entity == this.getOwner()) {
				continue;
			}
			if (entity instanceof ServerPlayer otherPlayer) {
				ServerPlayNetworking.send(otherPlayer, new QuickItemEffectPayload(
						QuickItemEffectPayload.EFFECT_DEKU_NUT_FLASH, pos.x, pos.y, pos.z, 0));
				continue;
			}
			entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 10, false, true));
			entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0, false, false));
			entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
			entity.setDeltaMovement(Vec3.ZERO);
			entity.hurtMarked = true;
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
			}
		}

		if (this.getOwner() instanceof ServerPlayer owner) {
			ServerPlayNetworking.send(owner, new QuickItemEffectPayload(
					QuickItemEffectPayload.EFFECT_DEKU_NUT_FLASH, pos.x, pos.y, pos.z, 0));
		}
	}
}