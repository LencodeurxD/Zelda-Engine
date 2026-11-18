package com.ocarinaengine.client.hud;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.rules.ZeldaRules;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

/**
 * Renders Zelda hearts in the top-left corner of the screen,
 * and the air gauge right below when underwater.
 * 1 heart = 4 health points, showing quarter-hearts filled piece by piece like Zelda.
 */
public final class ZeldaHud {
	/** Original Minecraft heart sprite size, in pixels. */
	private static final int SPRITE_SIZE = 9;
	/** Scale factor: +10%. */
	private static final float SCALE = 1.1f;
	/** Spacing between two hearts (9 x 1.1 = 9.9 px, rounded). */
	private static final int STEP = 10;
	private static final int MARGIN = 6;

	private static final ResourceLocation HEART_CONTAINER = vanilla("hud/heart/container");
	private static final ResourceLocation HEART_FULL = vanilla("hud/heart/full");
	private static final ResourceLocation AIR = vanilla("hud/air");
	private static final ResourceLocation AIR_BURSTING = vanilla("hud/air_bursting");

	/**
	 * The 4 quarters of the full heart: {u, v, width, height} in the 9x9 sprite.
	 * Fill order: top-left, bottom-left, bottom-right, top-right.
	 */
	private static final int[][] HEART_QUARTERS = {
			{0, 0, 5, 5}, // quarter 1: top left
			{0, 5, 5, 4}, // quarter 2: bottom left
			{5, 5, 4, 4}, // quarter 3: bottom right
			{5, 0, 4, 5}  // quarter 4: top right
	};

	private ZeldaHud() {}

	public static void register() {
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> render(graphics));
	}

	private static ResourceLocation vanilla(String path) {
		return ResourceLocation.fromNamespaceAndPath("minecraft", path);
	}

	private static void sprite(GuiGraphics g, ResourceLocation id, int x, int y) {
		g.pose().pushPose();
		g.pose().translate((float) x, (float) y, 0.0f);
		g.pose().scale(SCALE, SCALE, 1.0f);
		g.blitSprite(id, 0, 0, SPRITE_SIZE, SPRITE_SIZE);
		g.pose().popPose();
	}

	/** Draws the first "quarters" (1, 2, or 3) pieces of a full heart. */
	private static void partialHeart(GuiGraphics g, int x, int y, int quarters) {
		g.pose().pushPose();
		g.pose().translate((float) x, (float) y, 0.0f);
		g.pose().scale(SCALE, SCALE, 1.0f);
		for (int q = 0; q < quarters; q++) {
			int[] r = HEART_QUARTERS[q];
			g.blitSprite(HEART_FULL, SPRITE_SIZE, SPRITE_SIZE, r[0], r[1], r[0], r[1], r[2], r[3]);
		}
		g.pose().popPose();
	}

	/** Draws the heart row (including quarter-hearts) at the given position. */
	public static void drawHearts(GuiGraphics g, LocalPlayer player, int x0, int y0) {
		int hearts = ZeldaRules.maxHearts;
		int perHeart = ZeldaRules.HP_PER_HEART;
		int quarters = Mth.clamp((int) Math.ceil(player.getHealth()), 0, hearts * perHeart);
		for (int i = 0; i < hearts; i++) {
			int x = x0 + i * STEP;
			sprite(g, HEART_CONTAINER, x, y0);
			int remaining = quarters - i * perHeart;
			if (remaining >= perHeart) {
				sprite(g, HEART_FULL, x, y0);
			} else if (remaining > 0) {
				partialHeart(g, x, y0, remaining);
			}
		}
	}

	private static void render(GuiGraphics g) {
		if (!OcarinaGameTypeClient.isZelda() || !ZeldaRules.zeldaHearts) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.options.hideGui || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) {
			return;
		}

		int x0 = MARGIN;
		int y0 = MARGIN;

		drawHearts(g, player, x0, y0);

		int air = player.getAirSupply();
		int maxAir = player.getMaxAirSupply();
		if (player.isEyeInFluid(FluidTags.WATER) || air < maxAir) {
			int full = Mth.ceil((air - 2) * 10.0 / maxAir);
			int total = Mth.ceil(air * 10.0 / maxAir);
			int airY = y0 + STEP + 3;
			for (int i = 0; i < total; i++) {
				sprite(g, i < full ? AIR : AIR_BURSTING, x0 + i * STEP, airY);
			}
		}
	}
}
