package com.lootdeck.tcg.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import org.junit.Test;

public class ActivityDetectorTest
{
	// classifyNpcKill never touches the client, so a null client is safe for these pure tests.
	private final ActivityDetector d = new ActivityDetector(null);

	// ---- Fakes for the tick-dependent gather/salvage tests (no Mockito on the classpath) ----

	// A one-element holder so a test can advance the game tick the fake Client reports.
	private static final class Tick
	{
		int t;
	}

	private static Object defaultFor(Class<?> rt)
	{
		if (rt == boolean.class) return Boolean.FALSE;
		if (rt == char.class) return (char) 0;
		if (rt == byte.class) return (byte) 0;
		if (rt == short.class) return (short) 0;
		if (rt == int.class) return 0;
		if (rt == long.class) return 0L;
		if (rt == float.class) return 0f;
		if (rt == double.class) return 0d;
		return null;
	}

	/** A Client whose getTickCount() reads the holder; every other method returns a type default. */
	private static Client clientAt(Tick tick)
	{
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, args) ->
				"getTickCount".equals(method.getName()) ? tick.t : defaultFor(method.getReturnType()));
	}

	/** An ItemContainer whose getItems() returns the given items; other methods return defaults. */
	private static ItemContainer container(Item... items)
	{
		return (ItemContainer) Proxy.newProxyInstance(
			ItemContainer.class.getClassLoader(),
			new Class<?>[]{ItemContainer.class},
			(proxy, method, args) ->
				"getItems".equals(method.getName()) ? items : defaultFor(method.getReturnType()));
	}

	private static ItemContainerChanged icc(int containerId, Item... items)
	{
		return new ItemContainerChanged(containerId, container(items));
	}

	private static final int INVENTORY_ID = net.runelite.api.InventoryID.INVENTORY.getId();
	private static final int HOLD_ID = 963; // SAILING_BOAT_1_CARGOHOLD

	private static String id(ActivityType a)
	{
		return a == null ? null : a.getId();
	}

	@Test
	public void combatLadder()
	{
		assertEquals("KILL_LOW_MONSTER", id(d.classifyNpcKill("Goblin", 2, -1)));
		assertEquals("KILL_LOW_MONSTER", id(d.classifyNpcKill("Guard", 99, -1)));
		assertEquals("KILL_MID_MONSTER", id(d.classifyNpcKill("Greater demon", 100, -1)));
		assertEquals("KILL_HIGH_MONSTER", id(d.classifyNpcKill("Ankou", 200, -1)));
		assertEquals("KILL_ELITE_MONSTER", id(d.classifyNpcKill("Nechryael", 300, -1)));
		assertEquals("KILL_MASTER_MONSTER", id(d.classifyNpcKill("Tormented Demon", 450, -1)));
	}

	@Test
	public void combatLevelIsStamped()
	{
		// The server needs the combat level to bucket an excluded boss.
		assertEquals(Integer.valueOf(450), d.classifyNpcKill("Tormented Demon", 450, -1).getCombatLevel());
		assertEquals(Integer.valueOf(318), d.classifyNpcKill("Cerberus", 318, 5862).getCombatLevel());
	}

	@Test
	public void namedBossesWin()
	{
		assertEquals("KILL_ZULRAH", id(d.classifyNpcKill("Zulrah", 725, -1)));
		assertEquals("KILL_VORKATH", id(d.classifyNpcKill("Vorkath", 732, -1)));
	}

	@Test
	public void bossesByName()
	{
		assertEquals("KILL_CERBERUS", id(d.classifyNpcKill("Cerberus", 318, 5862)));
		assertEquals("KILL_GENERAL_GRAARDOR", id(d.classifyNpcKill("General Graardor", 624, -1)));
		assertEquals("KILL_KRIL_TSUTSAROTH", id(d.classifyNpcKill("K'ril Tsutsaroth", 650, -1)));
		assertEquals("KILL_ROYAL_TITANS", id(d.classifyNpcKill("Branda the Fire Queen", 610, -1)));
		// Exact-name safety: an add of a boss is NOT the boss.
		assertEquals("KILL_HIGH_MONSTER", id(d.classifyNpcKill("Vet'ion Jr.", 200, -1)));
	}

	@Test
	public void idRulesSeparateSameNameBosses()
	{
		// Phosani's Nightmare shares the name "The Nightmare" with the group boss; the id decides.
		assertEquals("KILL_PHOSANIS_NIGHTMARE", id(d.classifyNpcKill("The Nightmare", 814, 11153)));
		assertEquals("KILL_NIGHTMARE", id(d.classifyNpcKill("The Nightmare", 814, 9432)));

		// Fight-Caves TzTok-Jad (3127) grants; the Inferno copy (6506) falls to the combat bucket.
		assertEquals("KILL_TZTOK_JAD", id(d.classifyNpcKill("TzTok-Jad", 900, 3127)));
		assertEquals("KILL_MASTER_MONSTER", id(d.classifyNpcKill("TzTok-Jad", 900, 6506)));
	}

	@Test
	public void barrowsChestNotBrothers()
	{
		// The six brothers never count as kills — the chest is the droppable event.
		for (String bro : new String[] {
			"Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
			"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled"
		})
		{
			assertNull(bro, id(d.classifyNpcKill(bro, 115, -1)));
		}
		// The chest EVENT does.
		assertEquals("KILL_BARROWS", id(d.classifyEventLoot("EVENT", "Barrows")));
		// Other loot events / NPC loot do not.
		assertNull(id(d.classifyEventLoot("NPC", "Cerberus")));
		assertNull(id(d.classifyEventLoot("EVENT", "Chambers of Xeric")));
	}

	// ---- Sailing salvage: player-only, withdrawal-filtered (DESIGN.md §5.1) ----

	private static void sailXp(ActivityDetector det, int xp)
	{
		det.onStatChanged(new StatChanged(Skill.SAILING, xp, 1, 1));
	}

	/**
	 * Prime the detector as a real session start does: the first XP reading is a login/hop replay
	 * (prev == null, no gain stamped) and the first inventory event only primes the snapshot (C2).
	 * After this, the NEXT XP gain + inventory increase are treated as genuine.
	 */
	private static void prime(ActivityDetector det, Skill skill, int baselineXp)
	{
		det.onStatChanged(new StatChanged(skill, baselineXp, 1, 1));
		det.onItemContainerChanged(icc(INVENTORY_ID));
	}

	@Test
	public void salvagePlayerGatherRolls()
	{
		// S2a/S2b: Sailing XP + inventory gains one salvage, no hold event → one deferred gather.
		Tick tk = new Tick();
		tk.t = 5;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		prime(det, Skill.SAILING, 50);
		sailXp(det, 100); // real gain → stamps tick 5
		List<ActivityType> sync = det.onItemContainerChanged(icc(INVENTORY_ID, new Item(32847, 1)));
		assertTrue("salvage must not emit synchronously", sync.isEmpty());
		List<ActivityType> flushed = det.flushGathers();
		assertEquals(1, flushed.size());
		assertEquals("SKILL_GATHER", flushed.get(0).getId());
		assertEquals(Integer.valueOf(32847), flushed.get(0).getItemId());
		assertEquals(1, flushed.get(0).getQuantity());
	}

	@Test
	public void salvageWithdrawalCancelled()
	{
		// Prior tick seeds the hold snapshot at {32847:1}. Next tick: passive Sailing XP, inventory
		// gains the salvage, hold now {32847:0} → inv gain nets against the hold decrease → no roll.
		Tick tk = new Tick();
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		tk.t = 10;
		det.onItemContainerChanged(icc(HOLD_ID, new Item(32847, 1))); // seed hold snapshot
		det.flushGathers(); // end of tick 10
		prime(det, Skill.SAILING, 150);

		tk.t = 11;
		sailXp(det, 200); // real gain
		det.onItemContainerChanged(icc(INVENTORY_ID, new Item(32847, 1))); // inventory gains salvage
		det.onItemContainerChanged(icc(HOLD_ID)); // hold now empty → decrease of 1
		assertTrue("withdrawal nets to zero → no roll", det.flushGathers().isEmpty());
	}

	@Test
	public void salvageCrewmateDepositDoesNotCancel()
	{
		// Prior tick seeds an empty hold. Next tick: player gathers (inv +1) AND a crewmate deposits
		// to the hold (+1) on the same tick — an INCREASE never cancels, so the gather still rolls.
		Tick tk = new Tick();
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		tk.t = 20;
		det.onItemContainerChanged(icc(HOLD_ID)); // seed empty hold snapshot
		det.flushGathers();
		prime(det, Skill.SAILING, 250);

		tk.t = 21;
		sailXp(det, 300); // real gain
		det.onItemContainerChanged(icc(INVENTORY_ID, new Item(32847, 1)));
		det.onItemContainerChanged(icc(HOLD_ID, new Item(32847, 1))); // crewmate deposit (increase)
		List<ActivityType> flushed = det.flushGathers();
		assertEquals(1, flushed.size());
		assertEquals(Integer.valueOf(32847), flushed.get(0).getItemId());
	}

	@Test
	public void salvageCrewmateOnlyNoInventoryNoRoll()
	{
		// Crewmate salvages into the hold; player's inventory never changes → nothing to roll.
		Tick tk = new Tick();
		tk.t = 30;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		sailXp(det, 400);
		det.onItemContainerChanged(icc(HOLD_ID, new Item(32847, 1)));
		assertTrue(det.flushGathers().isEmpty());
	}

	@Test
	public void salvageNoXpNoRoll()
	{
		// Inventory gains salvage with NO Sailing XP (e.g. traded/spawned) → not a gather.
		Tick tk = new Tick();
		tk.t = 40;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		det.onItemContainerChanged(icc(INVENTORY_ID)); // prime the snapshot
		det.onItemContainerChanged(icc(INVENTORY_ID, new Item(32847, 1))); // salvage gain, no XP
		assertTrue(det.flushGathers().isEmpty());
	}

	@Test
	public void nonSalvageGatherRollsViaFlush()
	{
		// Woodcutting logs (1511) are now staged like salvage and adjudicated on GameTick via
		// flushGathers() — nothing emits synchronously from onItemContainerChanged (M12).
		Tick tk = new Tick();
		tk.t = 50;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		prime(det, Skill.WOODCUTTING, 400);
		det.onStatChanged(new StatChanged(Skill.WOODCUTTING, 500, 1, 1)); // real gain → stamps tick 50
		assertTrue("gathers no longer emit synchronously",
			det.onItemContainerChanged(icc(INVENTORY_ID, new Item(1511, 1))).isEmpty());
		List<ActivityType> out = det.flushGathers();
		assertEquals(1, out.size());
		assertEquals("SKILL_GATHER", out.get(0).getId());
		assertEquals(Integer.valueOf(1511), out.get(0).getItemId());
	}

	// ---- P0 session-lifecycle: no gains from nothing (audit C1/C2) ----

	@Test
	public void firstStatObservationIsNotAGather()
	{
		Tick tk = new Tick();
		tk.t = 100;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		// Login XP replay: the first-ever observation of Fishing XP must NOT stamp a gather tick.
		det.onStatChanged(new StatChanged(Skill.FISHING, 1_000_000, 99, 99));
		det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 1))); // primes the snapshot
		// A real inventory gain follows, but no XP GAIN ever occurred (only the replay), so nothing
		// stamps lastGatherTick and flushGathers rolls nothing.
		assertTrue(det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 2))).isEmpty());
		assertTrue("first XP reading isn't a gain → no gather", det.flushGathers().isEmpty());
	}

	@Test
	public void firstContainerEventOnlyPrimes()
	{
		Tick tk = new Tick();
		tk.t = 100;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		// A genuine XP gain (second observation > first):
		det.onStatChanged(new StatChanged(Skill.FISHING, 50, 1, 1));
		det.onStatChanged(new StatChanged(Skill.FISHING, 60, 1, 1));
		// FIRST inventory event carries a full fish stack — must only prime, not report.
		assertTrue(det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 27))).isEmpty());
		// SECOND event stages one more fish; flushGathers adjudicates → exactly one gather of qty 1.
		assertTrue(det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 28))).isEmpty());
		List<ActivityType> out = det.flushGathers();
		assertEquals(1, out.size());
		assertEquals(1, out.get(0).getQuantity());
	}

	@Test
	public void hopThenFishReportsOneFish()
	{
		// The scenario-B faucet: hop (reset) mid-session, then fish → exactly one gather, not a stack.
		Tick tk = new Tick();
		tk.t = 100;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		prime(det, Skill.FISHING, 50);
		det.onStatChanged(new StatChanged(Skill.FISHING, 60, 1, 1));
		det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 28))); // stages (not asserted)
		det.flushGathers(); // drain the staged state
		// Hop: session ends → reset() clears snapshot + XP baseline.
		det.reset();
		det.onStatChanged(new StatChanged(Skill.FISHING, 60, 1, 1)); // replay: prev == null, no gain
		det.onStatChanged(new StatChanged(Skill.FISHING, 70, 1, 1)); // real gain → stamps tick 100
		// First inventory event after the hop (28 fish) only primes.
		assertTrue(det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 28))).isEmpty());
		// Next event (29) stages one fish; flushGathers reports qty 1.
		assertTrue(det.onItemContainerChanged(icc(INVENTORY_ID, new Item(383, 29))).isEmpty());
		List<ActivityType> out = det.flushGathers();
		assertEquals(1, out.size());
		assertEquals(1, out.get(0).getQuantity());
	}

	@Test
	public void minigameSurvivesWithoutReset()
	{
		Tick tk = new Tick();
		tk.t = 100;
		ActivityDetector det = new ActivityDetector(clientAt(tk));
		det.onChatMessage(chat("The storm has subsided and Tempoross has been subdued."));
		// In-instance: not flushed yet.
		assertNull(det.flushMinigame(true));
		// Out of the instance (no reset happened in between): flushes exactly once.
		assertEquals("MINIGAME_TEMPOROSS", det.flushMinigame(false).getId());
		assertNull(det.flushMinigame(false));
	}

	// ---- Completion-gated minigames (Wave 2, DESIGN §6.2) ----

	private static ChatMessage chat(String message)
	{
		ChatMessage e = new ChatMessage();
		e.setMessage(message);
		return e;
	}

	@Test
	public void minigameCompletionSignalsClassify()
	{
		// detectMinigameCompletion is pure — no client needed.
		assertEquals("MINIGAME_TEMPOROSS", d.detectMinigameCompletion("The storm has subsided and Tempoross has been subdued."));
		assertEquals("MINIGAME_VOLCANIC_MINE", d.detectMinigameCompletion("Volcanic Mine: you earned 320 points."));
		assertEquals("MINIGAME_FISHING_TRAWLER", d.detectMinigameCompletion("You've caught some fish from the trawler net."));
		assertNull("unrelated chat stages nothing", d.detectMinigameCompletion("Oh dear, you are dead!"));
		assertNull(d.detectMinigameCompletion(null));
	}

	@Test
	public void minigameReportIsDeferredUntilOutOfInstance()
	{
		ActivityDetector det = new ActivityDetector(null);
		// Completion detected while still inside the instanced arena → staged, not reported inline.
		assertNull("completion never reports inline", det.onChatMessage(chat("Tempoross has been subdued.")));
		// Still inside the instance: nothing flushes.
		assertNull(det.flushMinigame(true));
		// Back in the overworld: flush exactly once.
		ActivityType a = det.flushMinigame(false);
		assertEquals("MINIGAME_TEMPOROSS", a.getId());
		assertNull("itemId absent for an activity report", a.getItemId());
		// One completion = one report; the slot is now empty.
		assertNull(det.flushMinigame(false));
	}

	@Test
	public void minigameLossOrEarlyLeaveNeverStages()
	{
		ActivityDetector det = new ActivityDetector(null);
		// No completion signal was ever seen → leaving the instance flushes nothing.
		assertNull(det.flushMinigame(false));
		// A non-completion message doesn't stage.
		det.onChatMessage(chat("The trawler's net is 50% full."));
		assertNull(det.flushMinigame(false));
	}

	@Test
	public void minigameCompletionDoesNotDoubleWithRaidClueDetection()
	{
		// A staged minigame returns null from onChatMessage AND does not fall through to raid/clue logic.
		ActivityDetector det = new ActivityDetector(null);
		assertNull(det.onChatMessage(chat("Volcanic Mine: 500 points awarded.")));
		// A real clue casket still reports inline (unchanged behaviour).
		ActivityType clue = det.onChatMessage(chat("You have completed a hard Treasure Trail."));
		assertEquals("CLUE_HARD", clue.getId());
	}
}
