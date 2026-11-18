package com.ocarinaengine.client.screen;

import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import com.ocarinaengine.client.input.ZeldaKeys;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The quick items menu: the game pauses while holding R (or RB),
 * the carousel scrolls smoothly, and the centered item is used with ZR.
 *
 * Close: release R (or RB), Escape, or B (with closing animation).
 * Use: G key, Enter, Space, click the item, or ZR (RT).
 * Keeps the OS mouse cursor hidden when opened or navigated via gamepad until real mouse input occurs.
 */
public final class ZeldaQuickMenuScreen extends Screen {
	private static final int DIM = 0xA0000000;
	private static final int SLOT_FILL = 0xFF21170F;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFB8A888;
	private static final float OPEN_DURATION = 0.16f;
	private static final float CLOSE_DURATION = 0.13f;

	// Layout computed each frame based on the current window size.
	private int cx;
	private int cy;
	private int selSize;
	private int sideSize;
	private int sideOffset;

	// Animations.
	private long lastFrameNanos = 0L;
	private float openTime = 0.0f;
	private boolean closing = false;
	private boolean forceClose = false;
	private float closeTime = 0.0f;
	private float closeStartVisual = 1.0f;
	private float useFlash = 0.0f;
	private float scrollPos;
	private int lastSelected;
	private float nameAnim = 1.0f;
	private float leftArrowAlpha;
	private float rightArrowAlpha;
	private float leftArrowNudge = 0.0f;
	private float rightArrowNudge = 0.0f;

	// Gamepad cursor suppression state.
	private boolean hideMouseCursor;
	private int ignoreMouseMovesTicks;
	private double anchorMouseX;
	private double anchorMouseY;

	// Previous gamepad state.
	private boolean prevLeft;
	private boolean prevRight;
	private boolean prevTrigger;
	private boolean prevInteract;
	private boolean prevCancel;
	private boolean prevStart;

	public ZeldaQuickMenuScreen() {
		this(false);
	}

	public ZeldaQuickMenuScreen(boolean openedWithGamepad) {
		super(Component.literal("Items"));
		this.hideMouseCursor = openedWithGamepad;
		this.ignoreMouseMovesTicks = openedWithGamepad ? 3 : 0;
		this.lastSelected = ZeldaQuickItems.selectedIndex();
		this.scrollPos = this.lastSelected;
		this.leftArrowAlpha = neighbor(-1) >= 0 ? 1.0f : 0.0f;
		this.rightArrowAlpha = neighbor(1) >= 0 ? 1.0f : 0.0f;

		Gamepad.poll();
		prevLeft = Gamepad.dpadLeft || Gamepad.leftX < -0.6f;
		prevRight = Gamepad.dpadRight || Gamepad.leftX > 0.6f;
		prevTrigger = Gamepad.rightTrigger;
		prevInteract = Gamepad.actionInteract;
		prevCancel = Gamepad.actionCancel;
		prevStart = Gamepad.buttonStart;
	}

	@Override
	protected void init() {
		if (!ZeldaQuickItems.hasItems()) {
			super.onClose();
			return;
		}
		captureMouseAnchor();
		if (this.hideMouseCursor) {
			applyGlfwCursorMode(true);
		}
	}

	@Override
	public void removed() {
		applyGlfwCursorMode(false);
		if (ZeldaInventoryClient.isSynced()) {
			ZeldaInventoryClient.sendSnapshot();
		}
		super.removed();
	}

	private void setMouseHidden(boolean hidden) {
		this.hideMouseCursor = hidden;
		if (hidden) {
			captureMouseAnchor();
		}
		applyGlfwCursorMode(hidden);
	}

	private void captureMouseAnchor() {
		Minecraft mc = Minecraft.getInstance();
		int screenW = Math.max(1, mc.getWindow().getScreenWidth());
		int screenH = Math.max(1, mc.getWindow().getScreenHeight());
		this.anchorMouseX = mc.mouseHandler.xpos() * (double) this.width / (double) screenW;
		this.anchorMouseY = mc.mouseHandler.ypos() * (double) this.height / (double) screenH;
	}

	private void syncCursorVisibility() {
		if (!closing && this.hideMouseCursor) {
			applyGlfwCursorMode(true);
		}
	}

