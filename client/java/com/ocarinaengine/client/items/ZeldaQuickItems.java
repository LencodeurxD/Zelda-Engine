package com.ocarinaengine.client.items;

import com.ocarinaengine.client.animation.ArticulatedPlayerModel;
import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.hud.QuickItemHud;
import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import com.ocarinaengine.inventory.ZeldaInventorySnapshot;
import com.ocarinaengine.network.QuickItemEffectPayload;
import com.ocarinaengine.network.UseQuickItemPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Quick items list + Step 1 execution (ZR = ALWAYS USE) + polished OoT player animations:
 * - Deku Stick: heroic forward torch stance + whip/thrust wave + full-body weight
 * - Deku Nut: coiled wind-up -> crisp downward foot-slam -> cushioned recoil
 * - Bomb: cushioned overhead lift -> two-handed wind-up & explosive forward heave
 * - Fairy Slingshot: hold ZR to enter bow aim mode with reticle, release ZR to fire dead-center
 * - Fairy Ocarina: smooth flute performance with chest lean and elbow framing
 */
public final class ZeldaQuickItems {
	public static final List<QuickItem> ITEMS = new ArrayList<>();
	private static int selected = 0;
	private static QuickItem activeHeldItem = null;
	private static boolean stickLit = false;
	private static int stickLitTicks = 0;
	private static float nutFlashAlpha = 0.0f;

	private static long waveStartNanos = 0L;
	private static final float WAVE_DURATION_SEC = 0.40f;

	private static long nutThrowStartNanos = 0L;
	private static final float NUT_THROW_DURATION_SEC = 0.56f;

	private static long bombLiftStartNanos = 0L;
	private static final float BOMB_LIFT_DURATION_SEC = 0.22f;

	private static long bombThrowStartNanos = 0L;
	private static final float BOMB_THROW_DURATION_SEC = 0.38f;

	private static boolean slingshotAiming = false;
	private static long slingshotAimStartNanos = 0L;
	private static long slingshotRecoilStartNanos = 0L;
	private static final float SLINGSHOT_RECOIL_DURATION_SEC = 0.22f;
	private static boolean aimHoveringEntity = false;

	private static long ocarinaStartNanos = 0L;
	private static final float OCARINA_DURATION_SEC = 1.40f;

	private static float holdPoseBlend = 0.0f;
	private static long lastPoseNanos = 0L;
	private static float wholeOffsetYBlocks = 0.0f;
	private static float wholeLeanForwardRad = 0.0f;

	private ZeldaQuickItems() {}

	public static boolean hasItems() {
		return !ITEMS.isEmpty();
	}

	public static int size() {
		return ITEMS.size();
	}

	public static int selectedIndex() {
		return ITEMS.isEmpty() ? 0 : Mth.clamp(selected, 0, ITEMS.size() - 1);
	}

	public static QuickItem current() {
		return ITEMS.isEmpty() ? null : ITEMS.get(selectedIndex());
	}

	public static QuickItem inSlot(int slot) {
		for (QuickItem item : ITEMS) {
			if (item.slot() == slot) {
				return item;
			}
		}
		return null;
	}

	public static boolean isSelected(QuickItem item) {
		return item != null && item == current();
	}

	public static void selectItem(QuickItem item) {
		int idx = ITEMS.indexOf(item);
		if (idx >= 0) {
			selected = idx;
			save();
		}
	}

	public static void select(int index) {
		if (!ITEMS.isEmpty()) {
			selected = Mth.clamp(index, 0, ITEMS.size() - 1);
			save();
		}
	}

	public static void scroll(int delta) {
		select(selectedIndex() + delta);
	}

	public static QuickItem grantFromChest(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		QuickItem mapped = mapStack(stack);
		for (int i = 0; i < ITEMS.size(); i++) {
			QuickItem existing = ITEMS.get(i);
			if (existing.id().equals(mapped.id())) {
				int newCount = Math.min(existing.maxCount(), existing.count() + mapped.count());
				QuickItem updated = new QuickItem(
						existing.slot(), existing.id(), existing.name(), existing.description(),
						existing.icon(), newCount, existing.maxCount());
				ITEMS.set(i, updated);
				selected = i;
				save();
				return updated;
			}
		}
		ITEMS.add(mapped);
		selected = ITEMS.size() - 1;
		save();
		return mapped;
	}

	public static QuickItem mapStack(ItemStack stack) {
		int qty = Math.max(1, stack.getCount());
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		String rawName = customName != null ? customName.getString() : stack.getHoverName().getString();
		if (stack.is(Items.STICK) || rawName.equalsIgnoreCase("Deku Stick")) {
			return new QuickItem(0, "deku_stick", "Deku Stick",
					"A long wooden stick. Press ZR to wave it or reach toward a torch to ignite it.",
					Items.STICK, Math.min(10, qty), 10);
		}
		if (stack.is(Items.COCOA_BEANS) || rawName.equalsIgnoreCase("Deku Nut")) {
			return new QuickItem(3, "deku_nut", "Deku Nut",
					"A hard-shelled nut. Slam it at your feet to stun enemies with a blinding flash.",
					Items.COCOA_BEANS, Math.min(20, qty), 20);
		}
		if (stack.is(Items.BOW) || rawName.equalsIgnoreCase("Fairy Slingshot")) {
			return new QuickItem(1, "fairy_slingshot", "Fairy Slingshot",
					"Hold ZR to aim with the reticle, then release to fire a Deku Seed.",
					Items.BOW, 1, 1);
		}
		if (stack.is(Items.TNT) || rawName.equalsIgnoreCase("Bomb")) {
			return new QuickItem(2, "bomb", "Bomb",
					"Press ZR to lift overhead, then ZR again to throw (or Sneak + ZR to set down).",
					Items.TNT, Math.min(20, qty), 20);
		}
		if (stack.is(Items.AMETHYST_SHARD) || rawName.equalsIgnoreCase("Fairy Ocarina")) {
			return new QuickItem(7, "fairy_ocarina", "Fairy Ocarina",
					"A carved wooden ocarina gifted by Saria. Press ZR to play a melody.",
					Items.AMETHYST_SHARD, 1, 1);
		}
		int freeSlot = firstFreeSlot();
		String id = stack.getItem().toString().toLowerCase().replace(':', '_');
		return new QuickItem(freeSlot, id, rawName,
				"An item discovered inside a treasure chest.", stack.getItem(), Math.min(99, qty), 99);
	}

