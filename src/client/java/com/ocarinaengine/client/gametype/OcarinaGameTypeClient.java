package com.ocarinaengine.client.gametype;

import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.camera.EngineState;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaBladeTracker;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import com.ocarinaengine.client.screen.ZeldaEquipmentScreen;
import com.ocarinaengine.client.screen.ZeldaQuickMenuScreen;
import com.ocarinaengine.gametype.OcarinaGameType;
import com.ocarinaengine.network.InitWorldGameTypePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Client-side controller for the active {@link OcarinaGameType} (Zelda vs. Minecraft)
 * and the Create World screen selector state.
 */
public final class OcarinaGameTypeClient {
	private static OcarinaGameType activeGameType = OcarinaGameType.ZELDA;
	private static OcarinaGameType createScreenSelection = OcarinaGameType.ZELDA;
	private static OcarinaGameType armedNewWorldType = null;

	private OcarinaGameTypeClient() {}

	public static OcarinaGameType getActiveGameType() {
		return activeGameType;
	}

	public static boolean isZelda() {
		return activeGameType == OcarinaGameType.ZELDA;
	}

	public static boolean isMinecraft() {
		return activeGameType == OcarinaGameType.MINECRAFT;
	}

	public static OcarinaGameType getCreateScreenSelection() {
		return createScreenSelection;
	}

	public static void setCreateScreenSelection(OcarinaGameType type) {
		createScreenSelection = (type != null) ? type : OcarinaGameType.ZELDA;
	}

	public static void resetCreateScreenSelection() {
		createScreenSelection = OcarinaGameType.ZELDA;
		armedNewWorldType = null;
	}

	/** Called when the player confirms world creation in CreateWorldScreen. */
	public static void armNewWorldCreation() {
		armedNewWorldType = createScreenSelection;
	}

	/** Called if the user cancels out of CreateWorldScreen before creating the world. */
	public static void clearPendingNewWorld() {
		armedNewWorldType = null;
	}

	/** Called during ClientPlayConnectionEvents.JOIN to apply & transmit newly created world type. */
	public static void onClientJoin(Minecraft client) {
		if (armedNewWorldType != null) {
			OcarinaGameType chosen = armedNewWorldType;
			armedNewWorldType = null;
			applyGameType(client, chosen, true);
			ClientPlayNetworking.send(new InitWorldGameTypePayload(chosen.ordinal()));
		}
	}

	/** Called during ClientPlayConnectionEvents.DISCONNECT to leave no stale state across worlds. */
	public static void onDisconnect() {
		armedNewWorldType = null;
		activeGameType = OcarinaGameType.ZELDA;
		resetZeldaSubsystems();
	}

	/** Called when receiving SyncGameTypePayload from the server (/Gametype or world join). */
	public static void applyServerGameType(OcarinaGameType type) {
		if (type == null) {
			return;
		}
		applyGameType(Minecraft.getInstance(), type, false);
	}

	private static void applyGameType(Minecraft client, OcarinaGameType newType, boolean initialJoin) {
		OcarinaGameType previous = activeGameType;
		activeGameType = newType;

		if (newType.isMinecraft()) {
			resetZeldaSubsystems();
			if (client.screen instanceof ZeldaEquipmentScreen || client.screen instanceof ZeldaQuickMenuScreen) {
				client.setScreen(null);
			}
			if ((previous.isZelda() || initialJoin) && client.options.getCameraType() != CameraType.FIRST_PERSON) {
				client.options.setCameraType(CameraType.FIRST_PERSON);
			}
		} else {
			if (previous.isMinecraft() || initialJoin) {
				resetZeldaSubsystems();
				if (client.player != null) {
					EngineState.camYaw = client.player.getYRot();
					EngineState.camPitch = EngineState.DEFAULT_PITCH;
					EngineState.followStrength = 0.0f;
				}
				if (client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
					client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				}
			}
		}
	}

	private static void resetZeldaSubsystems() {
		ZeldaChestCutscene.reset();
		ZeldaTargetingSystem.reset();
		ZeldaCombatSystem.reset();
		ZeldaLedgeSystem.reset();
		ZeldaAnimationEngine.resetWorld();
		ZeldaBladeTracker.reset();
	}
}
