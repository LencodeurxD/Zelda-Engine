package com.ocarinaengine.entity;

import com.ocarinaengine.OcarinaEngine;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ocarina of Time Bomb entity with realistic bounce & wall ricochet physics,
 * smooth client interpolation, sphere-center tumble rotation, and 3.5s (70-tick) fuse.
 */
public class ZeldaBombEntity extends Entity {
	public static EntityType<ZeldaBombEntity> TYPE;
	private static final EntityDataAccessor<Integer> DATA_FUSE =
			SynchedEntityData.defineId(ZeldaBombEntity.class, EntityDataSerializers.INT);

	private LivingEntity owner;
	private int fuseTicks = 70;
	private int lerpSteps = 0;
	private double lerpX;
	private double lerpY;
	private double lerpZ;
	private float rollDeg = 0.0f;
	private float prevRollDeg = 0.0f;

	public ZeldaBombEntity(EntityType<? extends ZeldaBombEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	public ZeldaBombEntity(Level level, LivingEntity owner) {
		super(TYPE, level);
		this.owner = owner;
		this.blocksBuilding = true;
		this.entityData.set(DATA_FUSE, 70);
	}

	public static void register() {
		TYPE = Registry.register(
				BuiltInRegistries.ENTITY_TYPE,
				OcarinaEngine.id("zelda_bomb"),
				EntityType.Builder.<ZeldaBombEntity>of(ZeldaBombEntity::new, MobCategory.MISC)
						.sized(0.42f, 0.42f)
						.clientTrackingRange(8)
						.updateInterval(1)
						.build());
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_FUSE, 70);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (DATA_FUSE.equals(key)) {
			this.fuseTicks = this.entityData.get(DATA_FUSE);
		}
	}

	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
		this.lerpX = x;
		this.lerpY = y;
		this.lerpZ = z;
		this.lerpSteps = Math.max(1, steps);
	}

	@Override
	public void lerpMotion(double x, double y, double z) {
		this.setDeltaMovement(x, y, z);
	}

	public int getDisplayFuse() {
		return Math.max(0, this.fuseTicks);
	}

	public float getRollDeg(float partialTick) {
		return Mth.lerp(partialTick, this.prevRollDeg, this.rollDeg);
	}

	@Override
	public void tick() {
		super.tick();
		this.prevRollDeg = this.rollDeg;

		// 1. Apply strong heavy bomb gravity directly to velocity BEFORE moving
		double prevX = this.getX();
		double prevY = this.getY();
		double prevZ = this.getZ();

		Vec3 vel = this.getDeltaMovement();
		if (!this.isNoGravity()) {
			double gravity = this.isInWater() ? -0.025 : -0.068;
			vel = vel.add(0.0, gravity, 0.0);
		}

		// 2. Move bounding box cleanly
		this.move(MoverType.SELF, vel);

		double dx = this.getX() - prevX;
		double dy = this.getY() - prevY;
		double dz = this.getZ() - prevZ;

		// Preserve gravity-updated velocity (NEVER read pre-gravity deltaMovement back from Entity.move!)
		double vx = vel.x;
		double vy = vel.y;
		double vz = vel.z;

		// 3. Heavy muted wall ricochet
		if (this.horizontalCollision) {
			boolean hitX = Math.abs(vel.x) > 0.005 && Math.abs(dx - vel.x) > 0.002;
			boolean hitZ = Math.abs(vel.z) > 0.005 && Math.abs(dz - vel.z) > 0.002;
			if (hitX) {
				vx = -vel.x * 0.32;
			}
			if (hitZ) {
				vz = -vel.z * 0.32;
			}
		}

		// 4. Heavy stone-like floor bounce (1 crisp small hop, then settles flat)
		if (this.verticalCollision || (vel.y < 0.0 && Math.abs(dy - vel.y) > 0.002)) {
			if (vel.y < -0.15) {
				vy = -vel.y * 0.25;
				vx *= 0.60;
				vz *= 0.60;
				if (!this.level().isClientSide) {
					this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
							SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.55f, 0.72f);
				}
			} else if (vel.y > 0.04 && Math.abs(dy - vel.y) > 0.002) {
				vy = -vel.y * 0.25;
			} else if (this.onGround() || vel.y <= 0.0) {
				vy = 0.0;
			}
		}

		// 5. Heavy ground friction & air weight
		if (this.onGround() && Math.abs(vy) < 0.05) {
			vx *= 0.72;
			vz *= 0.72;
			if (vx * vx + vz * vz < 0.0004) {
				vx = 0.0;
				vz = 0.0;
			}
		} else {
			double drag = this.isInWater() ? 0.80 : 0.98;
			vx *= drag;
			vz *= drag;
		}

		this.setDeltaMovement(vx, vy, vz);

		// 6. Gentle client correction toward authoritative server coordinates
		if (this.level().isClientSide && this.lerpSteps > 0) {
			double nx = Mth.lerp(1.0 / this.lerpSteps, this.getX(), this.lerpX);
			double ny = Mth.lerp(1.0 / this.lerpSteps, this.getY(), this.lerpY);
			double nz = Mth.lerp(1.0 / this.lerpSteps, this.getZ(), this.lerpZ);
			this.setPos(nx, ny, nz);
			this.lerpSteps--;
		}

		// Accumulate tumble spin while moving, then settle upright once resting on the ground
		float horizSpeed = (float) Math.hypot(vx, vz);
		if (horizSpeed > 0.01f) {
			this.rollDeg += horizSpeed * 220.0f;
		} else if (this.onGround()) {
			float upright = Math.round(this.rollDeg / 360.0f) * 360.0f;
			this.rollDeg = Mth.lerp(0.35f, this.rollDeg, upright);
		}

		// 7. Fuse countdown (never call entityData.set from client!)
		this.fuseTicks = Math.max(0, this.fuseTicks - 1);

		if (this.level().isClientSide) {
			this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.48, this.getZ(), 0.0, 0.025, 0.0);
			if (this.fuseTicks % 3 == 0) {
				this.level().addParticle(ParticleTypes.FLAME, this.getX(), this.getY() + 0.50, this.getZ(), 0.0, 0.02, 0.0);
			}
		} else {
			if (this.fuseTicks == 24 || this.fuseTicks == 16 || this.fuseTicks == 10 || this.fuseTicks == 5) {
				this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
						SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.55f, 1.55f);
			}
			if (this.fuseTicks <= 0) {
				detonate((ServerLevel) this.level());
				this.discard();
			}
		}
	}

	private void detonate(ServerLevel level) {
		double x = this.getX();
		double y = this.getY() + 0.25;
		double z = this.getZ();
		level.explode(this, x, y, z, 2.5f, Level.ExplosionInteraction.NONE);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.15f, 1.0f);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);

		BlockPos center = BlockPos.containing(x, y, z);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 2, 2))) {
			BlockState bs = level.getBlockState(pos);
			if (bs.is(Blocks.CRACKED_STONE_BRICKS) || bs.is(Blocks.CRACKED_DEEPSLATE_BRICKS)
					|| bs.is(Blocks.CRACKED_NETHER_BRICKS) || bs.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
					|| bs.is(Blocks.COBWEB)) {
				level.destroyBlock(pos, false);
			}
		}

		AABB box = new AABB(x - 4.0, y - 2.0, z - 4.0, x + 4.0, y + 3.0, z + 4.0);
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box);
		for (LivingEntity entity : targets) {
			float dmg = (entity == this.owner) ? (entity.distanceToSqr(x, y, z) < 4.5 ? 2.0f : 0.0f) : 8.0f;
			if (dmg > 0.0f) {
				entity.hurt(this.damageSources().explosion(this, this.owner), dmg);
			}
			Vec3 push = entity.position().subtract(x, y - 0.5, z);
			if (push.lengthSqr() > 1.0e-4) {
				push = push.normalize().scale(0.65);
				entity.push(push.x, 0.35, push.z);
			}
		}
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		int fuse = tag.getShort("Fuse");
		this.fuseTicks = fuse;
		this.entityData.set(DATA_FUSE, fuse);
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		tag.putShort("Fuse", (short) this.fuseTicks);
	}
}