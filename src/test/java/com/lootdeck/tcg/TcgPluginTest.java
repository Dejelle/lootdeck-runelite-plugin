package com.lootdeck.tcg;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev launcher — runs RuneLite with the LootDeck plugin loaded so you can test it locally
 * before submitting to the Plugin Hub. Run this class's main() (from your IDE, or `./gradlew`
 * with the test classpath). Pass "--developer-mode" as a program argument if you want the
 * developer tools enabled.
 */
public class TcgPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TcgPlugin.class);
		RuneLite.main(args);
	}
}