	private static void applyGlfwCursorMode(boolean hidden) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getWindow() == null) {
			return;
		}
		long window = mc.getWindow().getWindow();
		int wanted = hidden ? GLFW.GLFW_CURSOR_HIDDEN : GLFW.GLFW_CURSOR_NORMAL;
		if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != wanted) {
			GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, wanted);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	private void startClosing(boolean force) {
		if (!closing) {
			closing = true;
			forceClose = force;
			closeTime = 0.0f;
			closeStartVisual = UiShapes.easeOutCubic(openTime);
		} else if (force) {
			forceClose = true;
		}
	}

	private void resumeOpen() {
		float current = visualProgress();
		closing = false;
		openTime = 1.0f - (float) Math.cbrt(Math.max(0.0f, 1.0f - current));
	}

	@Override
	public void onClose() {
		startClosing(true);
	}

	private float visualProgress() {
		if (closing) {
			return closeStartVisual * (1.0f - UiShapes.easeOutCubic(closeTime));
		}
		return UiShapes.easeOutCubic(openTime);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		float ease = visualProgress();
		g.fill(0, 0, this.width, this.height, UiShapes.withAlpha(DIM, ease));
	}

	private void layout() {
		cx = this.width / 2;
		cy = Math.round(this.height * 0.46f);
		sideSize = Mth.clamp(this.height / 9, 28, 50);
		selSize = Math.round(sideSize * 1.35f);
		sideOffset = selSize / 2 + 22 + sideSize / 2;
	}

	/** Neighbor of the selected item (delta = -1 or +1), or -1 if none exists. */
	private int neighbor(int delta) {
		int index = ZeldaQuickItems.selectedIndex() + delta;
		return index >= 0 && index < ZeldaQuickItems.size() ? index : -1;
	}

	private void updateAnimations() {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
		}
		float dt = Mth.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, 0.05f);
		lastFrameNanos = now;

		if (closing) {
			closeTime = Math.min(1.0f, closeTime + dt / CLOSE_DURATION);
		} else {
			openTime = Math.min(1.0f, openTime + dt / OPEN_DURATION);
		}
		if (useFlash > 0.0f) {
			useFlash = Math.max(0.0f, useFlash - dt / 0.18f);
		}

		int target = ZeldaQuickItems.selectedIndex();
		if (target != lastSelected) {
			int dir = target - lastSelected;
			if (dir < 0) {
				leftArrowNudge = 1.0f;
			} else {
				rightArrowNudge = 1.0f;
			}
			lastSelected = target;
			nameAnim = 0.0f;
		} else if (nameAnim < 1.0f) {
			nameAnim = Math.min(1.0f, nameAnim + dt / 0.14f);
		}

		scrollPos = UiShapes.damp(scrollPos, target, 20.0f, dt);
		if (Math.abs(target - scrollPos) < 0.002f) {
			scrollPos = target;
		}
		leftArrowAlpha = UiShapes.damp(leftArrowAlpha, neighbor(-1) >= 0 ? 1.0f : 0.0f, 18.0f, dt);
		rightArrowAlpha = UiShapes.damp(rightArrowAlpha, neighbor(1) >= 0 ? 1.0f : 0.0f, 18.0f, dt);
		if (leftArrowNudge > 0.0f) {
			leftArrowNudge = Math.max(0.0f, leftArrowNudge - dt / 0.16f);
		}
		if (rightArrowNudge > 0.0f) {
			rightArrowNudge = Math.max(0.0f, rightArrowNudge - dt / 0.16f);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (!ZeldaQuickItems.hasItems()) {
			super.onClose();
			return;
		}

		syncCursorVisibility();
		updateAnimations();
		if (closing && closeTime >= 1.0f) {
			super.onClose();
			return;
		}

		renderBackground(g, mouseX, mouseY, partialTick);
		layout();

		float openEase = visualProgress();
		int openSlideY = Math.round((1.0f - openEase) * 18.0f);
		float openScale = 0.88f + 0.12f * openEase;
		int baseY = cy + openSlideY;

		QuickItem current = ZeldaQuickItems.current();
		if (current == null) {
			if (openEase > 0.02f) {
				g.drawCenteredString(this.font, "No items", cx, baseY, UiShapes.withAlpha(TEXT_DIM, openEase));
			}
			return;
		}

		// Smooth carousel: sort from farthest to closest to the center.
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < ZeldaQuickItems.size(); i++) {
			if (Math.abs(i - scrollPos) <= 1.75f) {
				order.add(i);
			}
		}
		order.sort(Comparator.comparingDouble((Integer i) -> Math.abs(i - scrollPos)).reversed());

		int selectedIdx = ZeldaQuickItems.selectedIndex();
		for (int i : order) {
			float offset = i - scrollPos;
			float absOff = Math.abs(offset);
			float distT = Mth.clamp(absOff, 0.0f, 1.0f);
			float fade = absOff <= 1.0f ? 1.0f : Mth.clamp(1.0f - (absOff - 1.0f) / 0.65f, 0.0f, 1.0f);
			float slotAlpha = openEase * fade;
			if (slotAlpha <= 0.01f) {
				continue;
			}
			int sx = Math.round(cx + offset * sideOffset);
			float pop = (i == selectedIdx) ? (1.0f + 0.12f * (float) Math.sin(Math.PI * useFlash)) : 1.0f;
			int size = Math.max(6, Math.round(Mth.lerp(distT, selSize, sideSize) * openScale * pop));
			float focus = Mth.clamp(1.0f - absOff * 1.4f, 0.0f, 1.0f);
			drawSlot(g, ZeldaQuickItems.ITEMS.get(i), sx, baseY, size, focus, slotAlpha);
		}

		// Left / right arrows with fade and bounce.
		if (leftArrowAlpha * openEase > 0.02f) {
			int lx = cx - selSize / 2 - 10 - Math.round(leftArrowNudge * 4.0f);
			UiShapes.arrow(g, lx, baseY, 5, true, UiShapes.withAlpha(WHITE, leftArrowAlpha * openEase));
		}
		if (rightArrowAlpha * openEase > 0.02f) {
			int rx = cx + selSize / 2 + 10 + Math.round(rightArrowNudge * 4.0f);
			UiShapes.arrow(g, rx, baseY, 5, false, UiShapes.withAlpha(WHITE, rightArrowAlpha * openEase));
		}

		// Item name in larger text below the centered slot.
		float nameEase = UiShapes.easeOutCubic(nameAnim);
		float nameAlpha = openEase * nameEase;
		int nameY = baseY + selSize / 2 + 14 + Math.round((1.0f - nameEase) * 5.0f);
		if (nameAlpha > 0.02f) {
			float nameScale = 1.5f;
			g.pose().pushPose();
			g.pose().translate((float) cx, (float) nameY, 0.0f);
			g.pose().scale(nameScale, nameScale, 1.0f);
			g.drawString(this.font, current.name(), -this.font.width(current.name()) / 2, 0,
					UiShapes.withAlpha(UiShapes.TEXT, nameAlpha), true);
			g.pose().popPose();
		}

		// "ZR  Use" below the item name.
		String useLabel = ZeldaKeys.label(ZeldaKeys.useQuickItem, "ZR");
		String text = "Use";
		int keyW = UiShapes.keyCapWidth(this.font, useLabel);
		int total = keyW + 5 + this.font.width(text);
		int hintX = cx - total / 2;
		int hintY = baseY + selSize / 2 + 38;
		UiShapes.keyCap(g, this.font, useLabel, hintX, hintY, openEase);
		if (openEase > 0.02f) {
			g.drawString(this.font, text, hintX + keyW + 5, hintY + 2, UiShapes.withAlpha(TEXT_DIM, openEase), false);
		}
	}

	private void drawSlot(GuiGraphics g, QuickItem item, int x, int y, int size, float focus, float alpha) {
		int bevel = Math.max(3, size / 6);
		int ring = focus > 0.5f ? 3 : 2;
		int left = x - size / 2;
		int top = y - size / 2;

		int borderColor = UiShapes.withAlpha(UiShapes.lerpColor(UiShapes.BORDER, WHITE, focus), alpha);
		int fillColor = UiShapes.withAlpha(SLOT_FILL, alpha);
		UiShapes.bevel(g, left, top, size, size, bevel, borderColor);
		UiShapes.bevel(g, left + ring, top + ring, size - 2 * ring, size - 2 * ring, Math.max(1, bevel - ring), fillColor);

		if (alpha > 0.08f) {
			float k = size * 0.6f / 16.0f;
			g.pose().pushPose();
			g.pose().translate(x - 8.0f * k, y - 8.0f * k, 0.0f);
			g.pose().scale(k, k, 1.0f);
			g.renderItem(item.stack(), 0, 0);
			g.pose().popPose();
		}

		if (alpha > 0.02f) {
			String count = String.valueOf(item.count());
			g.pose().pushPose();
			g.pose().translate(0.0f, 0.0f, 200.0f);
			g.drawString(this.font, count, left + size - 4 - this.font.width(count), top + size - 11,
					UiShapes.withAlpha(WHITE, alpha), true);
			g.pose().popPose();
		}
	}

	// ------------------------------------------------------------------ controls

	private void scrollItems(int delta) {
		ZeldaQuickItems.scroll(delta);
	}

	private void useAndClose() {
		if (closing || !ZeldaQuickItems.hasItems()) {
			return;
		}
		ZeldaQuickItems.useCurrent();
		useFlash = 1.0f;
		startClosing(true);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (!ZeldaQuickItems.hasItems()) {
			super.onClose();
			return true;
		}
		if (closing) {
			if (!forceClose && ZeldaKeys.quickMenu.matches(key, scanCode)) {
				resumeOpen();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE) {
			startClosing(true);
			return true;
		}
		if (ZeldaKeys.quickMenu.matches(key, scanCode)) {
			return true;
		}
		if (ZeldaKeys.useQuickItem.matches(key, scanCode)) {
			useAndClose();
			return true;
		}
		switch (key) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> scrollItems(-1);
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> scrollItems(1);
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> useAndClose();
			default -> {
				return super.keyPressed(key, scanCode, modifiers);
			}
		}
		return true;
	}

	@Override
	public boolean keyReleased(int key, int scanCode, int modifiers) {
		if (!closing && ZeldaKeys.quickMenu.matches(key, scanCode) && !(Gamepad.connected && Gamepad.rightBumper)) {
			startClosing(false);
			return true;
		}
		return super.keyReleased(key, scanCode, modifiers);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (closing) {
			return;
		}
		if (this.hideMouseCursor) {
			if (this.ignoreMouseMovesTicks > 0) {
				this.anchorMouseX = mouseX;
				this.anchorMouseY = mouseY;
				return;
			}
			double dx = mouseX - this.anchorMouseX;
			double dy = mouseY - this.anchorMouseY;
			if (dx * dx + dy * dy <= 16.0) {
				return;
			}
			setMouseHidden(false);
		}
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (closing || !ZeldaQuickItems.hasItems()) {
			return true;
		}
		if (this.hideMouseCursor) {
			setMouseHidden(false);
		}
		if (scrollY > 0) {
			scrollItems(-1);
			return true;
		}
		if (scrollY < 0) {
			scrollItems(1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (closing || !ZeldaQuickItems.hasItems()) {
			return true;
		}
		if (this.hideMouseCursor) {
			setMouseHidden(false);
		}
		if (button == 0) {
			int half = selSize / 2;
			if (Math.abs(mouseX - cx) <= half && Math.abs(mouseY - cy) <= half) {
				useAndClose();
				return true;
			}
			int sideHalf = sideSize / 2;
			if (Math.abs(mouseY - cy) <= sideHalf) {
				int left = neighbor(-1);
				int right = neighbor(1);
				if (left >= 0 && Math.abs(mouseX - (cx - sideOffset)) <= sideHalf) {
					ZeldaQuickItems.select(left);
					return true;
				}
				if (right >= 0 && Math.abs(mouseX - (cx + sideOffset)) <= sideHalf) {
					ZeldaQuickItems.select(right);
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (!closing && ZeldaKeys.quickMenu.matchesMouse(button) && !(Gamepad.connected && Gamepad.rightBumper)) {
			startClosing(false);
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public void tick() {
		if (this.ignoreMouseMovesTicks > 0) {
			this.ignoreMouseMovesTicks--;
		}
		if (!ZeldaQuickItems.hasItems()) {
			super.onClose();
			return;
		}

		Gamepad.poll();
		boolean padHeld = Gamepad.connected && Gamepad.rightBumper;

		if (closing) {
			if (!forceClose && (ZeldaKeys.isQuickMenuHeld() || padHeld)) {
				resumeOpen();
			}
			return;
		}
		if (!ZeldaKeys.isQuickMenuHeld() && !padHeld) {
			startClosing(false);
			return;
		}
		if (!Gamepad.connected) {
			prevLeft = prevRight = prevTrigger = prevInteract = prevCancel = prevStart = false;
			return;
		}

		boolean leftNow = Gamepad.dpadLeft || Gamepad.leftX < -0.6f;
		boolean rightNow = Gamepad.dpadRight || Gamepad.leftX > 0.6f;
		boolean usedGamepad = false;
		if (leftNow && !prevLeft) {
			scrollItems(-1);
			usedGamepad = true;
		}
		if (rightNow && !prevRight) {
			scrollItems(1);
			usedGamepad = true;
		}
		boolean use = (Gamepad.rightTrigger && !prevTrigger) || (Gamepad.actionInteract && !prevInteract);
		boolean close = (Gamepad.actionCancel && !prevCancel) || (Gamepad.buttonStart && !prevStart);
		if (usedGamepad && !use && !close && !this.hideMouseCursor) {
			setMouseHidden(true);
		}

		prevLeft = leftNow;
		prevRight = rightNow;
		prevTrigger = Gamepad.rightTrigger;
		prevInteract = Gamepad.actionInteract;
		prevCancel = Gamepad.actionCancel;
		prevStart = Gamepad.buttonStart;

		if (use) {
			useAndClose();
		} else if (close) {
			startClosing(true);
		}
	}
}
