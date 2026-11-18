package com.ocarinaengine.client.screen;

import com.ocarinaengine.client.equipment.EquipSlot;
import com.ocarinaengine.client.equipment.ZeldaEquipment;
import com.ocarinaengine.client.equipment.ZeldaItem;
import com.ocarinaengine.client.hud.ZeldaHud;
import com.ocarinaengine.client.input.Gamepad;
import com.ocarinaengine.client.inventory.ZeldaInventoryClient;
import com.ocarinaengine.client.items.QuickItem;
import com.ocarinaengine.client.items.ZeldaQuickItems;
import com.ocarinaengine.client.render.ZeldaShieldRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * The main menu (E key / Start):
 * - Equipment tab: swords, shields, outfits, masks, boots, and the player in an idle pose;
 * - Items tab: 22-slot shaped grid inspired by the original on the left, large preview on the right;
 * - Smooth animations: upward open, downward close, tab sliding,
 *   sliding cursor, and bounce on activation.
 * - Keeps the OS mouse cursor hidden when opened or navigated via gamepad until real mouse input occurs.
 */
public final class ZeldaEquipmentScreen extends Screen {
	private static final String[] TABS = {"Collection", "Equipment", "Items", "System"};
	private static final int TAB_EQUIPMENT = 1;
	private static final int TAB_ITEMS = 2;
	private static final int CONTENT_TOP = 34;

	private static final int BG_TOP = 0xFF2A1F15;
	private static final int BG_BOTTOM = 0xFF120C08;
	private static final int BAR = 0xFF0E0906;
	private static final int BORDER = 0xFF6E5836;
	private static final int BORDER_DARK = 0xFF3A2C1B;
	private static final int EMPTY_BORDER = 0xFF332617;
	private static final int EMPTY_FILL = 0xFF140E09;
	private static final int GOLD = 0xFFD2A62E;
	private static final int GOLD_FILL = 0xFF7D5D17;
	private static final int SLOT_FILL = 0xFF21170F;
	private static final int TEXT = 0xFFF2E8D0;
	private static final int TEXT_DIM = 0xFFB8A888;
	private static final int WHITE = 0xFFFFFFFF;

	private static final float OPEN_DURATION = 0.24f;
	private static final float CLOSE_DURATION = 0.16f;
	private static final float TAB_DURATION = 0.22f;

	/** Visual copy of the player frozen in an idle pose (standing, no running, jumping, swimming, or swinging). */
	private static final class IdlePreviewPlayer extends AbstractClientPlayer {
		private final LocalPlayer source;

		IdlePreviewPlayer(ClientLevel level, LocalPlayer source) {
			super(level, source.getGameProfile());
			this.source = source;
		}

		@Override
		public boolean isSpectator() {
			return false;
		}

		@Override
		public boolean isCreative() {
			return source.isCreative();
		}

		@Override
		public PlayerSkin getSkin() {
			return source.getSkin();
		}

		@Override
		public boolean isModelPartShown(PlayerModelPart part) {
			return source.isModelPartShown(part);
		}

		@Override
		public HumanoidArm getMainArm() {
			return source.getMainArm();
		}

		@Override
		public ItemStack getItemBySlot(EquipmentSlot slot) {
			return source.getItemBySlot(slot);
		}

		@Override
		public Iterable<ItemStack> getArmorSlots() {
			return source.getArmorSlots();
		}

		@Override
		public Iterable<ItemStack> getHandSlots() {
			return source.getHandSlots();
		}

		@Override
		public boolean isInvisible() {
			return false;
		}

		@Override
		public boolean isInvisibleTo(Player viewer) {
			return true;
		}
	}

	/** True when the rendered entity is the Equipment screen character preview. */
	public static boolean isPreviewEntity(LivingEntity entity) {
		return entity instanceof IdlePreviewPlayer;
	}

	/** A slot cell in the Equipment tab. */
	private static final class Cell {
		final int cx;
		final int cy;
		final int size;
		final boolean hex;
		final ZeldaItem item;
		float hoverAnim;
		float equipAnim;
		float equipPulse;

		Cell(int cx, int cy, int size, boolean hex, ZeldaItem item) {
			this.cx = cx;
			this.cy = cy;
			this.size = size;
			this.hex = hex;
			this.item = item;
			this.equipAnim = (item != null && ZeldaEquipment.isEquipped(item)) ? 1.0f : 0.0f;
		}

		int halfW() {
			return hex ? Math.round(size * 0.866f) : size / 2;
		}

		int halfH() {
			return hex ? size : size / 2;
		}

		boolean contains(double x, double y) {
			return Math.abs(x - cx) <= halfW() && Math.abs(y - cy) <= halfH();
		}
	}

	/** A slot cell in the Items tab, with its own custom shape. */
	private static final class ItemCell {
		final int cx;
		final int cy;
		final int w;
		final int h;
		final UiShapes.SlotShape shape;
		final QuickItem item;
		float hoverAnim;
		float equipAnim;
		float equipPulse;

		ItemCell(int cx, int cy, int w, int h, UiShapes.SlotShape shape, QuickItem item) {
			this.cx = cx;
			this.cy = cy;
			this.w = w;
			this.h = h;
			this.shape = shape;
			this.item = item;
			this.equipAnim = (item != null && ZeldaQuickItems.isSelected(item)) ? 1.0f : 0.0f;
		}

		boolean contains(double x, double y) {
			return Math.abs(x - cx) <= w / 2.0 && Math.abs(y - cy) <= h / 2.0;
		}
	}

	private final List<Cell> cells = new ArrayList<>();
	private final List<ItemCell> itemCells = new ArrayList<>();

	private int cursor = 0;
	private int itemCursor = 0;
	private int tab = TAB_EQUIPMENT;
	private IdlePreviewPlayer previewPlayer = null;

	// Equipment tab geometry.
	private int ovalCx;
	private int ovalCy;
	private int ovalRx;
	private int ovalRy;
	private int ornamentX;
	private int ornamentY;

	// Items tab geometry.
	private int itemsPanelLeft;
	private int itemsPanelRight;
	private int itemsPanelTop;
	private int itemsPanelBottom;
	private float itemsUnit;
	private int itemsGridX0;
	private int itemsGridY0;

	// Animations.
	private long lastFrameNanos = 0L;
	private float openTime = 0.0f;
	private boolean closing = false;
	private float closeTime = 0.0f;
	private float closeStartVisual = 1.0f;
	private int prevTab = -1;
	private int tabDir = 1;
	private float tabAnim = 1.0f;
	private float tabIndicatorX = 0.0f;
	private int tabKeyPulseDir = 0;
	private float tabKeyPulse = 0.0f;
	private float cursorX;
	private float cursorY;
	private float cursorHalf;
	private int lastCursor = -1;
	private float descAnim = 1.0f;