	private static int firstFreeSlot() {
		for (int s = 0; s < 22; s++) {
			if (inSlot(s) == null) {
				return s;
			}
		}
		return 0;
	}

	public static void useCurrent() {
		QuickItem item = current();
		if (item != null) {
			QuickItemHud.triggerUseAnimation();
			use(item);
		}
	}

	public static QuickItem activeItem() {
		return activeHeldItem;
	}

	public static boolean isHoldingBomb() {
		return activeHeldItem != null && "bomb".equals(activeHeldItem.id()) && !ZeldaCombatSystem.isSwordDrawn();
	}

	public static boolean isPlayingOcarina() {
		return timedProgress(ocarinaStartNanos, OCARINA_DURATION_SEC) > 0.0f && !ZeldaCombatSystem.isSwordDrawn();
	}

	public static boolean isAimingSlingshot() {
		return slingshotAiming && !ZeldaCombatSystem.isSwordDrawn();
	}

	public static boolean isSlingshotRecoilActive() {
		return timedProgress(slingshotRecoilStartNanos, SLINGSHOT_RECOIL_DURATION_SEC) > 0.0f && !ZeldaCombatSystem.isSwordDrawn();
	}

	public static boolean isAimHoveringEntity() {
		return isAimingSlingshot() && aimHoveringEntity;
	}

	public static float slingshotChargeProgress() {
		if (!isAimingSlingshot() || slingshotAimStartNanos == 0L) {
			return 0.0f;
		}
		float elapsed = (System.nanoTime() - slingshotAimStartNanos) / 1.0e9f;
		return Mth.clamp(elapsed / 0.28f, 0.0f, 1.0f);
	}

	public static float bombLiftBlend() {
		if (timedProgress(bombThrowStartNanos, BOMB_THROW_DURATION_SEC) > 0.0f) {
			return 1.0f;
		}
		if (!isHoldingBomb() || bombLiftStartNanos == 0L) {
			return 0.0f;
		}
		float elapsed = (System.nanoTime() - bombLiftStartNanos) / 1.0e9f;
		float t = Mth.clamp(elapsed / BOMB_LIFT_DURATION_SEC, 0.0f, 1.0f);
		return smoothstep(0.0f, 1.0f, t);
	}

	public static float bombThrowProgress() {
		return timedProgress(bombThrowStartNanos, BOMB_THROW_DURATION_SEC);
	}

	public static float bombThrowWindupFactor() {
		float p = bombThrowProgress();
		if (p <= 0.0f || p >= 0.32f) {
			return 0.0f;
		}
		return smoothstep(0.0f, 0.28f, p);
	}

	public static boolean isShowingDekuNutInHand() {
		float p = timedProgress(nutThrowStartNanos, NUT_THROW_DURATION_SEC);
		return p > 0.0f && p < 0.48f && !ZeldaCombatSystem.isSwordDrawn();
	}

	public static boolean isHoldingQuickItem() {
		if (ZeldaCombatSystem.isSwordDrawn()) {
			return false;
		}
		return activeHeldItem != null
				|| timedProgress(nutThrowStartNanos, NUT_THROW_DURATION_SEC) > 0.0f
				|| timedProgress(bombThrowStartNanos, BOMB_THROW_DURATION_SEC) > 0.0f
				|| isAimingSlingshot()
				|| isSlingshotRecoilActive()
				|| isPlayingOcarina();
	}

	public static boolean isStickLit() {
		return activeHeldItem != null && "deku_stick".equals(activeHeldItem.id()) && stickLit;
	}

	public static int stickLitTicksRemaining() {
		return stickLitTicks;
	}

	private static float timedProgress(long startNanos, float durationSec) {
		if (startNanos == 0L) {
			return 0.0f;
		}
		float elapsed = (System.nanoTime() - startNanos) / 1.0e9f;
		if (elapsed >= durationSec) {
			return 0.0f;
		}
		return Mth.clamp(elapsed / durationSec, 0.0f, 1.0f);
	}

	public static float stickSwingProgress() {
		float p = timedProgress(waveStartNanos, WAVE_DURATION_SEC);
		if (p == 0.0f) {
			waveStartNanos = 0L;
		}
		return p;
	}

	public static float nutFlashAlpha() {
		return nutFlashAlpha;
	}

	public static float getWholeOffsetYBlocks() {
		return wholeOffsetYBlocks;
	}

	public static float getWholeLeanForwardRad() {
		return wholeLeanForwardRad;
	}

