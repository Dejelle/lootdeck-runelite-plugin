package com.lootdeck.tcg.activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a killed NPC (by id, then by name) to a KILL_&lt;BOSS&gt; activity id.
 *
 * <p>Two rule kinds, checked in order (see plan/activity-taxonomy Appendix A):
 * <ol>
 *   <li><b>ID rules</b> — for the only genuine same-name collisions. Phosani's Nightmare shares
 *       the name "The Nightmare" with the group version, and the Fight-Caves TzTok-Jad shares its
 *       name with the Inferno / Ket-Rak copies. Ids pinned from {@code net.runelite.api.NpcID}
 *       (runelite-api 1.12.31.1), the same id space {@code NPC.getId()} returns.</li>
 *   <li><b>Name rules</b> — everything else, matched by exact name ({@code EQUALS}) unless the
 *       boss's own display name varies ({@code CONTAINS}, e.g. Zulrah/Vorkath phases). Adds carry
 *       suffixes ("Vet'ion Jr.", "Hueycoatl tail"), so EQUALS excludes them.</li>
 * </ol>
 *
 * <p>Odds, exclusions and caps are 100% server-side; this class only names <i>which boss</i>.
 * A miss returns {@code null} and the caller falls back to the combat-level monster bucket.
 */
final class BossRegistry
{
	private enum Match { EQUALS, CONTAINS }

	private static final class NameRule
	{
		final String activity;
		final Match match;
		final String needle; // lower-cased

		NameRule(String activity, Match match, String needle)
		{
			this.activity = activity;
			this.match = match;
			this.needle = needle.toLowerCase();
		}
	}

	// ---- ID rules (checked first) ----
	private static final Map<Integer, String> ID_RULES = new HashMap<>();

	static
	{
		// Fight-Caves TzTok-Jad only (id 3127). The Inferno / Ket-Rak "TzTok-Jad" copies
		// (6506, 13661, 15574, 15699) share the name and must NOT grant this activity.
		ID_RULES.put(3127, "KILL_TZTOK_JAD");

		// Phosani's Nightmare — same in-game name ("The Nightmare") as the group boss; only the
		// id separates them. All Phosani npc ids from net.runelite.api.NpcID.
		for (int id : new int[] {
			377, 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153, 11154, 11155
		})
		{
			ID_RULES.put(id, "KILL_PHOSANIS_NIGHTMARE");
		}
	}

	// ---- Name rules (checked after id rules) ----
	private static final List<NameRule> NAME_RULES = new ArrayList<>();

	private static void eq(String activity, String name)
	{
		NAME_RULES.add(new NameRule(activity, Match.EQUALS, name));
	}

	private static void contains(String activity, String needle)
	{
		NAME_RULES.add(new NameRule(activity, Match.CONTAINS, needle));
	}

	static
	{
		contains("KILL_ZULRAH", "zulrah");
		contains("KILL_VORKATH", "vorkath");
		eq("KILL_GIANT_MOLE", "Giant Mole");
		eq("KILL_DERANGED_ARCHAEOLOGIST", "Deranged Archaeologist");
		eq("KILL_SARACHNIS", "Sarachnis");
		eq("KILL_SCURRIUS", "Scurrius");
		eq("KILL_KALPHITE_QUEEN", "Kalphite Queen");
		eq("KILL_KING_BLACK_DRAGON", "King Black Dragon");
		eq("KILL_DAGANNOTH_REX", "Dagannoth Rex");
		eq("KILL_DAGANNOTH_PRIME", "Dagannoth Prime");
		eq("KILL_DAGANNOTH_SUPREME", "Dagannoth Supreme");
		eq("KILL_GENERAL_GRAARDOR", "General Graardor");
		eq("KILL_KREEARRA", "Kree'arra");
		eq("KILL_COMMANDER_ZILYANA", "Commander Zilyana");
		eq("KILL_KRIL_TSUTSAROTH", "K'ril Tsutsaroth");
		eq("KILL_NEX", "Nex");
		eq("KILL_CORPOREAL_BEAST", "Corporeal Beast");
		eq("KILL_HUEYCOATL", "The Hueycoatl"); // excludes "Hueycoatl tail"/"Hueycoatl body"
		eq("KILL_ABYSSAL_SIRE", "Abyssal Sire");
		eq("KILL_KRAKEN", "Kraken");
		eq("KILL_CERBERUS", "Cerberus");
		eq("KILL_THERMONUCLEAR_SMOKE_DEVIL", "Thermonuclear Smoke Devil");
		eq("KILL_GROTESQUE_GUARDIANS", "Dusk"); // loot comes from Dusk (npc 7849)
		eq("KILL_ALCHEMICAL_HYDRA", "Alchemical Hydra");
		eq("KILL_ARAXXOR", "Araxxor");
		eq("KILL_CALLISTO", "Callisto");
		eq("KILL_ARTIO", "Artio");
		eq("KILL_VETION", "Vet'ion"); // excludes "Vet'ion Jr." adds
		eq("KILL_CALVARION", "Calvar'ion");
		eq("KILL_VENENATIS", "Venenatis");
		eq("KILL_SPINDEL", "Spindel");
		eq("KILL_SCORPIA", "Scorpia");
		eq("KILL_CHAOS_ELEMENTAL", "Chaos Elemental");
		eq("KILL_CHAOS_FANATIC", "Chaos Fanatic");
		eq("KILL_CRAZY_ARCHAEOLOGIST", "Crazy Archaeologist");
		eq("KILL_REVENANT_MALEDICTUS", "Revenant Maledictus");
		eq("KILL_PHANTOM_MUSPAH", "Phantom Muspah");
		eq("KILL_NIGHTMARE", "The Nightmare"); // group; Phosani's is split off by the id rule above
		eq("KILL_DUKE_SUCELLUS", "Duke Sucellus");
		eq("KILL_LEVIATHAN", "The Leviathan");
		eq("KILL_WHISPERER", "The Whisperer");
		eq("KILL_VARDORVIS", "Vardorvis");
		eq("KILL_OBOR", "Obor");
		eq("KILL_BRYOPHYTA", "Bryophyta");
		eq("KILL_AMOXLIATL", "Amoxliatl");
		eq("KILL_ROYAL_TITANS", "Branda the Fire Queen"); // Branda holds the pair's loot
		eq("KILL_GEMSTONE_CRAB", "Gemstone Crab"); // excludes "Gemstone crab shell"
		eq("KILL_DOOM_OF_MOKHAIOTL", "Doom of Mokhaiotl");
		eq("KILL_YAMA", "Yama"); // excludes "Disciple/Judge/Voice of Yama"
		eq("KILL_TZKAL_ZUK", "TzKal-Zuk");
		eq("KILL_CRYSTALLINE_HUNLLEF", "Crystalline Hunllef");
		eq("KILL_CORRUPTED_HUNLLEF", "Corrupted Hunllef");
	}

	private BossRegistry()
	{
	}

	/** Return the KILL_&lt;BOSS&gt; activity for this npc, or null. Id rules win over name rules. */
	static String match(int npcId, String name)
	{
		String byId = ID_RULES.get(npcId);
		if (byId != null)
		{
			return byId;
		}
		if (name == null)
		{
			return null;
		}
		String lower = name.toLowerCase();
		for (NameRule r : NAME_RULES)
		{
			boolean hit = r.match == Match.EQUALS ? lower.equals(r.needle) : lower.contains(r.needle);
			if (hit)
			{
				return r.activity;
			}
		}
		return null;
	}
}
