package com.ocarinaengine.client.hud;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.render.ZeldaItemIcons;
import com.ocarinaengine.client.rules.ZeldaRules;
import com.ocarinaengine.client.screen.UiShapes;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

public final class QuickItemHud {
	private static final int SIZE = 32;
	private static final int SLOT_BORDER = 0xFF6E5836;
	private static final int SLOT_FILL = 0xE0140E09;
	private static final int WHITE = 0xFFFFFFFF;

	private static long lastFrameNanos = 0L;
	private static int lastIndex = -1;
	private static int switchDir = 0;
	private static float switchAnim = 1.0f;
	private static float usePulse = 0.0f;
	private static float leftNearAlpha = 0.0f;
	private static float leftFarAlpha = 0.0f;
	private static float rightNearAlpha = 0.0f;
	private static float rightFarAlpha = 0.0f;

	private QuickItemHud() {}

	public static void register() {
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> render(graphics));
	}

	public static void triggerUseAnimation() {
		usePulse = 1.0f;
	}

	private static void render(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		float flash = ZeldaQuickItems.nutFlashAlpha();
		if (flash > 0.01f && mc.player != null) {
			int flashAlpha = Mth.clamp((int) (flash * 220.0f), 0, 220);
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), (flashAlpha << 24) | 0xFFFFFF);
		}

		if (!OcarinaGameTypeClient.isZelda() || !ZeldaRules.quickItems || !ZeldaQuickItems.hasItems()) {
			lastIndex = -1;
			usePulse = 0.0f;
			return;
		}
		if (mc.player == null || mc.options.hideGui) {
			return;
		}

		if (ZeldaQuickItems.isAimingSlingshot()) {
			renderSlingshotReticle(g);
		}

		QuickItem item = ZeldaQuickItems.current();
		if (item == null) {
			lastIndex = -1;
			return;
		}

		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
		}
		float dt = Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, 0.05f);
		lastFrameNanos = now;

		int index = ZeldaQuickItems.selectedIndex();
		if (lastIndex < 0) {
			lastIndex = index;
		} else if (index != lastIndex) {
			switchDir = index > lastIndex ? 1 : -1;
			switchAnim = 0.0f;
			lastIndex = index;
		} else if (switchAnim < 1.0f) {
			switchAnim = Math.min(1.0f, switchAnim + dt / 0.18f);
		}
		if (usePulse > 0.0f) {
			usePulse = Math.max(0.0f, usePulse - dt / 0.22f);
		}

		int leftCount = Math.min(2, index);
		int rightCount = Math.min(2, ZeldaQuickItems.size() - 1 - index);
		leftNearAlpha = UiShapes.damp(leftNearAlpha, leftCount >= 1 ? 0.50f : 0.0f, 16.0f, dt);
		leftFarAlpha = UiShapes.damp(leftFarAlpha, leftCount >= 2 ? 0.10f : 0.0f, 16.0f, dt);
		rightNearAlpha = UiShapes.damp(rightNearAlpha, rightCount >= 1 ? 0.50f : 0.0f, 16.0f, dt);
		rightFarAlpha = UiShapes.damp(rightFarAlpha, rightCount >= 2 ? 0.10f : 0.0f, 16.0f, dt);

		Font font = mc.font;
		int x = g.guiWidth() - SIZE - 10;
		int y = 22;

		String openLabel = ZeldaKeys.label(ZeldaKeys.quickMenu, "R");
		int capW = UiShapes.keyCapWidth(font, openLabel);
		int capX = x + SIZE / 2 - capW / 2;
		int capY = y - 15;
		UiShapes.keyCap(g, font, openLabel, capX, capY);
		int midY = capY + 6;
		if (leftNearAlpha > 0.01f) {
			g.fill(capX - 6, midY - 2, capX - 2, midY + 2, UiShapes.withAlpha(WHITE, leftNearAlpha));
		}
		if (leftFarAlpha > 0.01f) {
			g.fill(capX - 10, midY - 1, capX - 8, midY + 1, UiShapes.withAlpha(WHITE, leftFarAlpha));
		}
		if (rightNearAlpha > 0.01f) {
			int rx = capX + capW + 2;
			g.fill(rx, midY - 2, rx + 4, midY + 2, UiShapes.withAlpha(WHITE, rightNearAlpha));
		}
		if (rightFarAlpha > 0.01f) {
			int rx2 = capX + capW + 8;
			g.fill(rx2, midY - 1, rx2 + 2, midY + 1, UiShapes.withAlpha(WHITE, rightFarAlpha));
		}

		float slotPop = 1.0f + 0.14f * (float) Math.sin(Math.PI * usePulse);
		int border = UiShapes.lerpColor(SLOT_BORDER, WHITE, usePulse * 0.9f);
		float cxSlot = x + SIZE / 2.0f;
		float cySlot = y + SIZE / 2.0f;

		g.pose().pushPose();
		g.pose().translate(cxSlot, cySlot, 0.0f);
		g.pose().scale(slotPop, slotPop, 1.0f);
		g.pose().translate(-cxSlot, -cySlot, 0.0f);

		UiShapes.bevel(g, x, y, SIZE, SIZE, 4, border);
		UiShapes.bevel(g, x + 2, y + 2, SIZE - 4, SIZE - 4, 2, SLOT_FILL);

		float switchEase = UiShapes.easeOutCubic(switchAnim);
		float iconSlideX = switchDir * (1.0f - switchEase) * 10.0f;
		float iconScale = 0.85f + 0.15f * switchEase;
		float k = 1.4f * iconScale;
		g.pose().pushPose();
		g.pose().translate(cxSlot + iconSlideX - 8.0f * k, cySlot - 8.0f * k, 0.0f);
		g.pose().scale(k, k, 1.0f);
		ZeldaItemIcons.draw16(g, item.id(), item.stack(), 0, 0);
		g.pose().popPose();

		String count = String.valueOf(item.count());
		g.pose().pushPose();
		g.pose().translate(0.0f, 0.0f, 200.0f);
		g.drawString(font, count, x + SIZE - 3 - font.width(count), y + SIZE - 10, WHITE, true);
		g.pose().popPose();
		g.pose().popPose();

		String useLabel = ZeldaKeys.label(ZeldaKeys.useQuickItem, "ZR");
		UiShapes.keyCap(g, font, useLabel, x + SIZE / 2 - UiShapes.keyCapWidth(font, useLabel) / 2, y + SIZE + 3);
	}

	private static void renderSlingshotReticle(GuiGraphics g) {
		int cx = g.guiWidth() / 2;
		int cy = g.guiHeight() / 2;
		float charge = ZeldaQuickItems.slingshotChargeProgress();
		boolean onTarget = ZeldaQuickItems.isAimHoveringEntity();

		int color = onTarget ? 0xFFFF5555 : (charge > 0.92f ? 0xFFFFE04A : 0xFFFFF4D8);
		int shadow = 0xAA000000;
		int gap = 4 + Math.round((1.0f - charge) * 13.0f);
		int armLen = 5;

		// Center dot
		g.fill(cx - 2, cy - 2, cx + 2, cy + 2, shadow);
		g.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);

		// Contracting crosshair ticks
		g.fill(cx - gap - armLen - 1, cy - 1, cx - gap + 1, cy + 1, shadow);
		g.fill(cx - gap - armLen, cy, cx - gap, cy + 1, color);

		g.fill(cx + gap - 1, cy - 1, cx + gap + armLen + 1, cy + 1, shadow);
		g.fill(cx + gap, cy, cx + gap + armLen, cy + 1, color);

		g.fill(cx - 1, cy - gap - armLen - 1, cx + 1, cy - gap + 1, shadow);
		g.fill(cx, cy - gap - armLen, cx + 1, cy - gap, color);

		g.fill(cx - 1, cy + gap - 1, cx + 1, cy + gap + armLen + 1, shadow);
		g.fill(cx, cy + gap, cx + 1, cy + gap + armLen, color);

		// Slim charge gauge underneath
		int barW = 24;
		int barX = cx - barW / 2;
		int barY = cy + gap + armLen + 5;
		g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 3, shadow);
		g.fill(barX, barY, barX + Math.round(barW * charge), barY + 2, color);
	}
}