	public static void beginSlingshotAim() {
		QuickItem cur = current();
		if (cur == null || !"fairy_slingshot".equals(cur.id()) || ZeldaCombatSystem.isSwordDrawn() || ZeldaLedgeSystem.isActive()) {
			return;
		}
		if (activeHeldItem != null) {
			cancelActiveItem();
		}
		ocarinaStartNanos = 0L;
		if (!slingshotAiming) {
			slingshotAiming = true;
			slingshotAimStartNanos = System.nanoTime();
			slingshotRecoilStartNanos = 0L;
			aimHoveringEntity = false;
			ClientPlayNetworking.send(new UseQuickItemPayload(
					"fairy_slingshot", UseQuickItemPayload.ACTION_AIM_START, -1, EngineState.camYaw, EngineState.camPitch));
		}
	}

	public static void cancelSlingshotAim() {
		slingshotAiming = false;
		aimHoveringEntity = false;
	}

	public static void tickSlingshotAim(Minecraft mc, boolean triggerHeld) {
		if (!slingshotAiming) {
			return;
		}
		QuickItem cur = current();
		if (mc.player == null || mc.level == null || mc.screen != null
				|| cur == null || !"fairy_slingshot".equals(cur.id())
				|| ZeldaCombatSystem.isSwordDrawn() || ZeldaLedgeSystem.isActive()) {
			cancelSlingshotAim();
			return;
		}
		if (!triggerHeld) {
			releaseSlingshotShot(mc);
			return;
		}
		AimSolution sol = solveCameraAim(mc);
		aimHoveringEntity = sol.entityId >= 0;
	}

	public static void releaseSlingshotShot(Minecraft mc) {
		if (!slingshotAiming || mc.player == null || mc.level == null) {
			cancelSlingshotAim();
			return;
		}
		slingshotAiming = false;
		aimHoveringEntity = false;
		slingshotRecoilStartNanos = System.nanoTime();
		QuickItemHud.triggerUseAnimation();

		AimSolution sol = solveCameraAim(mc);
		LivingEntity locked = ZeldaTargetingSystem.getLockedTarget();
		int targetId = sol.entityId >= 0 ? sol.entityId : (locked != null ? locked.getId() : -1);
		ClientPlayNetworking.send(new UseQuickItemPayload(
				"fairy_slingshot", UseQuickItemPayload.ACTION_USE, targetId, sol.yawDeg, sol.pitchDeg));
	}

	private record AimSolution(float yawDeg, float pitchDeg, int entityId) {}

