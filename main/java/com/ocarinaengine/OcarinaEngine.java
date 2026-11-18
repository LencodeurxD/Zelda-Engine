package com.ocarinaengine;

import com.ocarinaengine.chest.ZeldaChestServer;
import com.ocarinaengine.combat.ZeldaCombatServer;
import com.ocarinaengine.combat.ZeldaFallServer;
import com.ocarinaengine.combat.ZeldaShieldServer;
import com.ocarinaengine.command.GametypeCommand;
import com.ocarinaengine.command.ZeldaChestCommands;
import com.ocarinaengine.entity.DekuNutEntity;
import com.ocarinaengine.entity.DekuSeedEntity;
import com.ocarinaengine.entity.ZeldaBombEntity;
import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import com.ocarinaengine.inventory.OcarinaInventoryServer;
import com.ocarinaengine.items.ZeldaQuickItemsServer;
import com.ocarinaengine.network.ChestCutscenePayload;
import com.ocarinaengine.network.FinishZeldaChestPayload;
import com.ocarinaengine.network.InitWorldGameTypePayload;
import com.ocarinaengine.network.OpenZeldaChestPayload;
import com.ocarinaengine.network.QuickItemEffectPayload;
import com.ocarinaengine.network.RequestZeldaInventoryPayload;
import com.ocarinaengine.network.SaveZeldaInventoryPayload;
import com.ocarinaengine.network.SyncGameTypePayload;
import com.ocarinaengine.network.SyncZeldaInventoryPayload;
import com.ocarinaengine.network.UseQuickItemPayload;
import com.ocarinaengine.network.ZeldaLandingRollPayload;
import com.ocarinaengine.network.ZeldaShieldBlockedPayload;
import com.ocarinaengine.network.ZeldaShieldStatePayload;
import com.ocarinaengine.network.ZeldaSwordAttackPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OcarinaEngine implements ModInitializer {
	public static final String MOD_ID = "ocarina_engine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(OpenZeldaChestPayload.TYPE, OpenZeldaChestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(FinishZeldaChestPayload.TYPE, FinishZeldaChestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ZeldaSwordAttackPayload.TYPE, ZeldaSwordAttackPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(InitWorldGameTypePayload.TYPE, InitWorldGameTypePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestZeldaInventoryPayload.TYPE, RequestZeldaInventoryPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SaveZeldaInventoryPayload.TYPE, SaveZeldaInventoryPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ZeldaShieldStatePayload.TYPE, ZeldaShieldStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ZeldaLandingRollPayload.TYPE, ZeldaLandingRollPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UseQuickItemPayload.TYPE, UseQuickItemPayload.CODEC);

		PayloadTypeRegistry.playS2C().register(ChestCutscenePayload.TYPE, ChestCutscenePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ZeldaShieldBlockedPayload.TYPE, ZeldaShieldBlockedPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SyncGameTypePayload.TYPE, SyncGameTypePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SyncZeldaInventoryPayload.TYPE, SyncZeldaInventoryPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(QuickItemEffectPayload.TYPE, QuickItemEffectPayload.CODEC);

		DekuNutEntity.register();
		DekuSeedEntity.register();
		ZeldaBombEntity.register();
		ZeldaChestServer.register();
		ZeldaCombatServer.register();
		ZeldaFallServer.register();
		ZeldaShieldServer.register();
		ZeldaQuickItemsServer.register();
		OcarinaGameTypeServer.register();
		OcarinaInventoryServer.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			ZeldaChestCommands.register(dispatcher);
			GametypeCommand.register(dispatcher);
		});
		LOGGER.info("[Ocarina Engine] Core loaded (common side + Step 1 quick items).");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}