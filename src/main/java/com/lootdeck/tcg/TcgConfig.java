package com.lootdeck.tcg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lootdeck")
public interface TcgConfig extends Config
{
	@ConfigItem(
		keyName = "token",
		name = "Account token",
		description = "Set automatically after linking. Do not share. While linked, the plugin sends "
			+ "your account hash, display name and qualifying gameplay events to the LootDeck service.",
		secret = true,
		position = 1
	)
	default String token()
	{
		return "";
	}

	// The setter MUST carry the same @ConfigItem keyName, or RuneLite can't persist the value.
	@ConfigItem(
		keyName = "token",
		name = "Account token",
		description = "Set automatically after linking. Do not share. While linked, the plugin sends "
			+ "your account hash, display name and qualifying gameplay events to the LootDeck service.",
		secret = true,
		position = 1
	)
	void setToken(String token);

	@ConfigItem(
		keyName = "enableAnimation",
		name = "Drop animation",
		description = "Show the drop animation overlay.",
		position = 3
	)
	default boolean enableAnimation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableWorldObject",
		name = "Show dropped pack in world",
		description = "Spawn a cosmetic, client-side pack object on the drop tile you can pick up.",
		position = 4
	)
	default boolean enableWorldObject()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightGroundPacks",
		name = "Highlight dropped packs",
		description = "Draw a coloured tile highlight and floating label on a dropped booster pack so it stands out among other loot.",
		position = 5
	)
	default boolean highlightGroundPacks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "leftClickTakePack",
		name = "Left-click take packs",
		description = "Make 'Take Booster Pack' the left-click action on the pack's tile (off = right-click menu only).",
		position = 6
	)
	default boolean leftClickTakePack()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableInClientOpen",
		name = "Open packs in-client",
		description = "Open packs with an animated reveal inside RuneLite (off = open on the website).",
		position = 7
	)
	default boolean enableInClientOpen()
	{
		return true;
	}

	@ConfigItem(
		keyName = "blockPackActionsInCombat",
		name = "Block pack actions in combat",
		description = "Prevent taking ground packs and opening packs while you are in combat, so a misclick during a fight can't interrupt you.",
		position = 8
	)
	default boolean blockPackActionsInCombat()
	{
		return true;
	}
}
