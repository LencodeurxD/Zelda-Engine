package com.ocarinaengine.client.chest;

import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.equipment.ZeldaItem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.screen.UiShapes;
import com.ocarinaengine.network.ChestCutscenePayload;
import com.ocarinaengine.network.FinishZeldaChestPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side Zelda chest opening cutscene controller:
 * - Single chest: quick lid open, pivot to face camera, and present the item spinning overhead.
 * - Double chest: 3-shot Ocarina of Time sequence (side profile heavy lift, low over-chest
 *   golden ray cut, and front-elevated presenting shot with open chest behind the spinning item).
 * - Holds the final reveal scene until the player confirms with gamepad A (or Enter/Space).
 */
public final class ZeldaChestCutscene {
	@FunctionalInterface
	public interface CameraApplier {
		void apply(Vec3 position, float yaw, float pitch);
	}

	private static boolean active = false;
	private static boolean bigChest = false;
	private static BlockPos primaryPos = BlockPos.ZERO;
	private static Vec3 chestCenter = Vec3.ZERO;
	private static Vec3 standPos = Vec3.ZERO;
	private static Direction chestFacing = Direction.SOUTH;
	private static ItemStack rewardStack = ItemStack.EMPTY;
	private static QuickItem rewardInfo = null;
	private static ZeldaItem rewardEquip = null;

	private static long startNanos = 0L;
	private static float elapsed = 0.0f;
	private static boolean soundOpened = false;
	private static boolean soundRevealed = false;
	private static int fanfareStage = 0;
	private static boolean itemGranted = false;

	private static boolean prevGamepadInteract = false;
	private static boolean suppressJumpUntilRelease = false;
	private static boolean suppressInteractUntilRelease = false;

