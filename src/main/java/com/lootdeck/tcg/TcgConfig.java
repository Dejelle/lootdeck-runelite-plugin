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
		description = "Set automatically after linking. Do not share.",
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
		description = "Set automatically after linking. Do not share.",
		secret = true,
		position = 1
	)
	void setToken(String token);

	@ConfigItem(
		keyName = "apiBase",
		name = "API base URL",
		description = "Advanced: override the LootDeck server URL.",
		position = 2
	)
	default String apiBase()
	{
		// Production LootDeck API (overridable for local dev).
		return "https://api-production-decd.up.railway.app";
	}

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
		keyName = "enableInClientOpen",
		name = "Open packs in-client",
		description = "Open packs with an animated reveal inside RuneLite (off = open on the website).",
		position = 5
	)
	default boolean enableInClientOpen()
	{
		return true;
	}
}