	private static AimSolution solveCameraAim(Minecraft mc) {
		Vec3 camPos = EngineState.aimCameraPos != null && EngineState.aimCameraPos.lengthSqr() > 0.01
				? EngineState.aimCameraPos
				: mc.player.getEyePosition();
		Vec3 camDir = Vec3.directionFromRotation(EngineState.camPitch, EngineState.camYaw).normalize();
		double range = 48.0;
		Vec3 rayEnd = camPos.add(camDir.scale(range));

		HitResult blockHit = mc.level.clip(new ClipContext(
				camPos, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		Vec3 hitPos = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : rayEnd;
		double bestDistSq = hitPos.distanceToSqr(camPos);

		AABB searchBox = mc.player.getBoundingBox().expandTowards(camDir.scale(range)).inflate(1.25);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				mc.player, camPos, hitPos, searchBox,
				e -> !e.isSpectator() && e.isPickable() && e != mc.player, bestDistSq);
		int hitEntityId = -1;
		if (entityHit != null) {
			hitPos = entityHit.getEntity().getBoundingBox().getCenter();
			hitEntityId = entityHit.getEntity().getId();
		}

		Vec3 fromEye = hitPos.subtract(mc.player.getEyePosition());
		if (fromEye.lengthSqr() < 0.01) {
			return new AimSolution(EngineState.camYaw, EngineState.camPitch, hitEntityId);
		}
		float shootYaw = (float) Math.toDegrees(Math.atan2(-fromEye.x, fromEye.z));
		double horiz = Math.sqrt(fromEye.x * fromEye.x + fromEye.z * fromEye.z);
		float shootPitch = (float) -Math.toDegrees(Math.atan2(fromEye.y, Math.max(1.0e-4, horiz)));
		return new AimSolution(shootYaw, shootPitch, hitEntityId);
	}

	public static boolean cancelActiveItem() {
		boolean didCancel = false;
		if (slingshotAiming) {
			cancelSlingshotAim();
			didCancel = true;
		}
		if (activeHeldItem != null) {
			String prevId = activeHeldItem.id();
			activeHeldItem = null;
			stickLit = false;
			stickLitTicks = 0;
			waveStartNanos = 0L;
			bombLiftStartNanos = 0L;
			ClientPlayNetworking.send(new UseQuickItemPayload(
					prevId, UseQuickItemPayload.ACTION_PUT_AWAY, -1, 0.0f, 0.0f));
			didCancel = true;
		}
		if (ocarinaStartNanos != 0L) {
			ocarinaStartNanos = 0L;
			ClientPlayNetworking.send(new UseQuickItemPayload(
					"fairy_ocarina", UseQuickItemPayload.ACTION_PUT_AWAY, -1, 0.0f, 0.0f));
			didCancel = true;
		}
		return didCancel;
	}

	/**
	 * Executes the selected Quick Item (ZR / G). ZR ALWAYS uses and NEVER puts away.
	 */
	public static void use(QuickItem item) {
		if (item == null || item.count() <= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LivingEntity lockedTarget = ZeldaTargetingSystem.getLockedTarget();
		int targetId = lockedTarget != null ? lockedTarget.getId() : -1;
		float playerYaw = mc.player != null ? mc.player.getYRot() : EngineState.camYaw;

		switch (item.id()) {
			case "deku_stick" -> {
				ocarinaStartNanos = 0L;
				cancelSlingshotAim();
				if (activeHeldItem == null || !"deku_stick".equals(activeHeldItem.id())) {
					activeHeldItem = item;
					stickLit = false;
					stickLitTicks = 0;
					waveStartNanos = 0L;
					ClientPlayNetworking.send(new UseQuickItemPayload(
							"deku_stick", UseQuickItemPayload.ACTION_USE, targetId, playerYaw, 0.0f));
				} else {
					waveStartNanos = System.nanoTime();
					ClientPlayNetworking.send(new UseQuickItemPayload(
							"deku_stick", UseQuickItemPayload.ACTION_WAVE_STICK, targetId, playerYaw, 0.0f));
				}
			}
			case "deku_nut" -> {
				activeHeldItem = null;
				stickLit = false;
				ocarinaStartNanos = 0L;
				cancelSlingshotAim();
				nutThrowStartNanos = System.nanoTime();
				ClientPlayNetworking.send(new UseQuickItemPayload(
						"deku_nut", UseQuickItemPayload.ACTION_USE, targetId, playerYaw, 0.0f));
			}
			case "bomb" -> {
				ocarinaStartNanos = 0L;
				stickLit = false;
				cancelSlingshotAim();
				if (activeHeldItem == null || !"bomb".equals(activeHeldItem.id())) {
					activeHeldItem = item;
					bombLiftStartNanos = System.nanoTime();
					ClientPlayNetworking.send(new UseQuickItemPayload(
							"bomb", UseQuickItemPayload.ACTION_USE, -1, playerYaw, 0.0f));
				} else {
					activeHeldItem = null;
					bombThrowStartNanos = System.nanoTime();
					float throwYaw = playerYaw;
					float throwPitch = Mth.clamp(EngineState.camPitch, -30.0f, 35.0f);
					ClientPlayNetworking.send(new UseQuickItemPayload(
							"bomb", UseQuickItemPayload.ACTION_USE, targetId, throwYaw, throwPitch));
				}
			}
			case "fairy_slingshot" -> beginSlingshotAim();
			case "fairy_ocarina" -> {
				activeHeldItem = null;
				stickLit = false;
				cancelSlingshotAim();
				ocarinaStartNanos = System.nanoTime();
				ClientPlayNetworking.send(new UseQuickItemPayload(
						"fairy_ocarina", UseQuickItemPayload.ACTION_USE, -1, playerYaw, 0.0f));
			}
			default -> {}
		}
	}

	/**
	 * Applies full-body OoT-grade procedural poses with anticipation, follow-through,
	 * articulated elbows/knees, and PoseStack body sink/lean.
	 */
	public static void applyHeldPose(HumanoidModel<?> model, LivingEntity entity, float limbSwingAmount) {
		long now = System.nanoTime();
		if (lastPoseNanos == 0L) {
			lastPoseNanos = now;
		}
		float dt = Mth.clamp((now - lastPoseNanos) / 1.0e9f, 0.0f, 0.05f);
		lastPoseNanos = now;

		boolean activeNow = isHoldingQuickItem();
		float ocarinaP = timedProgress(ocarinaStartNanos, OCARINA_DURATION_SEC);
		float nutP = timedProgress(nutThrowStartNanos, NUT_THROW_DURATION_SEC);
		float bombThrowP = bombThrowProgress();
		float recoilP = timedProgress(slingshotRecoilStartNanos, SLINGSHOT_RECOIL_DURATION_SEC);
		float speedRate = (nutP > 0.0f || bombThrowP > 0.0f || slingshotAiming || recoilP > 0.0f) ? 28.0f : 16.0f;

		float target = activeNow ? 1.0f : 0.0f;
		holdPoseBlend = Mth.clamp(holdPoseBlend + (target - holdPoseBlend) * Math.min(1.0f, dt * speedRate), 0.0f, 1.0f);
		if (holdPoseBlend <= 0.004f) {
			wholeOffsetYBlocks = 0.0f;
			wholeLeanForwardRad = 0.0f;
			return;
		}

		float b = smoothstep(0.0f, 1.0f, holdPoseBlend);
		float t = (now & 0xFFFFFFFFL) / 1.0e9f;
		float moveW = Mth.clamp(limbSwingAmount * 5.0f, 0.0f, 1.0f);
		float targetWholeY = 0.0f;
		float targetWholeLean = 0.0f;

		// 1. Fairy Ocarina (two-handed flute performance at mouth with chest sway)
		if (ocarinaP > 0.0f) {
			float env = smoothstep(0.0f, 0.16f, ocarinaP) * (1.0f - smoothstep(0.84f, 1.0f, ocarinaP));
			float bw = b * env;
			float noteWave = Mth.sin(t * 7.2f) * 0.045f;
			float sway = Mth.sin(t * 3.1f) * 0.055f;

			targetWholeY = -0.025f * bw;
			targetWholeLean = 0.04f * bw;
			model.head.xRot = Mth.lerp(bw, model.head.xRot, 0.14f + noteWave);
			model.head.yRot = Mth.lerp(bw, model.head.yRot, sway * 0.6f);
			model.head.zRot = Mth.lerp(bw, model.head.zRot, sway * 0.8f);
			model.body.xRot += 0.06f * bw;
			model.body.zRot = Mth.lerp(bw, model.body.zRot, sway * 0.4f);

			model.rightArm.xRot = Mth.lerp(bw, model.rightArm.xRot, -1.42f + noteWave * 0.5f);
			model.rightArm.yRot = Mth.lerp(bw, model.rightArm.yRot, 0.56f);
			model.rightArm.zRot = Mth.lerp(bw, model.rightArm.zRot, -0.14f);
			model.leftArm.xRot = Mth.lerp(bw, model.leftArm.xRot, -1.36f - noteWave * 0.4f);
			model.leftArm.yRot = Mth.lerp(bw, model.leftArm.yRot, -0.52f);
			model.leftArm.zRot = Mth.lerp(bw, model.leftArm.zRot, 0.18f);

			setForearm(model, true, bw, -1.08f, 0.10f, 0.0f);
			setForearm(model, false, bw, -1.02f, -0.10f, 0.0f);
			setShin(model, true, bw * (1.0f - moveW), 0.14f);
			setShin(model, false, bw * (1.0f - moveW), 0.14f);
			syncPoseParts(model);
			updateWholeBody(targetWholeY, targetWholeLean, dt);
			return;
		}

		// 2. Deku Nut Ground Slam (wind-up coil -> whip slam toward feet -> spring settle)
		if (nutP > 0.0f) {
			float armX;
			float forearmX;
			float chestX;
			float kneeDip;
			float swingEnvelope;
			if (nutP < 0.24f) {
				float u = smoothstep(0.0f, 0.24f, nutP);
				armX = Mth.lerp(u, -0.45f, -2.45f);
				forearmX = Mth.lerp(u, -0.25f, -0.78f);
				chestX = Mth.lerp(u, 0.0f, -0.18f);
				kneeDip = u * 0.35f;
				swingEnvelope = u * 0.4f;
			} else if (nutP < 0.52f) {
				float u = (nutP - 0.24f) / 0.28f;
				float whip = (float) Math.pow(u, 0.55);
				armX = Mth.lerp(whip, -2.45f, 0.42f);
				forearmX = Mth.lerp(whip, -0.78f, -0.06f);
				chestX = Mth.lerp(whip, -0.18f, 0.52f);
				kneeDip = Mth.lerp(whip, 0.35f, 1.0f);
				swingEnvelope = Mth.lerp(whip, 0.4f, 1.0f);
			} else {
				float u = smoothstep(0.52f, 1.0f, nutP);
				armX = Mth.lerp(u, 0.42f, -0.15f);
				forearmX = Mth.lerp(u, -0.06f, -0.22f);
				chestX = Mth.lerp(u, 0.52f, 0.0f);
				kneeDip = 1.0f - u;
				swingEnvelope = 1.0f - u;
			}

			targetWholeY = -0.12f * kneeDip * b;
			targetWholeLean = 0.28f * kneeDip * b;
			model.body.xRot += chestX * b;
			model.body.yRot = Mth.lerp(b, model.body.yRot, -0.26f * swingEnvelope);
			model.head.xRot = Mth.lerp(b, model.head.xRot, 0.34f * kneeDip);
			model.rightArm.xRot = Mth.lerp(b, model.rightArm.xRot, armX);
			model.rightArm.yRot = Mth.lerp(b, model.rightArm.yRot, -0.22f * swingEnvelope);
			model.rightArm.zRot = Mth.lerp(b, model.rightArm.zRot, 0.14f * swingEnvelope);
			model.leftArm.xRot = Mth.lerp(b, model.leftArm.xRot, 0.42f * swingEnvelope);
			model.leftArm.zRot = Mth.lerp(b, model.leftArm.zRot, -0.24f * swingEnvelope);
			setForearm(model, true, b, forearmX, 0.0f, 0.0f);
			setForearm(model, false, b, -0.35f * swingEnvelope, 0.0f, 0.0f);
			if (moveW < 0.25f) {
				float g = b * (1.0f - moveW * 4.0f);
				model.rightLeg.xRot = Mth.lerp(g, model.rightLeg.xRot, -0.52f * kneeDip);
				model.leftLeg.xRot = Mth.lerp(g, model.leftLeg.xRot, 0.38f * kneeDip);
				setShin(model, true, g, 0.88f * kneeDip);
				setShin(model, false, g, 0.36f * kneeDip);
			}
			syncPoseParts(model);
			updateWholeBody(targetWholeY, targetWholeLean, dt);
			return;
		}

		// 3. Bomb Two-Handed Throw Heave (wind-up overhead -> violent forward heave -> follow-through)
		if (bombThrowP > 0.0f) {
			float armsX;
			float forearmsX;
			float chestX;
			float heaveW;
			float stride = 0.0f;
			if (bombThrowP < 0.28f) {
				float u = smoothstep(0.0f, 0.28f, bombThrowP);
				armsX = Mth.lerp(u, -2.55f, -3.08f);
				forearmsX = Mth.lerp(u, -0.52f, -0.78f);
				chestX = Mth.lerp(u, -0.06f, -0.26f);
				heaveW = 0.0f;
				stride = u * 0.45f;
			} else if (bombThrowP < 0.62f) {
				float u = (bombThrowP - 0.28f) / 0.34f;
				float snap = (float) Math.pow(u, 0.56);
				armsX = Mth.lerp(snap, -3.08f, -0.58f);
				forearmsX = Mth.lerp(snap, -0.78f, -0.06f);
				chestX = Mth.lerp(snap, -0.26f, 0.44f);
				heaveW = snap;
				stride = Mth.lerp(snap, 0.45f, 1.0f);
			} else {
				float u = smoothstep(0.62f, 1.0f, bombThrowP);
				armsX = Mth.lerp(u, -0.58f, -0.10f);
				forearmsX = Mth.lerp(u, -0.06f, -0.18f);
				chestX = Mth.lerp(u, 0.44f, 0.0f);
				heaveW = 1.0f - u;
				stride = 1.0f - u;
			}

			targetWholeY = -0.09f * stride * b;
			targetWholeLean = (chestX * 0.48f) * b;
			model.body.xRot += chestX * b;
			model.head.xRot = Mth.lerp(b, model.head.xRot, -chestX * 0.45f);
			float handInward = 0.22f * (1.0f - heaveW);
			model.rightArm.xRot = Mth.lerp(b, model.rightArm.xRot, armsX);
			model.rightArm.yRot = Mth.lerp(b, model.rightArm.yRot, handInward);
			model.rightArm.zRot = Mth.lerp(b, model.rightArm.zRot, -0.16f + 0.10f * heaveW);
			model.leftArm.xRot = Mth.lerp(b, model.leftArm.xRot, armsX);
			model.leftArm.yRot = Mth.lerp(b, model.leftArm.yRot, -handInward);
			model.leftArm.zRot = Mth.lerp(b, model.leftArm.zRot, 0.16f - 0.10f * heaveW);
			setForearm(model, true, b, forearmsX, 0.0f, 0.0f);
			setForearm(model, false, b, forearmsX, 0.0f, 0.0f);
			syncPoseParts(model);
			updateWholeBody(targetWholeY, targetWholeLean, dt);
			return;
		}

		// 4. Fairy Slingshot Bow-Style Aim + Elastic Shot Recoil
		if (isAimingSlingshot() || recoilP > 0.0f) {
			float aimW = isAimingSlingshot() ? 1.0f : (1.0f - smoothstep(0.0f, 1.0f, recoilP));
			float draw = isAimingSlingshot()
					? slingshotChargeProgress()
					: Math.max(0.0f, 1.0f - recoilP * 4.5f);
			float pitchRad = Mth.clamp(EngineState.camPitch, -72.0f, 75.0f) * Mth.DEG_TO_RAD;
			float recoilKick = recoilP > 0.0f ? Mth.sin(recoilP * Mth.PI) * 0.24f : 0.0f;

			targetWholeY = -0.055f * b * aimW * (1.0f - moveW * 0.6f);
			model.body.yRot = Mth.lerp(b, model.body.yRot, 0.56f * aimW);
			model.body.xRot += pitchRad * 0.22f * b;
			model.head.yRot = Mth.lerp(b, model.head.yRot, -0.52f * aimW);
			model.head.xRot = Mth.lerp(b, model.head.xRot, pitchRad * 0.72f);

			// Right arm aims the forked wooden frame straight downrange
			model.rightArm.xRot = Mth.lerp(b, model.rightArm.xRot, -1.52f + pitchRad * 0.85f - recoilKick * 0.35f);
			model.rightArm.yRot = Mth.lerp(b, model.rightArm.yRot, -0.14f);
			model.rightArm.zRot = Mth.lerp(b, model.rightArm.zRot, 0.08f);
			setForearm(model, true, b, -0.08f, 0.0f, 0.0f);

			// Left arm draws the leather pouch back toward Link's cheek
			float drawBackY = 0.68f + 0.34f * draw - recoilKick;
			model.leftArm.xRot = Mth.lerp(b, model.leftArm.xRot, -1.36f + pitchRad * 0.75f);
			model.leftArm.yRot = Mth.lerp(b, model.leftArm.yRot, drawBackY);
			model.leftArm.zRot = Mth.lerp(b, model.leftArm.zRot, 0.14f);
			setForearm(model, false, b, -0.68f - 0.68f * draw + recoilKick * 1.4f, -0.18f * draw, 0.0f);

			if (moveW < 0.05f) {
				float s = b * aimW * (1.0f - moveW / 0.05f);
				model.rightLeg.xRot = Mth.lerp(s, model.rightLeg.xRot, 0.24f);
				model.rightLeg.zRot = Mth.lerp(s, model.rightLeg.zRot, 0.09f);
				model.leftLeg.xRot = Mth.lerp(s, model.leftLeg.xRot, -0.28f);
				model.leftLeg.zRot = Mth.lerp(s, model.leftLeg.zRot, -0.09f);
				setShin(model, true, s, 0.22f);
				setShin(model, false, s, 0.34f);
			}
			syncPoseParts(model);
			updateWholeBody(targetWholeY, 0.0f, dt);
			return;
		}

		// 5. Bomb Overhead Carry (cushioned lift transition + balanced shoulder/elbow cradle)
		if (isHoldingBomb()) {
			float lift = bombLiftBlend();
			float walkBob = Mth.sin(t * 9.5f) * 0.045f * moveW + Mth.sin(t * 2.8f) * 0.025f;
			targetWholeY = -0.04f * b * lift;
			targetWholeLean = -0.05f * b * lift;

			float shoulderX = Mth.lerp(lift, -0.95f, -2.52f) + walkBob;
			float shoulderY = Mth.lerp(lift, 0.38f, 0.28f);
			float shoulderZ = Mth.lerp(lift, -0.26f, -0.24f);
			float forearmX = Mth.lerp(lift, -0.82f, -0.50f);

			model.body.xRot += -0.07f * lift * b;
			model.head.xRot = Mth.lerp(b, model.head.xRot, 0.08f * lift);
			model.rightArm.xRot = Mth.lerp(b, model.rightArm.xRot, shoulderX);
			model.rightArm.yRot = Mth.lerp(b, model.rightArm.yRot, shoulderY);
			model.rightArm.zRot = Mth.lerp(b, model.rightArm.zRot, shoulderZ);
			model.leftArm.xRot = Mth.lerp(b, model.leftArm.xRot, shoulderX);
			model.leftArm.yRot = Mth.lerp(b, model.leftArm.yRot, -shoulderY);
			model.leftArm.zRot = Mth.lerp(b, model.leftArm.zRot, -shoulderZ);
			setForearm(model, true, b, forearmX, 0.0f, 0.0f);
			setForearm(model, false, b, forearmX, 0.0f, 0.0f);
			syncPoseParts(model);
			updateWholeBody(targetWholeY, targetWholeLean, dt);
			return;
		}

		// 6. Deku Stick Forward Torch Pose + Anticipation/Thrust/Settle ZR Wave
		float waveT = stickSwingProgress();
		float breath = Mth.sin(t * 2.5f) * 0.025f;
		float armX = -0.92f + breath;
		float armY = -0.14f;
		float armZ = 0.10f;
		float forearmX = -0.44f;
		float chestX = 0.04f;
		float chestY = -0.12f;

		if (waveT > 0.0f) {
			if (waveT < 0.22f) {
				float u = smoothstep(0.0f, 0.22f, waveT);
				armX = Mth.lerp(u, -0.92f, -1.25f);
				armY = Mth.lerp(u, -0.14f, -0.44f);
				forearmX = Mth.lerp(u, -0.44f, -0.78f);
				chestX = Mth.lerp(u, 0.04f, -0.06f);
				chestY = Mth.lerp(u, -0.12f, -0.26f);
			} else if (waveT < 0.64f) {
				float u = (waveT - 0.22f) / 0.42f;
				float snap = (float) Math.pow(u, 0.65);
				float dip = Mth.sin(u * Mth.PI);
				armX = Mth.lerp(snap, -1.25f, -1.36f) + dip * 0.24f;
				armY = Mth.lerp(snap, -0.44f, 0.44f);
				armZ = 0.10f - 0.18f * dip;
				forearmX = Mth.lerp(snap, -0.78f, -0.12f);
				chestX = 0.20f * dip;
				chestY = Mth.lerp(snap, -0.26f, 0.22f);
				targetWholeLean = 0.12f * dip * b;
				targetWholeY = -0.05f * dip * b;
			} else {
				float u = smoothstep(0.64f, 1.0f, waveT);
				armX = Mth.lerp(u, -1.36f, -0.92f);
				armY = Mth.lerp(u, 0.44f, -0.14f);
				armZ = 0.10f;
				forearmX = Mth.lerp(u, -0.12f, -0.44f);
				chestX = Mth.lerp(u, 0.08f, 0.04f);
				chestY = Mth.lerp(u, 0.22f, -0.12f);
			}
		}

		model.rightArm.xRot = Mth.lerp(b, model.rightArm.xRot, armX);
		model.rightArm.yRot = Mth.lerp(b, model.rightArm.yRot, armY);
		model.rightArm.zRot = Mth.lerp(b, model.rightArm.zRot, armZ);
		model.body.xRot += chestX * b;
		model.body.yRot = Mth.lerp(b, model.body.yRot, chestY);
		// Natural relaxed balance in free left arm while carrying the long stick
		model.leftArm.zRot = Mth.lerp(b * 0.45f, model.leftArm.zRot, -0.12f);
		setForearm(model, true, b, forearmX, 0.0f, 0.0f);
		syncPoseParts(model);
		updateWholeBody(targetWholeY, targetWholeLean, dt);
	}

	private static void updateWholeBody(float targetY, float targetLean, float dt) {
		float k = Math.min(1.0f, dt * 22.0f);
		wholeOffsetYBlocks += (targetY - wholeOffsetYBlocks) * k;
		wholeLeanForwardRad += (targetLean - wholeLeanForwardRad) * k;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / Math.max(1.0e-5f, edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}

	private static void setForearm(HumanoidModel<?> model, boolean right, float blend,
							   float targetX, float targetY, float targetZ) {
		if (model instanceof ArticulatedPlayerModel apm) {
			ModelPart forearm = right ? apm.ocarina$rightForearm() : apm.ocarina$leftForearm();
			if (forearm != null) {
				forearm.xRot = Mth.lerp(blend, forearm.xRot, targetX);
				forearm.yRot = Mth.lerp(blend, forearm.yRot, targetY);
				forearm.zRot = Mth.lerp(blend, forearm.zRot, targetZ);
				ModelPart sleeve = right ? apm.ocarina$rightForearmSleeve() : apm.ocarina$leftForearmSleeve();
				if (sleeve != null) {
					sleeve.copyFrom(forearm);
				}
			}
		}
	}

	private static void setShin(HumanoidModel<?> model, boolean right, float blend, float targetX) {
		if (model instanceof ArticulatedPlayerModel apm) {
			ModelPart shin = right ? apm.ocarina$rightShin() : apm.ocarina$leftShin();
			if (shin != null) {
				shin.xRot = Mth.lerp(blend, shin.xRot, targetX);
				ModelPart pants = right ? apm.ocarina$rightShinPants() : apm.ocarina$leftShinPants();
				if (pants != null) {
					pants.copyFrom(shin);
				}
			}
		}
	}

	private static void syncPoseParts(HumanoidModel<?> model) {
		if (model instanceof PlayerModel<?> pm) {
			pm.rightSleeve.copyFrom(pm.rightArm);
			pm.leftSleeve.copyFrom(pm.leftArm);
			pm.rightPants.copyFrom(pm.rightLeg);
			pm.leftPants.copyFrom(pm.leftLeg);
			pm.jacket.copyFrom(pm.body);
			pm.hat.copyFrom(pm.head);
		}
	}

	public static void tickClient(Minecraft mc) {
		if (activeHeldItem != null && (ZeldaCombatSystem.isSwordDrawn() || ZeldaLedgeSystem.isActive())) {
			cancelActiveItem();
		}
		if (slingshotAiming && (ZeldaCombatSystem.isSwordDrawn() || ZeldaLedgeSystem.isActive())) {
			cancelSlingshotAim();
		}
		if (nutFlashAlpha > 0.0f) {
			nutFlashAlpha = Math.max(0.0f, nutFlashAlpha - 0.12f);
		}
		if (stickLit && stickLitTicks > 0) {
			stickLitTicks--;
		}
		// Spawn flame & smoke directly at the forward-pointing tip of the 2x Deku Stick
		if (isStickLit() && mc.player != null && mc.level != null && (mc.player.tickCount % 2 == 0)) {
			float yawRad = mc.player.yBodyRot * Mth.DEG_TO_RAD;
			Vec3 forward = new Vec3(-Mth.sin(yawRad), 0.0, Mth.cos(yawRad));
			Vec3 right = new Vec3(-Mth.cos(yawRad), 0.0, -Mth.sin(yawRad));
			Vec3 tip = mc.player.position()
					.add(0.0, 1.88, 0.0)
					.add(forward.scale(1.58))
					.add(right.scale(0.32));
			mc.level.addParticle(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 0.0, 0.025, 0.0);
			if (mc.player.tickCount % 4 == 0) {
				mc.level.addParticle(ParticleTypes.SMOKE, tip.x, tip.y + 0.12, tip.z, 0.0, 0.03, 0.0);
			}
		}
	}

	public static void onServerEffect(QuickItemEffectPayload payload) {
		switch (payload.effectType()) {
			case QuickItemEffectPayload.EFFECT_STICK_IGNITED -> {
				stickLit = true;
				stickLitTicks = payload.remainingTicks();
			}
			case QuickItemEffectPayload.EFFECT_STICK_EXTINGUISHED -> {
				stickLit = false;
				stickLitTicks = 0;
			}
			case QuickItemEffectPayload.EFFECT_STICK_BURNED_OUT -> {
				activeHeldItem = null;
				stickLit = false;
				stickLitTicks = 0;
				waveStartNanos = 0L;
			}
			case QuickItemEffectPayload.EFFECT_DEKU_NUT_FLASH -> {
				nutFlashAlpha = 0.85f;
			}
			default -> {}
		}
	}

	public static void clear() {
		ITEMS.clear();
		selected = 0;
		activeHeldItem = null;
		stickLit = false;
		stickLitTicks = 0;
		waveStartNanos = 0L;
		nutThrowStartNanos = 0L;
		bombLiftStartNanos = 0L;
		bombThrowStartNanos = 0L;
		slingshotAiming = false;
		slingshotAimStartNanos = 0L;
		slingshotRecoilStartNanos = 0L;
		aimHoveringEntity = false;
		ocarinaStartNanos = 0L;
		holdPoseBlend = 0.0f;
		wholeOffsetYBlocks = 0.0f;
		wholeLeanForwardRad = 0.0f;
		nutFlashAlpha = 0.0f;
	}

	public static void applySnapshot(int savedSelected, List<ZeldaInventorySnapshot.QuickItemData> entries) {
		String activeId = activeHeldItem != null ? activeHeldItem.id() : null;
		ITEMS.clear();
		if (entries != null) {
			for (ZeldaInventorySnapshot.QuickItemData entry : entries) {
				if (entry.id().isEmpty() || entry.name().isEmpty()) {
					continue;
				}
				Item icon = Items.STICK;
				ResourceLocation parsed = ResourceLocation.tryParse(entry.iconId());
				if (parsed != null) {
					Item resolved = BuiltInRegistries.ITEM.get(parsed);
					if (resolved != Items.AIR) {
						icon = resolved;
					}
				}
				int slot = Mth.clamp(entry.slot(), 0, 21);
				int count = Math.max(1, entry.count());
				int maxCount = Math.max(count, entry.maxCount());
				String desc = entry.description().isEmpty()
						? "An item discovered inside a treasure chest." : entry.description();
				ITEMS.add(new QuickItem(slot, entry.id(), entry.name(), desc, icon, count, maxCount));
			}
		}
		selected = ITEMS.isEmpty() ? 0 : Mth.clamp(savedSelected, 0, ITEMS.size() - 1);
		if (activeId != null) {
			activeHeldItem = null;
			for (QuickItem item : ITEMS) {
				if (activeId.equals(item.id()) && item.count() > 0) {
					activeHeldItem = item;
					break;
				}
			}
		}
	}

	public static List<ZeldaInventorySnapshot.QuickItemData> toSnapshotItems() {
		List<ZeldaInventorySnapshot.QuickItemData> list = new ArrayList<>(ITEMS.size());
		for (QuickItem item : ITEMS) {
			list.add(new ZeldaInventorySnapshot.QuickItemData(
					item.slot(), item.id(), item.name(), item.description(),
					BuiltInRegistries.ITEM.getKey(item.icon()).toString(), item.count(), item.maxCount()));
		}
		return list;
	}

	public static void load() { clear(); }
	public static void save() { ZeldaInventoryClient.sendSnapshot(); }
}