	private float itemCursorX;
	private float itemCursorY;
	private float itemCursorW;
	private float itemCursorH;
	private int lastItemCursor = -1;
	private float itemDescAnim = 1.0f;

	// Gamepad cursor suppression state.
	private boolean hideMouseCursor;
	private int ignoreMouseMovesTicks;
	private double anchorMouseX;
	private double anchorMouseY;

	// Previous gamepad state.
	private boolean prevUp;
	private boolean prevDown;
	private boolean prevLeft;
	private boolean prevRight;
	private boolean prevInteract;
	private boolean prevCancel;
	private boolean prevStart;
	private boolean prevLb;
	private boolean prevRb;

	public ZeldaEquipmentScreen() {
		this(false);
	}

	public ZeldaEquipmentScreen(boolean openedWithGamepad) {
		super(Component.literal("Equipment"));
		this.hideMouseCursor = openedWithGamepad;
		this.ignoreMouseMovesTicks = openedWithGamepad ? 3 : 0;
		Gamepad.poll();
		prevUp = Gamepad.dpadUp || Gamepad.leftY > 0.6f;
		prevDown = Gamepad.dpadDown || Gamepad.leftY < -0.6f;
		prevLeft = Gamepad.dpadLeft || Gamepad.leftX < -0.6f;
		prevRight = Gamepad.dpadRight || Gamepad.leftX > 0.6f;
		prevInteract = Gamepad.actionInteract;
		prevCancel = Gamepad.actionCancel;
		prevStart = Gamepad.buttonStart;
		prevLb = Gamepad.leftBumper;
		prevRb = Gamepad.rightBumper;
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void onClose() {
		if (!closing) {
			closing = true;
			closeTime = 0.0f;
			closeStartVisual = UiShapes.easeOutCubic(openTime);
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

	// ------------------------------------------------------------------ cursor management

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

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		buildCells();
		buildItemCells();

		cursor = 0;
		for (int i = 0; i < cells.size(); i++) {
			ZeldaItem item = cells.get(i).item;
			if (item != null && ZeldaEquipment.isEquipped(item)) {
				cursor = i;
				break;
			}
		}
		if (!cells.isEmpty()) {
			Cell c = cells.get(cursor);
			c.hoverAnim = 1.0f;
			cursorX = c.cx;
			cursorY = c.cy;
			cursorHalf = c.halfH();
		}
		lastCursor = cursor;

		itemCursor = Mth.clamp(itemCursor, 0, Math.max(0, itemCells.size() - 1));
		if (!itemCells.isEmpty()) {
			ItemCell ic = itemCells.get(itemCursor);
			ic.hoverAnim = 1.0f;
			itemCursorX = ic.cx;
			itemCursorY = ic.cy;
			itemCursorW = ic.w;
			itemCursorH = ic.h;
		}
		lastItemCursor = itemCursor;

		tabIndicatorX = tabsLeft() + tab * tabWidth();

		captureMouseAnchor();
		if (this.hideMouseCursor) {
			applyGlfwCursorMode(true);
		}
	}

	private void buildCells() {
		cells.clear();
		int contentBottom = this.height - 28;
		int contentH = contentBottom - CONTENT_TOP;

		// --- Right side: one row per category (sword, shield, outfit), 3 items per row ---
		int cs = Mth.clamp((int) Math.min(this.width * 0.09f, contentH / 3.4f), 24, 60);
		int gap = Math.max(3, cs / 8);
		int gridSize = 3 * cs + 2 * gap;
		int gridX = this.width - gridSize - Math.max(8, this.width / 36);
		int gridY = CONTENT_TOP + (contentH - gridSize) / 2;

		EquipSlot[] rows = {EquipSlot.SWORD, EquipSlot.SHIELD, EquipSlot.OUTFIT};
		for (int r = 0; r < rows.length; r++) {
			List<ZeldaItem> owned = ZeldaEquipment.owned(rows[r]);
			for (int c = 0; c < 3; c++) {
				ZeldaItem item = c < owned.size() ? owned.get(c) : null;
				cells.add(new Cell(gridX + c * (cs + gap) + cs / 2, gridY + r * (cs + gap) + cs / 2, cs, false, item));
			}
		}

		// --- Left side, hexagonal slots: 2 masks, 2 pairs of boots, and 1 free slot ---
		int radius = Math.max(14, Math.round(cs * 0.7f));
		float pitchX = radius * 1.95f;
		float pitchY = radius * 2.05f;
		int gx = Math.round(this.width * 0.165f);
		float totalH = pitchY * 2.1f + 2 * radius;
		int baseY = Math.round(CONTENT_TOP + (contentH - totalH) / 2f + radius);

		List<ZeldaItem> masks = ZeldaEquipment.owned(EquipSlot.MASK);
		List<ZeldaItem> boots = ZeldaEquipment.owned(EquipSlot.BOOTS);
		for (int i = 0; i < 2; i++) {
			int x = Math.round(gx + (i == 0 ? -1 : 1) * pitchX / 2f);
			cells.add(new Cell(x, baseY, radius, true, i < masks.size() ? masks.get(i) : null));
		}
		for (int i = 0; i < 2; i++) {
			int x = Math.round(gx + (i == 0 ? -1 : 1) * pitchX / 2f);
			cells.add(new Cell(x, Math.round(baseY + pitchY), radius, true, i < boots.size() ? boots.get(i) : null));
		}
		cells.add(new Cell(gx, Math.round(baseY + pitchY * 2.1f), radius, true, null));

		ornamentX = gx;
		ornamentY = Math.round(baseY + pitchY / 2f);

		// --- Center: the oval between the two groups ---
		int leftEdge = Math.round(gx + pitchX / 2f + radius * 0.866f);
		ovalCx = (leftEdge + gridX) / 2;
		ovalRx = Math.round((gridX - leftEdge) / 2f * 0.88f);
		ovalCy = CONTENT_TOP + contentH / 2;
		ovalRy = Math.round(contentH * 0.5f);
	}

	private void buildItemCells() {
		itemCells.clear();
		itemsPanelLeft = Math.max(10, Math.round(this.width * 0.025f));
		itemsPanelRight = Math.round(this.width * 0.545f);
		itemsPanelTop = CONTENT_TOP + 6;
		itemsPanelBottom = this.height - 34;

		int availW = itemsPanelRight - itemsPanelLeft;
		int availH = itemsPanelBottom - itemsPanelTop;
		itemsUnit = Math.min(availW / 9.2f, availH / 8.7f);
		itemsGridX0 = Math.round(itemsPanelLeft + (availW - 9.1f * itemsUnit) / 2.0f);
		itemsGridY0 = Math.round(itemsPanelTop + (availH - 8.6f * itemsUnit) / 2.0f);

		int slotIndex = 0;

		// Column 0 (left): tall tapered slot at top, square, square, flat hex, square.
		addItemSlot(UiShapes.SlotShape.TAPER_DOWN, 0.20f, 0.15f, 1.45f, 2.15f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,     0.20f, 2.55f, 1.45f, 1.15f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,     0.20f, 3.95f, 1.45f, 1.45f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.HEX_FLAT,   0.15f, 5.65f, 1.55f, 1.35f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,     0.20f, 7.20f, 1.45f, 1.25f, ZeldaQuickItems.inSlot(slotIndex++));

		// Column 1: tapered slot, chevron, square, tall hex, square.
		addItemSlot(UiShapes.SlotShape.TAPER_DOWN,    1.95f, 0.15f, 1.45f, 1.55f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.CHEVRON_RIGHT, 1.95f, 1.95f, 1.45f, 1.55f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,        1.95f, 3.95f, 1.45f, 1.45f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.HEX_TALL,      1.95f, 5.60f, 1.45f, 1.45f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,        1.95f, 7.20f, 1.45f, 1.25f, ZeldaQuickItems.inSlot(slotIndex++));

		// Upper middle (column 2): large cut-corner square (Bow) + 3 pointed bars (Arrows).
		addItemSlot(UiShapes.SlotShape.CUT_TL_BR,  3.90f, 0.15f, 2.35f, 2.30f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.ARROW_LEFT, 3.90f, 2.75f, 2.35f, 0.65f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.ARROW_LEFT, 3.90f, 3.65f, 2.35f, 0.65f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.ARROW_LEFT, 3.90f, 4.55f, 2.35f, 0.65f, ZeldaQuickItems.inSlot(slotIndex++));

		// Upper right of the panel: large right-cut rectangle + upward trapezoid below.
		addItemSlot(UiShapes.SlotShape.CUT_TR_BR,    6.60f, 0.15f, 2.30f, 3.10f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.TRAPEZOID_UP, 6.65f, 3.55f, 2.20f, 1.65f, ZeldaQuickItems.inSlot(slotIndex++));

		// Lower middle and lower right: 2 tall hexes + square, then 2 trapezoids + octagon.
		addItemSlot(UiShapes.SlotShape.HEX_TALL,     3.80f, 5.60f, 1.45f, 1.45f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.HEX_TALL,     5.45f, 5.60f, 1.45f, 1.45f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.SQUARE,       7.45f, 5.65f, 1.45f, 1.35f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.TRAPEZOID_UP, 3.80f, 7.20f, 1.45f, 1.25f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.TRAPEZOID_UP, 5.45f, 7.20f, 1.45f, 1.25f, ZeldaQuickItems.inSlot(slotIndex++));
		addItemSlot(UiShapes.SlotShape.OCTAGON,      7.45f, 7.20f, 1.45f, 1.25f, ZeldaQuickItems.inSlot(slotIndex++));
	}

	private void addItemSlot(UiShapes.SlotShape shape, float ux, float uy, float uw, float uh, QuickItem item) {
		int w = Math.max(8, Math.round(uw * itemsUnit));
		int h = Math.max(8, Math.round(uh * itemsUnit));
		int cx = Math.round(itemsGridX0 + ux * itemsUnit + w / 2.0f);
		int cy = Math.round(itemsGridY0 + uy * itemsUnit + h / 2.0f);
		itemCells.add(new ItemCell(cx, cy, w, h, shape, item));
	}

	// ------------------------------------------------------------------ animations

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
		if (tabAnim < 1.0f) {
			tabAnim = Math.min(1.0f, tabAnim + dt / TAB_DURATION);
		}
		if (tabKeyPulse > 0.0f) {
			tabKeyPulse = Math.max(0.0f, tabKeyPulse - dt / 0.16f);
		}

		if (cursor != lastCursor) {
			lastCursor = cursor;
			descAnim = 0.0f;
		} else if (descAnim < 1.0f) {
			descAnim = Math.min(1.0f, descAnim + dt / 0.15f);
		}

		if (itemCursor != lastItemCursor) {
			lastItemCursor = itemCursor;
			itemDescAnim = 0.0f;
		} else if (itemDescAnim < 1.0f) {
			itemDescAnim = Math.min(1.0f, itemDescAnim + dt / 0.16f);
		}

		float targetTabX = tabsLeft() + tab * tabWidth();
		tabIndicatorX = UiShapes.damp(tabIndicatorX, targetTabX, 20.0f, dt);

		if (!cells.isEmpty()) {
			Cell target = cells.get(cursor);
			cursorX = UiShapes.damp(cursorX, target.cx, 22.0f, dt);
			cursorY = UiShapes.damp(cursorY, target.cy, 22.0f, dt);
			cursorHalf = UiShapes.damp(cursorHalf, target.halfH(), 22.0f, dt);

			for (int i = 0; i < cells.size(); i++) {
				Cell c = cells.get(i);
				float wantHover = (i == cursor) ? 1.0f : 0.0f;
				float wantEquip = (c.item != null && ZeldaEquipment.isEquipped(c.item)) ? 1.0f : 0.0f;
				c.hoverAnim = UiShapes.damp(c.hoverAnim, wantHover, 18.0f, dt);
				c.equipAnim = UiShapes.damp(c.equipAnim, wantEquip, 16.0f, dt);
				if (c.equipPulse > 0.0f) {
					c.equipPulse = Math.max(0.0f, c.equipPulse - dt / 0.28f);
				}
			}
		}

		if (!itemCells.isEmpty()) {
			ItemCell target = itemCells.get(itemCursor);
			itemCursorX = UiShapes.damp(itemCursorX, target.cx, 22.0f, dt);
			itemCursorY = UiShapes.damp(itemCursorY, target.cy, 22.0f, dt);
			itemCursorW = UiShapes.damp(itemCursorW, target.w, 22.0f, dt);
			itemCursorH = UiShapes.damp(itemCursorH, target.h, 22.0f, dt);

			for (int i = 0; i < itemCells.size(); i++) {
				ItemCell c = itemCells.get(i);
				float wantHover = (i == itemCursor) ? 1.0f : 0.0f;
				float wantEquip = (c.item != null && ZeldaQuickItems.isSelected(c.item)) ? 1.0f : 0.0f;
				c.hoverAnim = UiShapes.damp(c.hoverAnim, wantHover, 18.0f, dt);
				c.equipAnim = UiShapes.damp(c.equipAnim, wantEquip, 16.0f, dt);
				if (c.equipPulse > 0.0f) {
					c.equipPulse = Math.max(0.0f, c.equipPulse - dt / 0.28f);
				}
			}
		}
	}

	private float visualProgress() {
		if (closing) {
			return closeStartVisual * (1.0f - UiShapes.easeOutCubic(closeTime));
		}
		return UiShapes.easeOutCubic(openTime);
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Background drawn in render(): do not let Minecraft blur the world behind it.
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		syncCursorVisibility();
		updateAnimations();
		if (closing && closeTime >= 1.0f) {
			super.onClose();
			return;
		}

		float openEase = visualProgress();
		int contentSlideY = Math.round((1.0f - openEase) * 40.0f);
		int topSlideY = Math.round((1.0f - openEase) * 14.0f);
		int bottomSlideY = Math.round((1.0f - openEase) * 16.0f);

		g.fillGradient(0, 0, this.width, this.height,
				UiShapes.withAlpha(BG_TOP, openEase),
				UiShapes.withAlpha(BG_BOTTOM, openEase));

		float slideDist = this.width * 0.36f;
		float te = UiShapes.easeOutCubic(tabAnim);
		if (tabAnim < 1.0f && prevTab >= 0 && prevTab != tab) {
			int outX = Math.round(-tabDir * te * slideDist);
			int inX = Math.round(tabDir * (1.0f - te) * slideDist);
			drawTabPage(g, prevTab, outX, contentSlideY, openEase * (1.0f - te), mouseX, mouseY);
			drawTabPage(g, tab, inX, contentSlideY, openEase * te, mouseX, mouseY);
		} else {
			drawTabPage(g, tab, 0, contentSlideY, openEase, mouseX, mouseY);
		}

		drawTabs(g, openEase, topSlideY);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && openEase > 0.02f) {
			g.setColor(1.0f, 1.0f, 1.0f, openEase);
			ZeldaHud.drawHearts(g, player, 8, 8 + topSlideY);
			g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
		}
		drawWallet(g, openEase, topSlideY);
		drawFooter(g, openEase, bottomSlideY);
	}

