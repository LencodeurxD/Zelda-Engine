package com.ocarinaengine.client.rules;

/**
 * List of vanilla Minecraft mechanics disabled by the Zelda engine.
 * true = disabled. When implementing the Zelda version of a mechanic
 * (sword, items, action button...), set the corresponding flag to false.
 */
public final class ZeldaRules {
	/** Breaking blocks. */
	public static boolean disableMining = true;
	/** Placing and using blocks (opening chests, doors...). */
	public static boolean disablePlacingAndUsingBlocks = true;
	/** Using the held item (eating, throwing, shooting a bow...). */
	public static boolean disableUsingItems = true;
	/** Attacking and interacting with entities. */
	public static boolean disableAttackAndEntityInteraction = true;
	/** Inventory, crafting, dropping items, swapping hands, hotbar, advancements. */
	public static boolean disableInventoryAndHotbar = true;

	/** The Zelda equipment menu replaces the inventory (E key, or Start on gamepad). */
	public static boolean zeldaMenu = true;

	/** Quick items menu: R opens the menu (pause), ZR uses the selected item (only when items are owned). */
	public static boolean quickItems = true;

	/** Hunger: bar hidden and food level locked (singleplayer), completely inactive. */
	public static boolean disableHunger = true;
	/** No natural health regeneration (hunger is locked just below the healing threshold). */
	public static boolean disableNaturalRegeneration = true;
	/** Health points per heart: 4 allows quarter-heart precision (1 HP per quarter). */
	public static final int HP_PER_HEART = 4;
	/** Caps the player's max health to "maxHearts" hearts (singleplayer). */
	public static boolean limitMaxHearts = true;
	public static int maxHearts = 3;
	/** Replaces Minecraft status bars with Zelda hearts in the top-left corner. */
	public static boolean zeldaHearts = true;
	/** Hides the vanilla hotbar and held item name. */
	public static boolean hideHotbar = true;
	/** Hides the experience bar and level indicator. */
	public static boolean hideExperienceBar = true;

	private ZeldaRules() {}
}
