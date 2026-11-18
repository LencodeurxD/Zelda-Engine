package com.ocarinaengine.client;

import com.ocarinaengine.OcarinaEngine;
import com.ocarinaengine.client.animation.ZeldaAnimationEngine;
import com.ocarinaengine.client.chest.ZeldaChestCutscene;
import com.ocarinaengine.client.combat.ZeldaCombatSystem;
import com.ocarinaengine.client.combat.ZeldaTargetingSystem;
import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.hud.QuickItemHud;
import com.ocarinaengine.client.hud.ZeldaHud;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import com.ocarinaengine.client.items.QuickItemController;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.movement.ZeldaLedgeSystem;
import com.ocarinaengine.client.render.DekuNutEntityRenderer;
import com.ocarinaengine.client.render.DekuSeedEntityRenderer;
import com.ocarinaengine.client.render.ZeldaBombEntityRenderer;
import com.ocarinaengine.client.render.ZeldaLightEffects;
import com.ocarinaengine.client.render.ZeldaTargetRenderer;
import com.ocarinaengine.client.rules.ZeldaLockdown;
import com.ocarinaengine.entity.DekuNutEntity;
import com.ocarinaengine.entity.DekuSeedEntity;
import com.ocarinaengine.entity.ZeldaBombEntity;
import com.ocarinaengine.gametype.OcarinaGameType;
import com.ocarinaengine.network.ChestCutscenePayload;
import com.ocarinaengine.network.QuickItemEffectPayload;
import com.ocarinaengine.network.SyncGameTypePayload;
import com.ocarinaengine.network.SyncZeldaInventoryPayload;
import com.ocarinaengine.network.ZeldaShieldBlockedPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;

public class OcarinaEngineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ZeldaInventoryClient.tickEnsureSynced(client);
			ZeldaQuickItems.tickClient(client);
			if (OcarinaGameTypeClient.isZelda()
					&& client.player != null && client.level != null
					&& client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
				client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
		});

		ZeldaInventoryClient.reset();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			OcarinaGameTypeClient.onClientJoin(client);
			ZeldaInventoryClient.onClientJoin(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ZeldaChestCutscene.flushPendingReward();
			ZeldaInventoryClient.flushOnDisconnect(client);
			OcarinaGameTypeClient.onDisconnect();
			ZeldaInventoryClient.reset();
			ZeldaChestCutscene.reset();
			ZeldaTargetingSystem.reset();
			ZeldaCombatSystem.reset();
			ZeldaLedgeSystem.reset();
			ZeldaAnimationEngine.resetWorld();
		});

		ZeldaLockdown.register();
		ZeldaHud.register();
		ZeldaKeys.register();
		QuickItemHud.register();
		QuickItemController.register();
		ZeldaChestCutscene.register();
		ZeldaTargetingSystem.register();
		ZeldaCombatSystem.register();
		ZeldaLightEffects.register();
		ZeldaTargetRenderer.register();

		EntityRendererRegistry.register(DekuNutEntity.TYPE, DekuNutEntityRenderer::new);
		EntityRendererRegistry.register(DekuSeedEntity.TYPE, DekuSeedEntityRenderer::new);
		EntityRendererRegistry.register(ZeldaBombEntity.TYPE, ZeldaBombEntityRenderer::new);

		ClientPlayNetworking.registerGlobalReceiver(SyncGameTypePayload.TYPE, (payload, context) -> {
			OcarinaGameType type = OcarinaGameType.byOrdinal(payload.gameTypeOrdinal());
			context.client().execute(() -> OcarinaGameTypeClient.applyServerGameType(type));
		});

		ClientPlayNetworking.registerGlobalReceiver(SyncZeldaInventoryPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ZeldaInventoryClient.applyServerSnapshot(payload.snapshot())));

		ClientPlayNetworking.registerGlobalReceiver(ChestCutscenePayload.TYPE, (payload, context) -> {
			if (OcarinaGameTypeClient.isZelda()) {
				ZeldaChestCutscene.start(payload);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(ZeldaShieldBlockedPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ZeldaCombatSystem.onShieldBlockedClient(
						new Vec3(payload.x(), payload.y(), payload.z()),
						payload.burnedDeku(),
						payload.reflectedMirror())));

		ClientPlayNetworking.registerGlobalReceiver(QuickItemEffectPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ZeldaQuickItems.onServerEffect(payload)));

		OcarinaEngine.LOGGER.info("[Ocarina Engine] Client initialized (per-world inventory & Step 1 quick items ready).");
	}
}