	private void drawTabPage(GuiGraphics g, int tabIndex, int offsetX, int offsetY, float alpha, int mouseX, int mouseY) {
		if (alpha <= 0.01f) {
			return;
		}
		if (tabIndex == TAB_EQUIPMENT) {
			drawEquipment(g, offsetX, offsetY, alpha, mouseX, mouseY);
		} else if (tabIndex == TAB_ITEMS) {
			drawItems(g, offsetX, offsetY, alpha);
		} else if (alpha > 0.02f) {
			g.drawCenteredString(this.font, "Coming soon",
					this.width / 2 + offsetX, this.height / 2 + offsetY, UiShapes.withAlpha(TEXT_DIM, alpha));
		}
	}

	private int tabWidth() {
		return Mth.clamp(this.width / 7, 58, 110);
	}

	private int tabsLeft() {
		return (this.width - TABS.length * tabWidth()) / 2;
	}

	private void drawTabs(GuiGraphics g, float alpha, int slideY) {
		int tw = tabWidth();
		int x0 = tabsLeft();
		int y0 = 4 + slideY;
		int th = 22;

		g.fill(0, slideY, this.width, y0 + th + 2, UiShapes.withAlpha(BAR, alpha));
		g.fill(0, y0 + th + 2, this.width, y0 + th + 3, UiShapes.withAlpha(BORDER_DARK, alpha));

		int qOff = (tabKeyPulseDir < 0) ? Math.round(-2.5f * tabKeyPulse) : 0;
		int rOff = (tabKeyPulseDir > 0) ? Math.round(2.5f * tabKeyPulse) : 0;
		UiShapes.keyCap(g, this.font, "Q", x0 - 22 + qOff, 9 + slideY, alpha);
		UiShapes.keyCap(g, this.font, "R", x0 + TABS.length * tw + 6 + rOff, 9 + slideY, alpha);

		int indX = Math.round(tabIndicatorX);
		g.fill(indX, y0, indX + tw, y0 + th, UiShapes.withAlpha(0xFF3B2A18, alpha));
		g.fill(indX, y0 + th - 2, indX + tw, y0 + th, UiShapes.withAlpha(GOLD, alpha));

		if (alpha > 0.02f) {
			for (int i = 0; i < TABS.length; i++) {
				int x = x0 + i * tw;
				float focus = Mth.clamp(1.0f - Math.abs(tabIndicatorX - x) / tw, 0.0f, 1.0f);
				int col = UiShapes.withAlpha(UiShapes.lerpColor(TEXT_DIM, TEXT, focus), alpha);
				g.drawCenteredString(this.font, TABS[i], x + tw / 2, y0 + 7, col);
			}
		}
	}