	private ZeldaChestCutscene() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ZeldaChestCutscene::tick);
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderHud(graphics));
	}

	public static void start(ChestCutscenePayload payload) {
		if (!OcarinaGameTypeClient.isZelda()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}

		primaryPos = payload.pos();
		Vec3 p1 = Vec3.atCenterOf(payload.pos());
		Vec3 p2 = Vec3.atCenterOf(payload.otherPos());
		chestCenter = payload.doubleChest() ? p1.add(p2).scale(0.5) : p1;
		chestFacing = payload.facing();
		rewardStack = payload.reward();
		if (rewardStack.isEmpty()) {
			rewardEquip = null;
			rewardInfo = null;
		} else {
			rewardEquip = ZeldaEquipment.previewEquipment(rewardStack);
			rewardInfo = (rewardEquip == null) ? ZeldaQuickItems.mapStack(rewardStack) : null;
		}
		bigChest = payload.doubleChest() && !rewardStack.isEmpty();

		double dist = bigChest ? 1.05 : 0.98;
		standPos = new Vec3(
				chestCenter.x + chestFacing.getStepX() * dist,
				player.getY(),
				chestCenter.z + chestFacing.getStepZ() * dist
		);

		Gamepad.poll();
		prevGamepadInteract = Gamepad.actionInteract;

		suppressJumpUntilRelease = false;
		suppressInteractUntilRelease = false;
		active = true;
		startNanos = System.nanoTime();
		elapsed = 0.0f;
		soundOpened = false;
		soundRevealed = false;
		fanfareStage = 0;
		itemGranted = false;
	}

	public static boolean isActive() {
		return OcarinaGameTypeClient.isZelda() && active;
	}

	/** Grants an uncollected chest reward before disconnect so quitting mid-cutscene never loses loot. */
	public static void flushPendingReward() {
		if (active && !itemGranted && !rewardStack.isEmpty()) {
			itemGranted = true;
			grantReward();
		}
	}

	public static void reset() {
		active = false;
		suppressJumpUntilRelease = false;
		suppressInteractUntilRelease = false;
		rewardStack = ItemStack.EMPTY;
		rewardInfo = null;
		rewardEquip = null;
	}

	public static boolean isBigChest() {
		return bigChest;
	}

	public static float elapsedSeconds() {
		return elapsed;
	}

	public static Vec3 chestCenter() {
		return chestCenter;
	}

	public static ItemStack rewardStack() {
		return rewardStack;
	}

	public static float revealStartTime() {
		return bigChest ? 2.20f : 0.44f;
	}

	public static boolean canConfirm() {
		return isActive() && elapsed >= revealStartTime() + 0.25f;
	}

	public static boolean isJumpSuppressed(boolean jumpInputHeld) {
		if (!suppressJumpUntilRelease) {
			return false;
		}
		if (!jumpInputHeld) {
			suppressJumpUntilRelease = false;
			return false;
		}
		return true;
	}

	public static boolean isInteractSuppressed(boolean interactHeld) {
		if (!suppressInteractUntilRelease) {
			return false;
		}
		if (!interactHeld) {
			suppressInteractUntilRelease = false;
			return false;
		}
		return true;
	}

	public static float chestBeamIntensity() {
		if (!isActive() || !bigChest) {
			return 0.0f;
		}
		if (elapsed < 0.70f) {
			return 0.0f;
		}
		if (elapsed < 1.25f) {
			return (elapsed - 0.70f) / 0.55f;
		}
		if (elapsed < 2.25f) {
			return 1.0f;
		}
		return Mth.clamp(1.0f - (elapsed - 2.25f) / 0.8f, 0.35f, 1.0f);
	}

	public static float itemRevealAlpha() {
		if (!isActive() || rewardStack.isEmpty()) {
			return 0.0f;
		}
		float start = revealStartTime();
		if (elapsed < start) {
			return 0.0f;
		}
		return Mth.clamp((elapsed - start) / 0.22f, 0.0f, 1.0f);
	}

	public static Vec3 itemWorldPosition() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return chestCenter;
		}
		float bob = Mth.sin(elapsed * 3.5f) * 0.04f;
		return new Vec3(player.getX(), player.getY() + 2.26 + bob, player.getZ());
	}

	public static void updateCamera(float dt) {
		if (!isActive()) {
			return;
		}
		elapsed = (System.nanoTime() - startNanos) / 1.0e9f;
	}

	public static void applyCutsceneCamera(BlockGetter level, Entity entity, CameraApplier applier) {
		if (!isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}

		Vec3 out = new Vec3(chestFacing.getStepX(), 0.0, chestFacing.getStepZ());
		Vec3 right = new Vec3(-out.z, 0.0, out.x);
		Vec3 playerPos = player.position();

		Vec3 focus;
		Vec3 desiredCam;

		if (bigChest && elapsed < 1.25f) {
			float p = Mth.clamp(elapsed / 1.25f, 0.0f, 1.0f);
			Vec3 mid = chestCenter.lerp(playerPos.add(0.0, 1.05, 0.0), 0.45);
			focus = new Vec3(mid.x, playerPos.y + 1.05, mid.z);
			double sideDist = Mth.lerp(p, 2.55, 2.25);
			desiredCam = focus
					.add(right.scale(sideDist))
					.add(out.scale(0.85))
					.add(0.0, 0.25, 0.0);
		} else if (bigChest && elapsed < 2.20f) {
			float p = Mth.clamp((elapsed - 1.25f) / 0.95f, 0.0f, 1.0f);
			focus = new Vec3(playerPos.x, playerPos.y + 1.32, playerPos.z);
			desiredCam = chestCenter
					.add(out.scale(-1.25))
					.add(right.scale(Mth.lerp(p, -1.65, -1.35)))
					.add(0.0, 0.12, 0.0);
		} else if (!bigChest && elapsed < 0.44f) {
			focus = chestCenter.lerp(playerPos.add(0.0, 1.05, 0.0), 0.50);
			desiredCam = focus
					.add(right.scale(1.90))
					.add(out.scale(1.40))
					.add(0.0, 0.45, 0.0);
		} else {
			float sinceReveal = Math.max(0.0f, elapsed - revealStartTime());
			float settle = 1.0f - (float) Math.pow(1.0f - Mth.clamp(sinceReveal / 0.40f, 0.0f, 1.0f), 2.0);
			double dist = Mth.lerp(settle, 2.70, 2.45);
			focus = new Vec3(playerPos.x, playerPos.y + 1.62, playerPos.z);
			desiredCam = playerPos
					.add(out.scale(dist))
					.add(0.0, 2.08, 0.0);
		}

		Vec3 safeCam = desiredCam;
		BlockHitResult hit = level.clip(new ClipContext(
				focus,
				desiredCam,
				ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE,
				entity
		));
		if (hit.getType() != HitResult.Type.MISS) {
			Vec3 hitLoc = hit.getLocation();
			Vec3 back = focus.subtract(hitLoc).normalize().scale(0.28);
			safeCam = hitLoc.add(back);
		}

		Vec3 look = focus.subtract(safeCam);
		double horiz = Math.sqrt(look.x * look.x + look.z * look.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-look.y, Math.max(1.0e-4, horiz)));

		applier.apply(safeCam, yaw, pitch);
	}

	public static void applyPlayerPose(LocalPlayer player) {
		if (!isActive()) {
			return;
		}
		double nx = Mth.lerp(0.28, player.getX(), standPos.x);
		double nz = Mth.lerp(0.28, player.getZ(), standPos.z);
		player.setPos(nx, player.getY(), nz);
		player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);

		float faceChestYaw = chestFacing.getOpposite().toYRot();
		float faceCameraYaw = chestFacing.toYRot();
		float desiredYaw = faceChestYaw;
		if (bigChest && elapsed >= 2.05f) {
			float turnT = Mth.clamp((elapsed - 2.05f) / 0.28f, 0.0f, 1.0f);
			float d = Mth.wrapDegrees(faceCameraYaw - faceChestYaw);
			desiredYaw = faceChestYaw + d * turnT;
		} else if (!bigChest && elapsed >= 0.42f) {
			float turnT = Mth.clamp((elapsed - 0.42f) / 0.24f, 0.0f, 1.0f);
			float d = Mth.wrapDegrees(faceCameraYaw - faceChestYaw);
			desiredYaw = faceChestYaw + d * turnT;
		}

		player.setYRot(desiredYaw);
		player.setYBodyRot(desiredYaw);
		player.setYHeadRot(desiredYaw);
	}

	public static void confirm() {
		if (!isActive() || !canConfirm()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15f));
		finish();
	}

	private static void tick(Minecraft mc) {
		if (!OcarinaGameTypeClient.isZelda()) {
			if (active) {
				reset();
			}
			return;
		}
		if (!active) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			active = false;
			return;
		}
		elapsed = (System.nanoTime() - startNanos) / 1.0e9f;

		if (bigChest) {
			if (!soundOpened && elapsed >= 0.65f) {
				soundOpened = true;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.CHEST_OPEN, 0.72f));
			}
			if (fanfareStage == 0 && elapsed >= 1.15f) {
				fanfareStage = 1;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), 0.90f));
			} else if (fanfareStage == 1 && elapsed >= 1.45f) {
				fanfareStage = 2;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), 1.05f));
			} else if (fanfareStage == 2 && elapsed >= 1.75f) {
				fanfareStage = 3;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HARP.value(), 1.25f));
			} else if (fanfareStage == 3 && elapsed >= 2.22f) {
				fanfareStage = 4;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.35f));
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.50f));
			}
		} else {
			if (!soundOpened && elapsed >= 0.12f) {
				soundOpened = true;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.CHEST_OPEN, 1.15f));
			}
			if (!soundRevealed && elapsed >= 0.44f && !rewardStack.isEmpty()) {
				soundRevealed = true;
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.25f));
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.40f));
			}
		}

		float grantAt = revealStartTime() + 0.05f;
		if (!itemGranted && !rewardStack.isEmpty() && elapsed >= grantAt) {
			itemGranted = true;
			grantReward();
		}

		Gamepad.poll();
		boolean interactPressed = Gamepad.actionInteract && !prevGamepadInteract;
		prevGamepadInteract = Gamepad.actionInteract;
		if (interactPressed && canConfirm()) {
			confirm();
		}
	}

	private static void grantReward() {
		if (ZeldaEquipment.isEquipmentStack(rewardStack)) {
			rewardEquip = ZeldaEquipment.grantFromChest(rewardStack);
			rewardInfo = null;
		} else {
			rewardInfo = ZeldaQuickItems.grantFromChest(rewardStack);
			rewardEquip = null;
		}
	}

	private static void finish() {
		if (!itemGranted && !rewardStack.isEmpty()) {
			itemGranted = true;
			grantReward();
		}
		Minecraft mc = Minecraft.getInstance();
		Gamepad.poll();
		suppressJumpUntilRelease = Gamepad.actionJump || mc.options.keyJump.isDown();
		suppressInteractUntilRelease = Gamepad.actionInteract || mc.options.keyUse.isDown();
		ClientPlayNetworking.send(new FinishZeldaChestPayload(primaryPos));
		EngineState.camYaw = chestFacing.toYRot();
		EngineState.camPitch = EngineState.DEFAULT_PITCH;
		active = false;
	}

	private static void renderHud(GuiGraphics g) {
		if (!isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui) {
			return;
		}

		float bannerAlpha = itemRevealAlpha();
		String rewardName = rewardEquip != null ? rewardEquip.name() : (rewardInfo != null ? rewardInfo.name() : null);
		String rewardDesc = rewardEquip != null ? rewardEquip.description() : (rewardInfo != null ? rewardInfo.description() : null);
		if (rewardName != null && bannerAlpha > 0.04f) {
			int w = Math.min(310, g.guiWidth() - 40);
			int h = bigChest ? 72 : 44;
			int x = (g.guiWidth() - w) / 2;
			int y = g.guiHeight() - (bigChest ? 98 : 66);

			UiShapes.bevel(g, x, y, w, h, 4, UiShapes.withAlpha(0xFF6E5836, bannerAlpha));
			UiShapes.bevel(g, x + 2, y + 2, w - 4, h - 4, 3, UiShapes.withAlpha(0xE8120C08, bannerAlpha));

			String headline = "You got a " + rewardName + "!";
			g.drawCenteredString(mc.font, headline, g.guiWidth() / 2, y + 8, UiShapes.withAlpha(0xFFF2E8D0, bannerAlpha));
			if (bigChest && rewardDesc != null) {
				var lines = mc.font.split(Component.literal(rewardDesc), w - 24);
				int ly = y + 22;
				for (int i = 0; i < Math.min(3, lines.size()); i++) {
					var line = lines.get(i);
					g.drawString(mc.font, line, g.guiWidth() / 2 - mc.font.width(line) / 2, ly,
							UiShapes.withAlpha(0xFFB8A888, bannerAlpha), false);
					ly += 10;
				}
			}

			if (canConfirm()) {
				String keyLabel = Gamepad.connected ? Gamepad.labelInteract() : "Use";
				String prompt = "Continue";
				int kw = UiShapes.keyCapWidth(mc.font, keyLabel);
				int totalW = kw + 5 + mc.font.width(prompt);
				int px = x + w - totalW - 10;
				int py = y + h - 18;
				float pulse = 0.72f + 0.28f * Mth.sin(elapsed * 5.0f);
				UiShapes.keyCap(g, mc.font, keyLabel, px, py, bannerAlpha * pulse);
				g.drawString(mc.font, prompt, px + kw + 5, py + 2,
						UiShapes.withAlpha(0xFFF2E8D0, bannerAlpha * pulse), false);
			}
		} else if (rewardStack.isEmpty() && elapsed > 0.25f) {
			int w = 220;
			int h = 42;
			int x = (g.guiWidth() - w) / 2;
			int y = g.guiHeight() - 66;
			UiShapes.bevel(g, x, y, w, h, 4, 0xFF6E5836);
			UiShapes.bevel(g, x + 2, y + 2, w - 4, h - 4, 3, 0xE8120C08);
			g.drawCenteredString(mc.font, "The chest is empty.", g.guiWidth() / 2, y + 9, 0xFFB8A888);
			if (canConfirm()) {
				String keyLabel = Gamepad.connected ? Gamepad.labelInteract() : "Use";
				int kw = UiShapes.keyCapWidth(mc.font, keyLabel);
				UiShapes.keyCap(g, mc.font, keyLabel, x + w - kw - 10, y + h - 17, 1.0f);
			}
		}
	}
}
