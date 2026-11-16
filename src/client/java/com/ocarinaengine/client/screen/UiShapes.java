package com.ocarinaengine.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Small code-drawn shapes (no textures) and animation helpers shared across menus and the HUD. */
public final class UiShapes {
	public static final int BORDER = 0xFF6E5836;
	public static final int KEY_FILL = 0xFF2B2014;
	public static final int TEXT = 0xFFF2E8D0;

	/** Slot shapes used in the Items tab. */
	public enum SlotShape {
		SQUARE,        // square with beveled corners
		TAPER_DOWN,    // trapezoid wider at the top than at the bottom
		TRAPEZOID_UP,  // trapezoid narrower at the top than at the bottom
		HEX_FLAT,      // horizontal hexagon (flat top/bottom)
		HEX_TALL,      // vertical hexagon (pointed top/bottom)
		OCTAGON,       // octagon (4 cut corners)
		CHEVRON_RIGHT, // notched on the left, pointed on the right
		ARROW_LEFT,    // horizontal bar pointed on the left (arrows)
		CUT_TL_BR,     // square with top-left and bottom-right corners cut
		CUT_TR_BR      // rectangle with top-right and bottom-right corners cut
	}

	private UiShapes() {}

	public static float easeOutCubic(float t) {
		float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
		return 1.0f - u * u * u;
	}

	/** Frame-rate independent exponential smoothing. */
	public static float damp(float current, float target, float speed, float dt) {
		return current + (target - current) * (1.0f - (float) Math.exp(-speed * dt));
	}