	private void drawWallet(GuiGraphics g, float alpha, int slideY) {
		String text = ZeldaEquipment.rupees + "/" + ZeldaEquipment.MAX_RUPEES;
		int right = this.width - 10;
		if (alpha > 0.02f) {
			g.drawString(this.font, text, right - this.font.width(text), 10 + slideY, UiShapes.withAlpha(TEXT, alpha), true);
		}
		int gemX = right - this.font.width(text) - 10;
		int gemY = 14 + slideY;
		UiShapes.hex(g, gemX, gemY, 7, UiShapes.withAlpha(0xFF1B5E2B, alpha));
		UiShapes.hex(g, gemX, gemY, 5, UiShapes.withAlpha(0xFF4CD06A, alpha));
	}

	// ------------------------------------------------------------------ Items tab

	private void drawItems(GuiGraphics g, int offsetX, int offsetY, float alpha) {
		int splitX = itemsPanelRight + Math.round(itemsUnit * 0.35f) + offsetX;
		g.fill(itemsPanelLeft + offsetX, itemsPanelTop + offsetY, splitX, itemsPanelBottom + offsetY,
				UiShapes.withAlpha(0x661C140C, alpha));
		g.fill(splitX, CONTENT_TOP + offsetY, this.width + offsetX, this.height - 28 + offsetY,
				UiShapes.withAlpha(0xAA080604, alpha));

		int lineCol = UiShapes.withAlpha(0xFF4A3A24, alpha * 0.7f);
		int sepX = Math.round(itemsGridX0 + 3.60f * itemsUnit) + offsetX;
		int sepY = Math.round(itemsGridY0 + 5.45f * itemsUnit) + offsetY;
		int topY = Math.round(itemsGridY0 + 0.15f * itemsUnit) + offsetY;
		int rightEnd = Math.round(itemsGridX0 + 8.90f * itemsUnit) + offsetX;
		int leftEnd = Math.round(itemsGridX0 + 0.15f * itemsUnit) + offsetX;
		g.fill(sepX, topY, sepX + 1, sepY, lineCol);
		g.fill(leftEnd, sepY, rightEnd, sepY + 1, lineCol);
		UiShapes.arrow(g, itemsPanelLeft - 3 + offsetX,
				(itemsPanelTop + itemsPanelBottom) / 2 + offsetY, 4, true, UiShapes.withAlpha(BORDER, alpha));

		float itemScale = 0.85f + 0.15f * alpha;
		for (ItemCell cell : itemCells) {
			drawItemCell(g, cell, offsetX, offsetY, alpha, itemScale);
		}

		if (!itemCells.isEmpty() && alpha > 0.02f) {
			ItemCell cur = itemCells.get(itemCursor);
			float pop = 1.0f + 0.05f * cur.hoverAnim + 0.10f * (float) Math.sin(Math.PI * cur.equipPulse);
			int rw = Math.max(8, Math.round((itemCursorW + 4) * pop));
			int rh = Math.max(8, Math.round((itemCursorH + 4) * pop));
			int rx = Math.round(itemCursorX) + offsetX - rw / 2;
			int ry = Math.round(itemCursorY) + offsetY - rh / 2;
			UiShapes.drawShapeRing(g, cur.shape, rx, ry, rw, rh, 2, UiShapes.withAlpha(WHITE, alpha));
		}

		ItemCell selectedCell = itemCells.isEmpty() ? null : itemCells.get(itemCursor);
		QuickItem selected = selectedCell == null ? null : selectedCell.item;
		if (selected == null) {
			return;
		}

		int previewCx = (splitX + this.width + offsetX) / 2;
		int contentH = (this.height - 28) - CONTENT_TOP;
		int previewCy = CONTENT_TOP + Math.round(contentH * 0.40f) + offsetY;
		float descEase = UiShapes.easeOutCubic(itemDescAnim);
		float descAlpha = alpha * descEase;

		float bob = (float) Math.sin((System.nanoTime() / 1.0e9) * 2.2) * 2.0f;
		if (descAlpha > 0.08f) {
			float k = Mth.clamp(contentH * 0.25f / 16.0f, 2.5f, 4.8f) * (0.82f + 0.18f * descEase) * (0.85f + 0.15f * alpha);
			g.pose().pushPose();
			g.pose().translate(previewCx - 8.0f * k, previewCy - 8.0f * k + bob, 0.0f);
			g.pose().scale(k, k, 1.0f);
			g.renderItem(selected.stack(), 0, 0);
			g.pose().popPose();
		}

		int textY = CONTENT_TOP + Math.round(contentH * 0.65f) + offsetY + Math.round((1.0f - descEase) * 6.0f);
		if (descAlpha > 0.02f) {
			if (selected.maxCount() > 0) {
				String countStr = selected.count() + "/" + selected.maxCount();
				String sep = " \u25c6 ";
				int totalW = this.font.width(selected.name() + sep + countStr);
				int tx = previewCx - totalW / 2;
				g.drawString(this.font, selected.name(), tx, textY, UiShapes.withAlpha(TEXT, descAlpha), true);
				tx += this.font.width(selected.name());
				g.drawString(this.font, sep, tx, textY, UiShapes.withAlpha(TEXT_DIM, descAlpha), false);
				tx += this.font.width(sep);
				g.drawString(this.font, countStr, tx, textY, UiShapes.withAlpha(TEXT, descAlpha), true);
			} else {
				g.drawCenteredString(this.font, selected.name(), previewCx, textY, UiShapes.withAlpha(TEXT, descAlpha));
			}

			int rightW = this.width + offsetX - splitX;
			int lineW = Math.round(rightW * 0.68f);
			int lineY = textY + 13;
			g.fill(previewCx - lineW / 2, lineY, previewCx + lineW / 2, lineY + 1, UiShapes.withAlpha(BORDER, descAlpha));
			g.fill(previewCx - 1, lineY - 1, previewCx + 1, lineY + 2, UiShapes.withAlpha(TEXT_DIM, descAlpha));

			int wrap = Math.max(60, Math.round(rightW * 0.78f));
			List<FormattedCharSequence> lines = this.font.split(Component.literal(selected.description()), wrap);
			int y = lineY + 7;
			for (int i = 0; i < Math.min(4, lines.size()); i++) {
				FormattedCharSequence line = lines.get(i);
				g.drawString(this.font, line, previewCx - this.font.width(line) / 2, y,
						UiShapes.withAlpha(TEXT_DIM, descAlpha), false);
				y += 10;
			}
		}
	}

