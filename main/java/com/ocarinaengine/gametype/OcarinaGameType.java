package com.ocarinaengine.gametype;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * Playstyle mode for Ocarina Engine worlds and players:
 * - ZELDA: Full Ocarina Engine gameplay (camera, movement, ledge grab, Z-Targeting, sword combat, hearts, menus).
 * - MINECRAFT: Pure vanilla Minecraft gameplay with all Zelda engine overrides disabled.
 */
public enum OcarinaGameType {
	ZELDA("zelda", "ocarina_engine.gametype.zelda", "Zelda"),
	MINECRAFT("minecraft", "ocarina_engine.gametype.minecraft", "Minecraft");

	private final String id;
	private final String translationKey;
	private final String label;

	OcarinaGameType(String id, String translationKey, String label) {
		this.id = id;
		this.translationKey = translationKey;
		this.label = label;
	}

	public String id() {
		return this.id;
	}

	public String label() {
		return this.label;
	}

	public boolean isZelda() {
		return this == ZELDA;
	}

	public boolean isMinecraft() {
		return this == MINECRAFT;
	}

	public Component displayComponent() {
		return Component.translatable(this.translationKey);
	}

	public static OcarinaGameType byOrdinal(int ordinal) {
		OcarinaGameType[] values = values();
		if (ordinal >= 0 && ordinal < values.length) {
			return values[ordinal];
		}
		return ZELDA;
	}

	public static OcarinaGameType parse(String raw) {
		if (raw == null) {
			return null;
		}
		String norm = raw.trim().toLowerCase(Locale.ROOT);
		if (norm.equals("zelda")) {
			return ZELDA;
		}
		if (norm.equals("minecraft")) {
			return MINECRAFT;
		}
		return null;
	}
}
