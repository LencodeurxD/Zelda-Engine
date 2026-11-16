package com.ocarinaengine.client.hud;

import com.ocarinaengine.client.gametype.OcarinaGameTypeClient;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.rules.ZeldaRules;
import com.ocarinaengine.client.screen.UiShapes;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Quick item slot in the top-right corner: selected item, its count,
 * "R" above (open menu) with neighbor indicators, and "ZR" below (use).
 * Hidden whenever the player owns no items (equipment does not count) or in Minecraft mode.
 */
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

	/** Called when the player uses the quick item (ZR / G). */
	public static void triggerUseAnimation() {
		usePulse = 1.0f;
	}

	private static void render(GuiGraphics g) {
		if (!OcarinaGameTypeClient.isZelda() || !ZeldaRules.quickItems || !ZeldaQuickItems.hasItems()) {
			lastIndex = -1;
			usePulse = 0.0f;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) {
			return;
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

		// "R" above the slot + small white square indicators (max 2 per side).
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

		// The slot (with bounce and flash on use).
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
		g.renderItem(item.stack(), 0, 0);
		g.pose().popPose();

		String count = String.valueOf(item.count());
		g.pose().pushPose();
		g.pose().translate(0.0f, 0.0f, 200.0f);
		g.drawString(font, count, x + SIZE - 3 - font.width(count), y + SIZE - 10, WHITE, true);
		g.pose().popPose();

		g.pose().popPose();

		// "ZR" below the slot.
		String useLabel = ZeldaKeys.label(ZeldaKeys.useQuickItem, "ZR");
		UiShapes.keyCap(g, font, useLabel, x + SIZE / 2 - UiShapes.keyCapWidth(font, useLabel) / 2, y + SIZE + 3);
	}
}