	private void drawItemCell(GuiGraphics g, ItemCell c, int offsetX, int offsetY, float alpha, float pageScale) {
		float pop = 1.0f + 0.05f * c.hoverAnim + 0.10f * (float) Math.sin(Math.PI * c.equipPulse);
		int dw = Math.max(6, Math.round(c.w * pop));
		int dh = Math.max(6, Math.round(c.h * pop));
		int x = c.cx + offsetX - dw / 2;
		int y = c.cy + offsetY - dh / 2;

		int restBorder = c.item == null ? EMPTY_BORDER : BORDER;
		int restFill = c.item == null ? EMPTY_FILL : SLOT_FILL;
		int baseBorder = UiShapes.lerpColor(restBorder, GOLD, c.equipAnim);
		int border = UiShapes.withAlpha(UiShapes.lerpColor(baseBorder, WHITE, c.hoverAnim * 0.85f), alpha);
		int fill = UiShapes.withAlpha(UiShapes.lerpColor(restFill, GOLD_FILL, c.equipAnim), alpha);

		if (c.equipPulse > 0.0f) {
			int grow = Math.round((1.0f - c.equipPulse) * 8.0f);
			int haloCol = UiShapes.withAlpha(GOLD, c.equipPulse * 0.65f * alpha);
			UiShapes.drawShapeRing(g, c.shape, x - grow / 2, y - grow / 2, dw + grow, dh + grow, 2, haloCol);
		}

		UiShapes.drawShapeSlot(g, c.shape, x, y, dw, dh, 2, border, fill);

		if (c.item != null && alpha > 0.08f) {
			float k = Math.min(dw, dh) * 0.62f * pageScale / 16.0f;
			g.pose().pushPose();
			g.pose().translate(c.cx + offsetX - 8.0f * k, c.cy + offsetY - 8.0f * k, 0.0f);
			g.pose().scale(k, k, 1.0f);
			g.renderItem(c.item.stack(), 0, 0);
			g.pose().popPose();

			if (alpha > 0.02f && c.item.maxCount() > 0) {
				String count = String.valueOf(c.item.count());
				g.pose().pushPose();
				g.pose().translate(0.0f, 0.0f, 200.0f);
				g.drawString(this.font, count, x + dw - 3 - this.font.width(count), y + dh - 10,
						UiShapes.withAlpha(WHITE, alpha), true);
				g.pose().popPose();
			}
		}
	}

