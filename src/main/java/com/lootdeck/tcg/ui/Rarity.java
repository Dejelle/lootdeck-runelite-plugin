package com.lootdeck.tcg.ui;

import java.awt.Color;

/** Rarity → color, mirroring the web RARITY_COLOR (card-utils.ts) exactly. */
public final class Rarity
{
	private Rarity()
	{
	}

	public static Color color(String rarity)
	{
		if (rarity == null)
		{
			return new Color(0xc1, 0xc7, 0xce);
		}
		switch (rarity)
		{
			case "uncommon":
				return new Color(0x7f, 0xce, 0x5f);
			case "rare":
				return new Color(0x4a, 0xa6, 0xea);
			case "epic":
				return new Color(0xb0, 0x6f, 0xf0);
			case "legendary":
				return new Color(0xf0, 0x90, 0x2f);
			case "common":
			default:
				return new Color(0xc1, 0xc7, 0xce);
		}
	}
}
