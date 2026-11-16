package com.ocarinaengine.client.equipment;

/** Equipment categories shown in the Equipment menu. */
public enum EquipSlot {
	SWORD("Sword"),
	SHIELD("Shield"),
	OUTFIT("Outfit"),
	MASK("Mask"),
	BOOTS("Boots");

	public final String label;

	EquipSlot(String label) {
		this.label = label;
	}
}