	// ------------------------------------------------------------------ Equipment tab

	private void drawEquipment(GuiGraphics g, int offsetX, int offsetY, float alpha, int mouseX, int mouseY) {
		int cxOval = ovalCx + offsetX;
		int cyOval = ovalCy + offsetY;
		drawOval(g, cxOval, cyOval, alpha);

		// Render the player inside the oval, always in an idle pose.
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player != null && level != null && alpha > 0.08f) {
			if (previewPlayer == null || previewPlayer.source != player || previewPlayer.level() != level) {
				previewPlayer = new IdlePreviewPlayer(level, player);
			}
			int x1 = cxOval - Math.round(ovalRx * 0.8f);
			int x2 = cxOval + Math.round(ovalRx * 0.8f);
			int y1 = cyOval - Math.round(ovalRy * 0.92f);
			int y2 = cyOval + Math.round(ovalRy * 0.48f);
			int scale = Math.max(4, Math.round(ovalRy * 0.56f * (0.75f + 0.25f * alpha)));
			float lookX = this.hideMouseCursor ? cxOval : mouseX;
			float lookY = this.hideMouseCursor ? (cyOval - ovalRy * 0.28f) : mouseY;
			InventoryScreen.renderEntityInInventoryFollowsMouse(g, x1, y1, x2, y2, scale, 0.0625f, lookX, lookY, previewPlayer);
		}

		// Name and description of the hovered item (with a slight vertical slide).
		ZeldaItem selected = cells.isEmpty() ? null : cells.get(cursor).item;
		float descEase = UiShapes.easeOutCubic(descAnim);
		float descAlpha = alpha * descEase;
		int descSlide = Math.round((1.0f - descEase) * 6.0f);
		int textY = cyOval + Math.round(ovalRy * 0.56f) + descSlide;
		if (selected != null && descAlpha > 0.02f) {
			g.drawCenteredString(this.font, selected.slot().label, cxOval, textY - 11, UiShapes.withAlpha(GOLD, descAlpha));
			g.drawCenteredString(this.font, selected.name(), cxOval, textY, UiShapes.withAlpha(TEXT, descAlpha));
			int lineW = Math.round(ovalRx * 1.2f);
			g.fill(cxOval - lineW / 2, textY + 11, cxOval + lineW / 2, textY + 12, UiShapes.withAlpha(BORDER, descAlpha));

			int wrap = Math.round(ovalRx * 1.25f);
			List<FormattedCharSequence> lines = this.font.split(Component.literal(selected.description()), wrap);
			int y = textY + 16;
			for (int i = 0; i < Math.min(3, lines.size()); i++) {
				FormattedCharSequence line = lines.get(i);
				g.drawString(this.font, line, cxOval - this.font.width(line) / 2, y, UiShapes.withAlpha(TEXT_DIM, descAlpha), false);
				y += 10;
			}
		}

		UiShapes.hex(g, ornamentX + offsetX, ornamentY + offsetY, 7, UiShapes.withAlpha(BORDER, alpha));
		UiShapes.hex(g, ornamentX + offsetX, ornamentY + offsetY, 4, UiShapes.withAlpha(BG_BOTTOM, alpha));

		float itemScale = 0.85f + 0.15f * alpha;
		for (Cell cell : cells) {
			drawCell(g, cell, offsetX, offsetY, alpha, itemScale);
		}

