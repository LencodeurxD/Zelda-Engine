package com.ocarinaengine.entity;

import com.ocarinaengine.OcarinaEngine;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fast straight-flying Deku Seed fired by the Fairy Slingshot.
 */
public class DekuSeedEntity extends ThrowableItemProjectile {
	public static EntityType<DekuSeedEntity> TYPE;

	public DekuSeedEntity(EntityType<? extends DekuSeedEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
	}

	public DekuSeedEntity(Level level, LivingEntity shooter) {
		super(TYPE, shooter, level);
		this.setNoGravity(true);
	}

	public static void register() {
		TYPE = Registry.register(
				BuiltInRegistries.ENTITY_TYPE,
				OcarinaEngine.id("deku_seed"),
				EntityType.Builder.<DekuSeedEntity>of(DekuSeedEntity::new, MobCategory.MISC)
						.sized(0.22f, 0.22f)
						.clientTrackingRange(4)
						.updateInterval(5)
						.build());
	}

	@Override
	protected Item getDefaultItem() {
		return Items.WHEAT_SEEDS;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.tickCount > 45 && !this.level().isClientSide) {
			this.discard();
		}
	}

	@Override
	protected void onHitEntity(EntityHitResult result) {
		super.onHitEntity(result);
		Entity hit = result.getEntity();
		if (hit != this.getOwner() && this.level() instanceof ServerLevel level) {
			hit.hurt(this.damageSources().thrown(this, this.getOwner()), 4.0f);
			level.playSound(null, hit.getX(), hit.getY(), hit.getZ(),
					SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.7f, 1.45f);
		}
	}

	@Override
	protected void onHit(HitResult result) {
		super.onHit(result);
		if (this.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.CRIT, result.getLocation().x, result.getLocation().y, result.getLocation().z,
					6, 0.08, 0.08, 0.08, 0.05);
			this.discard();
		}
	}
}