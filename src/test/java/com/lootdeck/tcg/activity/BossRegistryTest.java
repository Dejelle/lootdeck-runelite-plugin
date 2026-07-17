package com.lootdeck.tcg.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** BossRegistry is package-private; tested here in the same package via match(). */
public class BossRegistryTest
{
	@Test
	public void idRulesWinAndSeparateSameNameBosses()
	{
		// Fight-Caves Jad only.
		assertEquals("KILL_TZTOK_JAD", BossRegistry.match(3127, "TzTok-Jad"));
		// Inferno / Ket-Rak "TzTok-Jad" copies are not the Fight-Caves reward.
		assertNull(BossRegistry.match(6506, "TzTok-Jad"));
		assertNull(BossRegistry.match(13661, "TzTok-Jad"));

		// Every pinned Phosani id resolves to Phosani's, regardless of the shared name.
		for (int phosani : new int[] {
			377, 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153, 11154, 11155
		})
		{
			assertEquals("KILL_PHOSANIS_NIGHTMARE", BossRegistry.match(phosani, "The Nightmare"));
		}
		// A group-Nightmare id is not in the Phosani set → name rule → group activity.
		assertEquals("KILL_NIGHTMARE", BossRegistry.match(9432, "The Nightmare"));
	}

	@Test
	public void nameRules()
	{
		assertEquals("KILL_ZULRAH", BossRegistry.match(-1, "Zulrah"));
		assertEquals("KILL_VORKATH", BossRegistry.match(-1, "Vorkath (Awakened)"));
		assertEquals("KILL_CERBERUS", BossRegistry.match(-1, "Cerberus"));
		assertEquals("KILL_KREEARRA", BossRegistry.match(-1, "Kree'arra"));
		assertEquals("KILL_GROTESQUE_GUARDIANS", BossRegistry.match(-1, "Dusk"));
		assertEquals("KILL_HUEYCOATL", BossRegistry.match(-1, "The Hueycoatl"));
	}

	@Test
	public void exactNameExcludesAddsAndNonBosses()
	{
		assertNull(BossRegistry.match(-1, "Vet'ion Jr."));
		assertNull(BossRegistry.match(-1, "Hueycoatl tail"));
		assertNull(BossRegistry.match(-1, "Disciple of Yama"));
		assertNull(BossRegistry.match(-1, "Goblin"));
		assertNull(BossRegistry.match(-1, null));
	}

	@Test
	public void everyMatchIsAKillActivity()
	{
		String[] names = {
			"Zulrah", "Cerberus", "Dusk", "The Nightmare", "Branda the Fire Queen", "Yama",
		};
		for (String n : names)
		{
			String a = BossRegistry.match(-1, n);
			assertTrue(n + " → " + a, a != null && a.startsWith("KILL_"));
		}
	}
}
