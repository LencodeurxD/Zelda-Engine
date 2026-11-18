package com.ocarinaengine.client.input;

import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * Reads the first connected gamepad (Xbox, Switch Pro, PlayStation...) via GLFW.
 *
 * Face buttons follow Nintendo Switch physical layout across all controllers:
 * - Top    (Switch X / Xbox Y): Jump
 * - Left   (Switch Y / Xbox X): Attack
 * - Bottom (Switch B / Xbox A): Cancel / Sheathe / Back
 * - Right  (Switch A / Xbox B): Interact / Open Chests / Confirm
 *
 * GLFW's SDL gamepad mapping already normalizes physical positions to Xbox coordinates
 * (Y=Top, X=Left, A=Bottom, B=Right), so Xbox X/Y and A/B are automatically swapped
 * relative to Switch labels while keeping identical physical button positions.
 */
public final class Gamepad {
	private static final float DEADZONE = 0.20f;
	private static final GLFWGamepadState STATE = GLFWGamepadState.create();

	private static int jid = -1;
	private static long lastScan = 0L;

	public static boolean connected = false;
	public static boolean isNintendoLayout = false;
	public static float leftX, leftY, rightX, rightY;

	/** Semantic face-button actions (Switch physical layout). */
	/** Top face button (Switch X / Xbox Y): Jump & Dodge. */
	public static boolean actionJump = false;
	/** Left face button (Switch Y / Xbox X): Attack. */
	public static boolean actionAttack = false;
	/** Bottom face button (Switch B / Xbox A): Cancel, Sheathe sword/shield, Put away item, Menu Back. */
	public static boolean actionCancel = false;
	/** Right face button (Switch A / Xbox B): Interact, Open chests, Menu Confirm. */
	public static boolean actionInteract = false;

	/** Start / Plus / Options button: opens and closes the equipment menu. */
	public static boolean buttonStart = false;
	/** Shoulder buttons: LB / RB (L / R). */
	public static boolean leftBumper = false;
	public static boolean rightBumper = false;
	/** Triggers: LT / RT (ZL / ZR). */
	public static boolean leftTrigger = false;
	public static boolean rightTrigger = false;
	/** Directional pad. */
	public static boolean dpadUp = false;
	public static boolean dpadDown = false;
	public static boolean dpadLeft = false;
	public static boolean dpadRight = false;

	private Gamepad() {}

	/** Refreshes the gamepad state. Safe to call multiple times per frame. */
	public static void poll() {
		long now = System.nanoTime();
		if (!findPad(now) || !GLFW.glfwGetGamepadState(jid, STATE)) {
			reset();
			return;
		}
		connected = true;
		isNintendoLayout = detectNintendo(jid);

		float lx = STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
		float ly = -STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
		float rx = STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X);
		float ry = -STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y);

		float[] l = applyDeadzone(lx, ly);
		float[] r = applyDeadzone(rx, ry);
		leftX = l[0];
		leftY = l[1];
		rightX = r[0];
		rightY = r[1];

		// Physical positions in GLFW: Y=Top, X=Left, A=Bottom, B=Right.
		actionJump = pressed(GLFW.GLFW_GAMEPAD_BUTTON_Y);
		actionAttack = pressed(GLFW.GLFW_GAMEPAD_BUTTON_X);
		actionCancel = pressed(GLFW.GLFW_GAMEPAD_BUTTON_A);
		actionInteract = pressed(GLFW.GLFW_GAMEPAD_BUTTON_B);

		buttonStart = pressed(GLFW.GLFW_GAMEPAD_BUTTON_START);
		leftBumper = pressed(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);
		rightBumper = pressed(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER);
		// Triggers range from -1 (released) to 1 (fully pressed).
		leftTrigger = STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0.5f;
		rightTrigger = STATE.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.5f;
		dpadUp = pressed(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP);
		dpadDown = pressed(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
		dpadLeft = pressed(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
		dpadRight = pressed(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT);
	}

	public static String labelJump() {
		return isNintendoLayout ? "X" : "Y";
	}

	public static String labelAttack() {
		return isNintendoLayout ? "Y" : "X";
	}

	public static String labelCancel() {
		return isNintendoLayout ? "B" : "A";
	}

	public static String labelInteract() {
		return isNintendoLayout ? "A" : "B";
	}

	public static String labelShield() {
		return isNintendoLayout ? "L" : "LB";
	}

	private static boolean detectNintendo(int joystickId) {
		String name = GLFW.glfwGetGamepadName(joystickId);
		if (name == null || name.isEmpty()) {
			name = GLFW.glfwGetJoystickName(joystickId);
		}
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.contains("nintendo")
				|| lower.contains("switch")
				|| lower.contains("joy-con")
				|| lower.contains("pro controller");
	}

	private static boolean pressed(int button) {
		return (STATE.buttons(button) & 0xFF) != 0;
	}

	public static boolean leftStickActive() {
		return connected && (leftX != 0f || leftY != 0f);
	}

	public static boolean rightStickActive() {
		return connected && (rightX != 0f || rightY != 0f);
	}

	private static boolean findPad(long now) {
		if (jid >= 0 && GLFW.glfwJoystickIsGamepad(jid)) {
			return true;
		}
		jid = -1;
		// Scan at most once per second to avoid overhead.
		if (now - lastScan < 1_000_000_000L) {
			return false;
		}
		lastScan = now;
		for (int i = GLFW.GLFW_JOYSTICK_1; i <= GLFW.GLFW_JOYSTICK_LAST; i++) {
			if (GLFW.glfwJoystickIsGamepad(i)) {
				jid = i;
				return true;
			}
		}
		return false;
	}

	private static float[] applyDeadzone(float x, float y) {
		float mag = (float) Math.sqrt(x * x + y * y);
		if (mag < DEADZONE) {
			return new float[] {0f, 0f};
		}
		float scaled = Math.min(1f, (mag - DEADZONE) / (1f - DEADZONE));
		float k = scaled / mag;
		return new float[] {x * k, y * k};
	}

	private static void reset() {
		connected = false;
		isNintendoLayout = false;
		leftX = leftY = rightX = rightY = 0f;
		actionJump = false;
		actionAttack = false;
		actionCancel = false;
		actionInteract = false;
		buttonStart = false;
		leftBumper = false;
		rightBumper = false;
		leftTrigger = false;
		rightTrigger = false;
		dpadUp = false;
		dpadDown = false;
		dpadLeft = false;
		dpadRight = false;
	}
}