		if (!cells.isEmpty() && alpha > 0.02f) {
			Cell cur = cells.get(cursor);
			int gx = Math.round(cursorX) + offsetX;
			int gy = Math.round(cursorY) + offsetY;
			float pop = 1.0f + 0.06f * cur.hoverAnim + 0.12f * (float) Math.sin(Math.PI * cur.equipPulse);
			int half = Math.max(4, Math.round(cursorHalf * pop));
			int ringColor = UiShapes.withAlpha(WHITE, alpha);
			if (cur.hex) {
				drawHexRing(g, gx, gy, half + 1, 2, ringColor);
			} else {
				int side = half * 2 + 2;
				int bevel = Math.max(3, side / 6);
				drawBevelRing(g, gx - side / 2, gy - side / 2, side, bevel, 2, ringColor);
			}
		}
	}

	private void drawOval(GuiGraphics g, int cx, int cy, float alpha) {
		UiShapes.ellipse(g, cx, cy, ovalRx + 2, ovalRy + 2, UiShapes.withAlpha(BORDER, alpha));
		UiShapes.ellipse(g, cx, cy, ovalRx, ovalRy, UiShapes.withAlpha(0xFF0D0906, alpha));
		UiShapes.ellipse(g, cx, cy, Math.round(ovalRx * 0.9f), Math.round(ovalRy * 0.92f), UiShapes.withAlpha(0xFF140E09, alpha));
		UiShapes.ellipse(g, cx, cy, Math.round(ovalRx * 0.75f), Math.round(ovalRy * 0.82f), UiShapes.withAlpha(0xFF1B130C, alpha));
		UiShapes.ellipse(g, cx, cy, Math.round(ovalRx * 0.55f), Math.round(ovalRy * 0.65f), UiShapes.withAlpha(0xFF221810, alpha));
	}

	private void drawCell(GuiGraphics g, Cell c, int offsetX, int offsetY, float alpha, float pageScale) {
		int cx = c.cx + offsetX;
		int cy = c.cy + offsetY;
		float pop = 1.0f + 0.06f * c.hoverAnim + 0.12f * (float) Math.sin(Math.PI * c.equipPulse);
		int drawSize = Math.max(4, Math.round(c.size * pop));

		int baseBorder = UiShapes.lerpColor(BORDER, GOLD, c.equipAnim);
		int border = UiShapes.withAlpha(UiShapes.lerpColor(baseBorder, WHITE, c.hoverAnim * 0.85f), alpha);
		int fill = UiShapes.withAlpha(UiShapes.lerpColor(SLOT_FILL, GOLD_FILL, c.equipAnim), alpha);
		int ring = 2;

		if (c.equipPulse > 0.0f) {
			int haloGrow = Math.round((1.0f - c.equipPulse) * 8.0f);
			int haloCol = UiShapes.withAlpha(GOLD, c.equipPulse * 0.65f * alpha);
			if (c.hex) {
				UiShapes.hex(g, cx, cy, drawSize + haloGrow, haloCol);
			} else {
				int hs = drawSize + haloGrow * 2;
				UiShapes.bevel(g, cx - hs / 2, cy - hs / 2, hs, hs, Math.max(3, hs / 6), haloCol);
			}
		}

		if (c.hex) {
			UiShapes.hex(g, cx, cy, drawSize, border);
			UiShapes.hex(g, cx, cy, drawSize - ring, fill);
		} else {
			int bevel = Math.max(3, drawSize / 6);
			int x = cx - drawSize / 2;
			int y = cy - drawSize / 2;
			UiShapes.bevel(g, x, y, drawSize, drawSize, bevel, border);
			UiShapes.bevel(g, x + ring, y + ring, drawSize - 2 * ring, drawSize - 2 * ring, Math.max(1, bevel - ring), fill);
		}

		if (c.item != null && alpha > 0.08f) {
			if (c.item.slot() == EquipSlot.SHIELD) {
				int iconSize = Math.max(10, Math.round(drawSize * 0.68f * pageScale));
				int ix = cx - iconSize / 2;
				int iy = cy - iconSize / 2;
				g.setColor(1.0f, 1.0f, 1.0f, alpha);
				g.blit(ZeldaShieldRenderer.textureFor(c.item.id()), ix, iy, iconSize, iconSize, 0.0f, 0.0f, 16, 16, 64, 32);
				g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
			} else {
				float k = (c.hex ? drawSize * 1.15f : drawSize * 0.6f) * pageScale / 16.0f;
				g.pose().pushPose();
				g.pose().translate(cx - 8.0f * k, cy - 8.0f * k, 0.0f);
				g.pose().scale(k, k, 1.0f);
				g.renderItem(c.item.stack(), 0, 0);
				g.pose().popPose();
			}
		}
	}

	private static void drawHexRing(GuiGraphics g, int cx, int cy, int r, int thickness, int color) {
		if (((color >>> 24) & 0xFF) == 0 || r <= thickness) {
			return;
		}
		int rIn = r - thickness;
		double wOut = r * 0.8660254;
		double wIn = rIn * 0.8660254;
		for (int dy = -r; dy < r; dy++) {
			double t = Math.abs(dy + 0.5);
			int outH = (int) Math.round(t <= r / 2.0 ? wOut : wOut * (r - t) / (r / 2.0));
			if (t >= rIn) {
				g.fill(cx - outH, cy + dy, cx + outH, cy + dy + 1, color);
			} else {
				int inH = (int) Math.round(t <= rIn / 2.0 ? wIn : wIn * (rIn - t) / (rIn / 2.0));
				g.fill(cx - outH, cy + dy, cx - inH, cy + dy + 1, color);
				g.fill(cx + inH, cy + dy, cx + outH, cy + dy + 1, color);
			}
		}
	}

	private static void drawBevelRing(GuiGraphics g, int x, int y, int size, int bevel, int thickness, int color) {
		if (((color >>> 24) & 0xFF) == 0 || size <= thickness * 2) {
			return;
		}
		for (int row = 0; row < size; row++) {
			int outInset = row < bevel ? bevel - row : (row >= size - bevel ? row - (size - bevel) + 1 : 0);
			if (row < thickness || row >= size - thickness) {
				g.fill(x + outInset, y + row, x + size - outInset, y + row + 1, color);
			} else {
				int inRow = row - thickness;
				int inSize = size - thickness * 2;
				int inBevel = Math.max(1, bevel - thickness);
				int inInset = inRow < inBevel ? inBevel - inRow : (inRow >= inSize - inBevel ? inRow - (inSize - inBevel) + 1 : 0);
				int leftIn = x + thickness + inInset;
				int rightIn = x + size - thickness - inInset;
				g.fill(x + outInset, y + row, leftIn, y + row + 1, color);
				g.fill(rightIn, y + row, x + size - outInset, y + row + 1, color);
			}
		}
	}

	private void drawFooter(GuiGraphics g, float alpha, int slideY) {
		int y = this.height - 20 + slideY;
		g.fill(0, y - 6, this.width, this.height + slideY, UiShapes.withAlpha(BAR, alpha));
		g.fill(0, y - 6, this.width, y - 5, UiShapes.withAlpha(BORDER_DARK, alpha));
		int right = this.width - 12;
		String backKey = Gamepad.connected ? Gamepad.labelCancel() + " / Start" : "Esc";
		String equipKey = Gamepad.connected ? Gamepad.labelInteract() : "Enter";
		String moveKey = Gamepad.connected ? "D-Pad" : "Arrows";
		right = hint(g, right, y, backKey, "Back", alpha);
		right = hint(g, right, y, equipKey, "Equip", alpha);
		hint(g, right, y, moveKey, "Select", alpha);
	}

	private int hint(GuiGraphics g, int right, int y, String key, String label, float alpha) {
		int labelW = this.font.width(label);
		if (alpha > 0.02f) {
			g.drawString(this.font, label, right - labelW, y + 2, UiShapes.withAlpha(TEXT_DIM, alpha), false);
		}
		int keyW = this.font.width(key) + 8;
		UiShapes.keyCap(g, this.font, key, right - labelW - 4 - keyW, y, alpha);
		return right - labelW - 4 - keyW - 14;
	}

	// ------------------------------------------------------------------ controls

	private void move(int dx, int dy) {
		if (tab == TAB_EQUIPMENT) {
			if (cells.isEmpty()) {
				return;
			}
			Cell cur = cells.get(cursor);
			int best = -1;
			double bestScore = Double.MAX_VALUE;
			for (int i = 0; i < cells.size(); i++) {
				if (i == cursor) {
					continue;
				}
				Cell c = cells.get(i);
				double ox = c.cx - cur.cx;
				double oy = c.cy - cur.cy;
				double forward = ox * dx + oy * dy;
				if (forward <= 0) {
					continue;
				}
				double sideways = Math.abs(ox * dy - oy * dx);
				double score = forward + 2.0 * sideways;
				if (score < bestScore) {
					bestScore = score;
					best = i;
				}
			}
			if (best >= 0) {
				cursor = best;
			}
		} else if (tab == TAB_ITEMS) {
			if (itemCells.isEmpty()) {
				return;
			}
			ItemCell cur = itemCells.get(itemCursor);
			int best = -1;
			double bestScore = Double.MAX_VALUE;
			for (int i = 0; i < itemCells.size(); i++) {
				if (i == itemCursor) {
					continue;
				}
				ItemCell c = itemCells.get(i);
				double ox = c.cx - cur.cx;
				double oy = c.cy - cur.cy;
				double forward = ox * dx + oy * dy;
				if (forward <= 0) {
					continue;
				}
				double sideways = Math.abs(ox * dy - oy * dx);
				double score = forward + 2.0 * sideways;
				if (score < bestScore) {
					bestScore = score;
					best = i;
				}
			}
			if (best >= 0) {
				itemCursor = best;
			}
		}
	}

	private void activate() {
		if (tab == TAB_EQUIPMENT) {
			if (cells.isEmpty()) {
				return;
			}
			Cell cell = cells.get(cursor);
			if (cell.item != null) {
				ZeldaEquipment.toggle(cell.item);
				cell.equipPulse = 1.0f;
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			}
		} else if (tab == TAB_ITEMS) {
			if (itemCells.isEmpty()) {
				return;
			}
			ItemCell cell = itemCells.get(itemCursor);
			if (cell.item != null) {
				ZeldaQuickItems.selectItem(cell.item);
				cell.equipPulse = 1.0f;
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			}
		}
	}

	private void selectTab(int newTab, int dir) {
		if (newTab == tab) {
			return;
		}
		prevTab = tab;
		tab = newTab;
		tabDir = dir;
		tabAnim = 0.0f;
		tabKeyPulseDir = dir;
		tabKeyPulse = 1.0f;
	}

	private void changeTab(int delta) {
		int next = Math.floorMod(tab + delta, TABS.length);
		selectTab(next, delta >= 0 ? 1 : -1);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (closing) {
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE
				|| Minecraft.getInstance().options.keyInventory.matches(key, scanCode)) {
			this.onClose();
			return true;
		}
		switch (key) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> move(-1, 0);
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> move(1, 0);
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> move(0, -1);
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> move(0, 1);
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> activate();
			case GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_PAGE_UP -> changeTab(-1);
			case GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_PAGE_DOWN -> changeTab(1);
			default -> {
				return super.keyPressed(key, scanCode, modifiers);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (closing) {
			return true;
		}
		if (this.hideMouseCursor) {
			setMouseHidden(false);
		}
		if (button == 0) {
			if (mouseY < 28) {
				int index = (int) Math.floor((mouseX - tabsLeft()) / tabWidth());
				if (mouseX >= tabsLeft() && index >= 0 && index < TABS.length) {
					selectTab(index, index >= tab ? 1 : -1);
					return true;
				}
			}
			if (tab == TAB_EQUIPMENT) {
				for (int i = 0; i < cells.size(); i++) {
					if (cells.get(i).contains(mouseX, mouseY)) {
						cursor = i;
						activate();
						return true;
					}
				}
			} else if (tab == TAB_ITEMS) {
				for (int i = 0; i < itemCells.size(); i++) {
					if (itemCells.get(i).contains(mouseX, mouseY)) {
						itemCursor = i;
						activate();
						return true;
					}
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (closing) {
			return;
		}
		if (this.hideMouseCursor) {
			if (this.ignoreMouseMovesTicks > 0) {
				// Absorb Minecraft's initial releaseMouse() center warp without revealing the cursor.
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
		if (tab == TAB_EQUIPMENT) {
			for (int i = 0; i < cells.size(); i++) {
				if (cells.get(i).contains(mouseX, mouseY)) {
					cursor = i;
					return;
				}
			}
		} else if (tab == TAB_ITEMS) {
			for (int i = 0; i < itemCells.size(); i++) {
				if (itemCells.get(i).contains(mouseX, mouseY)) {
					itemCursor = i;
					return;
				}
			}
		}
	}

	@Override
	public void tick() {
		if (this.ignoreMouseMovesTicks > 0) {
			this.ignoreMouseMovesTicks--;
		}
		if (closing) {
			return;
		}
		Gamepad.poll();
		if (!Gamepad.connected) {
			prevUp = prevDown = prevLeft = prevRight = false;
			prevInteract = prevCancel = prevStart = prevLb = prevRb = false;
			return;
		}

		boolean up = Gamepad.dpadUp || Gamepad.leftY > 0.6f;
		boolean down = Gamepad.dpadDown || Gamepad.leftY < -0.6f;
		boolean left = Gamepad.dpadLeft || Gamepad.leftX < -0.6f;
		boolean right = Gamepad.dpadRight || Gamepad.leftX > 0.6f;
		boolean usedGamepad = false;

		if (tab == TAB_EQUIPMENT || tab == TAB_ITEMS) {
			if (up && !prevUp) {
				move(0, -1);
				usedGamepad = true;
			}
			if (down && !prevDown) {
				move(0, 1);
				usedGamepad = true;
			}
			if (left && !prevLeft) {
				move(-1, 0);
				usedGamepad = true;
			}
			if (right && !prevRight) {
				move(1, 0);
				usedGamepad = true;
			}
			if (Gamepad.actionInteract && !prevInteract) {
				activate();
				usedGamepad = true;
			}
		}
		if (Gamepad.leftBumper && !prevLb) {
			changeTab(-1);
			usedGamepad = true;
		}
		if (Gamepad.rightBumper && !prevRb) {
			changeTab(1);
			usedGamepad = true;
		}
		boolean closeNow = (Gamepad.actionCancel && !prevCancel) || (Gamepad.buttonStart && !prevStart);
		if (usedGamepad && !closeNow && !this.hideMouseCursor) {
			setMouseHidden(true);
		}

		prevUp = up;
		prevDown = down;
		prevLeft = left;
		prevRight = right;
		prevInteract = Gamepad.actionInteract;
		prevCancel = Gamepad.actionCancel;
		prevStart = Gamepad.buttonStart;
		prevLb = Gamepad.leftBumper;
		prevRb = Gamepad.rightBumper;

		if (closeNow) {
			this.onClose();
		}
	}
}