	public static int withAlpha(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * Mth.clamp(alpha, 0.0f, 1.0f));
		return (a << 24) | (color & 0x00FFFFFF);
	}

	public static int lerpColor(int c1, int c2, float t) {
		float k = Mth.clamp(t, 0.0f, 1.0f);
		int a = Math.round(Mth.lerp(k, (c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF));
		int r = Math.round(Mth.lerp(k, (c1 >>> 16) & 0xFF, (c2 >>> 16) & 0xFF));
		int g = Math.round(Mth.lerp(k, (c1 >>> 8) & 0xFF, (c2 >>> 8) & 0xFF));
		int b = Math.round(Mth.lerp(k, c1 & 0xFF, c2 & 0xFF));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Hexagon (pointed top), drawn row by row. */
	public static void hex(GuiGraphics g, int cx, int cy, int r, int color) {
		if (((color >>> 24) & 0xFF) == 0 || r <= 0) {
			return;
		}
		double w = r * 0.8660254;
		for (int dy = -r; dy < r; dy++) {
			double t = Math.abs(dy + 0.5);
			double half = t <= r / 2.0 ? w : w * (r - t) / (r / 2.0);
			int h = (int) Math.round(half);
			g.fill(cx - h, cy + dy, cx + h, cy + dy + 1, color);
		}
	}

	/** Rectangle with beveled corners. */
	public static void bevel(GuiGraphics g, int x, int y, int w, int h, int b, int color) {
		if (((color >>> 24) & 0xFF) == 0 || w <= 0 || h <= 0) {
			return;
		}
		for (int row = 0; row < h; row++) {
			int inset = 0;
			if (row < b) {
				inset = b - row;
			} else if (row >= h - b) {
				inset = row - (h - b) + 1;
			}
			g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
		}
	}

	public static void ellipse(GuiGraphics g, int cx, int cy, int rx, int ry, int color) {
		if (((color >>> 24) & 0xFF) == 0 || rx <= 0 || ry <= 0) {
			return;
		}
		for (int dy = -ry; dy < ry; dy++) {
			double t = (dy + 0.5) / ry;
			int half = (int) Math.round(rx * Math.sqrt(Math.max(0.0, 1.0 - t * t)));
			g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
		}
	}

	/** Small filled triangle: points left (left = true) or right. */
	public static void arrow(GuiGraphics g, int cx, int cy, int size, boolean left, int color) {
		if (((color >>> 24) & 0xFF) == 0 || size <= 0) {
			return;
		}
		for (int dy = -size; dy <= size; dy++) {
			int a = Math.abs(dy);
			if (left) {
				g.fill(cx - size / 2 + a, cy + dy, cx + size / 2 + 1, cy + dy + 1, color);
			} else {
				g.fill(cx - size / 2, cy + dy, cx + size / 2 + 1 - a, cy + dy + 1, color);
			}
		}
	}

	/** Draws a slot cell (border + non-overlapping interior fill) matching its shape. */
	public static void drawShapeSlot(GuiGraphics g, SlotShape shape, int x, int y, int w, int h,
									 int thickness, int borderColor, int fillColor) {
		if (w <= 2 || h <= 2) {
			return;
		}
		int[] left = new int[h];
		int[] right = new int[h];
		computeSpans(shape, w, h, left, right);
		int reach = Math.max(1, thickness - 1);
		boolean hasBorder = ((borderColor >>> 24) & 0xFF) != 0;
		boolean hasFill = ((fillColor >>> 24) & 0xFF) != 0;

		for (int row = 0; row < h; row++) {
			int outL = left[row];
			int outR = right[row];
			if (outR <= outL) {
				continue;
			}
			if (row < thickness || row >= h - thickness) {
				if (hasBorder) {
					g.fill(x + outL, y + row, x + outR, y + row + 1, borderColor);
				}
				continue;
			}
			int maxL = outL;
			int minR = outR;
			for (int k = -reach; k <= reach; k++) {
				int r2 = Mth.clamp(row + k, 0, h - 1);
				if (left[r2] > maxL) {
					maxL = left[r2];
				}
				if (right[r2] < minR) {
					minR = right[r2];
				}
			}
			int inL = maxL + thickness;
			int inR = minR - thickness;
			if (inL >= inR) {
				if (hasBorder) {
					g.fill(x + outL, y + row, x + outR, y + row + 1, borderColor);
				}
			} else {
				if (hasBorder) {
					g.fill(x + outL, y + row, x + inL, y + row + 1, borderColor);
					g.fill(x + inR, y + row, x + outR, y + row + 1, borderColor);
				}
				if (hasFill) {
					g.fill(x + inL, y + row, x + inR, y + row + 1, fillColor);
				}
			}
		}
	}

	/** Draws only the outline of a shape (used for the sliding cursor or pulse halo). */
	public static void drawShapeRing(GuiGraphics g, SlotShape shape, int x, int y, int w, int h,
									 int thickness, int color) {
		if (((color >>> 24) & 0xFF) == 0 || w <= thickness * 2 || h <= thickness * 2) {
			return;
		}
		drawShapeSlot(g, shape, x, y, w, h, thickness, color, 0);
	}

	private static void computeSpans(SlotShape shape, int w, int h, int[] left, int[] right) {
		for (int row = 0; row < h; row++) {
			float t = (row + 0.5f) / h;
			int l = 0;
			int r = w;
			switch (shape) {
				case SQUARE -> {
					int b = Math.max(2, Math.min(w, h) / 10);
					int in = row < b ? b - row : (row >= h - b ? row - (h - b) + 1 : 0);
					l = in;
					r = w - in;
				}
				case TAPER_DOWN -> {
					int botIn = Math.max(2, Math.round(w * 0.13f));
					int in = Math.round(t * botIn);
					l = in;
					r = w - in;
				}
				case TRAPEZOID_UP -> {
					int topIn = Math.max(3, Math.round(w * 0.16f));
					int in = Math.round((1.0f - t) * topIn);
					l = in;
					r = w - in;
				}
				case HEX_FLAT -> {
					int p = Math.max(3, Math.round(w * 0.22f));
					float d = Math.abs(t - 0.5f) * 2.0f;
					int in = Math.round(d * p);
					l = in;
					r = w - in;
				}
				case HEX_TALL -> {
					int roof = Math.max(4, Math.round(h * 0.27f));
					int in = 0;
					if (row < roof) {
						float d = 1.0f - (row + 0.5f) / roof;
						in = Math.round(d * (w * 0.42f));
					} else if (row >= h - roof) {
						float d = 1.0f - (h - 0.5f - row) / roof;
						in = Math.round(d * (w * 0.42f));
					}
					l = in;
					r = w - in;
				}
				case OCTAGON -> {
					int c = Math.max(3, Math.round(Math.min(w, h) * 0.28f));
					int in = row < c ? c - row : (row >= h - c ? row - (h - c) + 1 : 0);
					l = in;
					r = w - in;
				}
				case CHEVRON_RIGHT -> {
					int p = Math.max(4, Math.round(w * 0.26f));
					float m = 1.0f - Math.abs(t - 0.5f) * 2.0f;
					l = Math.round(m * p);
					r = w - Math.round((1.0f - m) * p);
				}
				case ARROW_LEFT -> {
					int p = Math.max(4, Math.round(h * 0.52f));
					float d = Math.abs(t - 0.5f) * 2.0f;
					l = Math.round(d * p);
					r = w;
				}
				case CUT_TL_BR -> {
					int c = Math.max(5, Math.round(Math.min(w, h) * 0.30f));
					l = row < c ? c - row : 0;
					r = row >= h - c ? w - (row - (h - c) + 1) : w;
				}
				case CUT_TR_BR -> {
					int c = Math.max(5, Math.round(w * 0.28f));
					l = 0;
					r = row < c ? w - (c - row) : (row >= h - c ? w - (row - (h - c) + 1) : w);
				}
			}
			left[row] = Mth.clamp(l, 0, w / 2 - 1);
			right[row] = Mth.clamp(r, left[row] + 1, w);
		}
	}

	public static int keyCapWidth(Font font, String label) {
		return font.width(label) + 8;
	}

	/** A keycap badge: small beveled rectangle with a label inside. Returns its width. */
	public static int keyCap(GuiGraphics g, Font font, String label, int x, int y) {
		return keyCap(g, font, label, x, y, 1.0f);
	}

	public static int keyCap(GuiGraphics g, Font font, String label, int x, int y, float alpha) {
		int w = keyCapWidth(font, label);
		bevel(g, x, y, w, 12, 2, withAlpha(BORDER, alpha));
		bevel(g, x + 1, y + 1, w - 2, 10, 1, withAlpha(KEY_FILL, alpha));
		if (alpha > 0.02f) {
			g.drawString(font, label, x + 4, y + 2, withAlpha(TEXT, alpha), false);
		}
		return w;
	}
}