package com.ocarinaengine.client.rules;

import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import com.ocarinaengine.client.screen.ZeldaEquipmentScreen;
import com.ocarinaengine.network.OpenZeldaChestPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Disables the vanilla Minecraft mechanics listed in {@link ZeldaRules}.
 * Handled client-side: if the player tries them, nothing is sent to the game,
 * except chest interactions which trigger the Zelda chest opening cutscene.
 */
public final class ZeldaLockdown {
	private ZeldaLockdown() {}

	public static void register() {
		// --- World actions (mining, placing, using, attacking) ---
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			level.isClientSide() && OcarinaGameTypeClient.isZelda() && ZeldaRules.disableMining
				? InteractionResult.FAIL : InteractionResult.PASS);

		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
			level.isClientSide() && OcarinaGameTypeClient.isZelda() && ZeldaRules.disablePlacingAndUsingBlocks
				? InteractionResult.FAIL : InteractionResult.PASS);

		UseItemCallback.EVENT.register((player, level, hand) ->
			level.isClientSide() && OcarinaGameTypeClient.isZelda() && ZeldaRules.disableUsingItems
				? InteractionResultHolder.fail(player.getItemInHand(hand))
				: InteractionResultHolder.pass(player.getItemInHand(hand)));

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
			level.isClientSide() && OcarinaGameTypeClient.isZelda() && ZeldaRules.disableAttackAndEntityInteraction
				? InteractionResult.FAIL : InteractionResult.PASS);

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
			level.isClientSide() && OcarinaGameTypeClient.isZelda() && ZeldaRules.disableAttackAndEntityInteraction
				? InteractionResult.FAIL : InteractionResult.PASS);

		// --- Keybindings: swallow presses before Minecraft processes them ---
		ClientTickEvents.START_CLIENT_TICK.register(ZeldaLockdown::swallowKeys);

		// --- Player body: hunger locked, max health capped (singleplayer) ---
		ClientTickEvents.END_CLIENT_TICK.register(ZeldaLockdown::enforceBody);
	}

	/**
	 * In singleplayer, directly configures the server player:
	 * - hunger locked at 17: no natural regeneration (requires 18) and never starves;
	 * - max health capped to the target heart count (1 heart = ZeldaRules.HP_PER_HEART health points).
	 */
	private static void enforceBody(Minecraft mc) {
		if (!OcarinaGameTypeClient.isZelda() || mc.player == null) {
			return;
		}
		var server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		var id = mc.player.getUUID();
		server.execute(() -> {
			var serverPlayer = server.getPlayerList().getPlayer(id);
			if (serverPlayer == null) {
				return;
			}
			if (ZeldaRules.disableHunger) {
				int level = ZeldaRules.disableNaturalRegeneration ? 17 : 20;
				var food = serverPlayer.getFoodData();
				if (food.getFoodLevel() != level) {
					food.setFoodLevel(level);
				}
			}
			if (ZeldaRules.limitMaxHearts) {
				double wanted = ZeldaRules.maxHearts * (double) ZeldaRules.HP_PER_HEART;
				var maxHealth = serverPlayer.getAttribute(Attributes.MAX_HEALTH);
				if (maxHealth != null && maxHealth.getBaseValue() != wanted) {
					maxHealth.setBaseValue(wanted);
				}
				if (serverPlayer.getHealth() > wanted) {
					serverPlayer.setHealth((float) wanted);
				}
			}
		});
	}

	/** Whether gamepad buttons were already held on the previous tick (trigger only on press). */
	private static boolean startWasDown = false;
	private static boolean attackWasDown = false;
	private static boolean cancelWasDown = false;
	private static boolean interactWasDown = false;

	private static void swallowKeys(Minecraft mc) {
		if (!OcarinaGameTypeClient.isZelda() || mc.player == null) {
			startWasDown = false;
			attackWasDown = false;
			cancelWasDown = false;
			interactWasDown = false;
			ZeldaCombatSystem.updateAttackInput(mc, false, false);
			return;
		}
		Gamepad.poll();
		boolean activeWin = mc.isWindowActive();
		boolean startPressed = Gamepad.buttonStart && !startWasDown;
		startWasDown = Gamepad.buttonStart;
		boolean padAttackPressed = Gamepad.actionAttack && !attackWasDown && activeWin;
		attackWasDown = Gamepad.actionAttack;
		boolean padCancelPressed = Gamepad.actionCancel && !cancelWasDown && activeWin;
		cancelWasDown = Gamepad.actionCancel;
		boolean padInteractPressed = Gamepad.actionInteract && !interactWasDown && activeWin;
		interactWasDown = Gamepad.actionInteract;

		if (mc.screen != null) {
			ZeldaCombatSystem.updateAttackInput(mc, false, false);
			return;
		}
		Options o = mc.options;

		if (ZeldaChestCutscene.isActive()) {
			drain(o.keyInventory);
			drain(o.keyDrop);
			drain(o.keySwapOffhand);
			drain(o.keyPickItem);
			drain(o.keyAdvancements);
			for (KeyMapping slot : o.keyHotbarSlots) {
				drain(slot);
			}
			drain(o.keyAttack);
			boolean confirmKey = false;
			while (o.keyUse.consumeClick()) {
				confirmKey = true;
			}
			while (o.keyJump.consumeClick()) {
				confirmKey = true;
			}
			if (confirmKey && ZeldaChestCutscene.canConfirm()) {
				ZeldaChestCutscene.confirm();
			}
			return;
		}

		if (ZeldaRules.disableInventoryAndHotbar) {
			// Minecraft's inventory is replaced by the Zelda equipment menu.
			boolean openByKey = false;
			boolean openByPad = false;
			if (ZeldaRules.zeldaMenu) {
				while (o.keyInventory.consumeClick()) {
					openByKey = true;
				}
				if (startPressed && mc.isWindowActive()) {
					openByPad = true;
				}
			} else {
				drain(o.keyInventory);
			}
			if (openByKey || openByPad) {
				mc.setScreen(new ZeldaEquipmentScreen(openByPad && !openByKey));
			}
			drain(o.keyDrop);
			drain(o.keySwapOffhand);
			drain(o.keyPickItem);
			drain(o.keyAdvancements);
			for (KeyMapping slot : o.keyHotbarSlots) {
				drain(slot);
			}
		}
		if (padCancelPressed) {
			if (ZeldaLedgeSystem.isHanging()) {
				ZeldaLedgeSystem.requestDrop(mc.player);
			} else {
				ZeldaQuickItems.cancelActiveItem();
				ZeldaCombatSystem.onCancelPressed(mc);
			}
		}
		if (ZeldaRules.disableMining || ZeldaRules.disableAttackAndEntityInteraction) {
			boolean attackPressed = padAttackPressed;
			while (o.keyAttack.consumeClick()) {
				attackPressed = true;
			}
			boolean attackHeld = o.keyAttack.isDown() || (Gamepad.actionAttack && activeWin);
			if (ZeldaEquipment.hasEquippedSword()) {
				ZeldaCombatSystem.updateAttackInput(mc, attackPressed, attackHeld);
			} else {
				ZeldaCombatSystem.updateAttackInput(mc, false, false);
			}
		}
		if (ZeldaRules.disablePlacingAndUsingBlocks || ZeldaRules.disableUsingItems) {
			boolean usePressed = padInteractPressed;
			while (o.keyUse.consumeClick()) {
				usePressed = true;
			}
			boolean interactHeldNow = o.keyUse.isDown() || (Gamepad.actionInteract && activeWin);
			if (ZeldaChestCutscene.isInteractSuppressed(interactHeldNow)) {
				usePressed = false;
			}
			if (usePressed && !ZeldaLedgeSystem.isActive()) {
				BlockPos chestPos = findTargetChest(mc);
				if (chestPos != null) {
					ClientPlayNetworking.send(new OpenZeldaChestPayload(chestPos));
				} else {
					ZeldaCombatSystem.tryStartRoll(mc);
				}
			}
		}
	}

	/** Finds a ChestBlock targeted by the crosshair or standing directly in front of the player. */
	private static BlockPos findTargetChest(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return null;
		}
		if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = bhr.getBlockPos();
			if (mc.level.getBlockState(pos).getBlock() instanceof ChestBlock
					&& mc.player.distanceToSqr(Vec3.atCenterOf(pos)) <= 20.0) {
				return pos;
			}
		}
		// Third-person Zelda fallback: probe directly ahead of the player's facing direction.
		double yawRad = Math.toRadians(mc.player.getYRot());
		double fx = -Math.sin(yawRad);
		double fz = Math.cos(yawRad);
		for (double d = 0.7; d <= 2.4; d += 0.45) {
			for (double dy : new double[] {0.4, 1.1}) {
				BlockPos probe = BlockPos.containing(
						mc.player.getX() + fx * d,
						mc.player.getY() + dy,
						mc.player.getZ() + fz * d
				);
				if (mc.level.getBlockState(probe).getBlock() instanceof ChestBlock) {
					return probe;
				}
			}
		}
		return null;
	}

	/** Drains pending key presses so they are completely ignored. */
	private static void drain(KeyMapping key) {
		while (key.consumeClick()) {
			// Do nothing: the key press is ignored.
		}
	}
}
