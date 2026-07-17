package com.lootdeck.tcg.activity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;

/**
 * Maps RuneLite events to the bounded ACTIVITY_IDS vocabulary. The server owns odds + tiers;
 * this only names *what happened*. Skilling is detected as gathered ITEMS gated on gathering XP.
 */
@Singleton
public class ActivityDetector
{
	private static final Set<Skill> GATHERING = EnumSet.of(
		Skill.WOODCUTTING, Skill.FISHING, Skill.MINING, Skill.HUNTER, Skill.FARMING,
		Skill.SAILING);

	// The 8 shipwreck salvage item ids (unnoted). Only salvage the PLAYER gathers rolls drops —
	// a crewmate's salvage lands in the cargo hold, and a hold withdrawal is filtered out below.
	private static final Set<Integer> SALVAGE_ITEM_IDS = Set.of(
		32847, 32849, 32851, 32853, 32855, 32857, 32859, 32861);
	// Cargo-hold container ids = gameval InventoryID.SAILING_BOAT_1..5_CARGOHOLD (963–967).
	private static final Set<Integer> CARGO_HOLD_IDS = Set.of(963, 964, 965, 966, 967);

	// The Barrows droppable event is the CHEST (a LootReceived EVENT), not the brothers. Killing a
	// brother must NOT count — otherwise a chest run would report up to six kills + the chest.
	private static final Set<String> BARROWS_BROTHERS = new java.util.HashSet<>(java.util.Arrays.asList(
		"Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
		"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled"));

	// ---- Completion-gated instanced minigames (skilling-expansion Wave 2, DESIGN §6.2) ----
	// Exactly ONE roll per COMPLETED game. A completion is detected in-instance but the report is
	// DEFERRED until the player is back in the overworld (client.isInInstancedRegion() == false), so
	// the ground pack materialises OUTSIDE the instanced map. Losing / leaving mid-game never stages
	// anything (we only stage on the completion signal), so no report is sent for a failed run.
	//
	// VERIFY IN-GAME (one QA pass per minigame): the exact completion CHAT strings below. All needles
	// must appear (lowercased) in one message. If a string never matches, that minigame simply won't
	// fire until corrected here — no wrong grants. Volcanic Mine is the highest-risk: it may need the
	// end-of-game points varbit rather than a chat line (DESIGN §6.2 implementation notes).
	private static final class MinigameSignal
	{
		final String activityId;
		final String[] needles;

		MinigameSignal(String activityId, String... needles)
		{
			this.activityId = activityId;
			this.needles = needles;
		}
	}

	private static final MinigameSignal[] MINIGAME_SIGNALS = {
		new MinigameSignal("MINIGAME_TEMPOROSS", "tempoross", "subdued"),
		new MinigameSignal("MINIGAME_VOLCANIC_MINE", "volcanic mine", "points"),
		new MinigameSignal("MINIGAME_FISHING_TRAWLER", "trawler", "caught"),
	};

	private final Client client;

	// A completed-but-not-yet-reported minigame activity id, or null. One slot is enough: these
	// minigames require leaving the instance before another can be entered.
	@javax.annotation.Nullable
	private String pendingMinigame;

	// Inventory snapshot (itemId -> qty) to diff gathered items.
	private final Map<Integer, Integer> invSnapshot = new HashMap<>();
	// False until the first inventory event after reset() has been absorbed. The first event
	// only PRIMES the snapshot — diffing against an empty snapshot would report the whole
	// inventory as gathered (audit C2).
	private boolean snapshotPrimed = false;
	// The game tick on which the last gathering-skill XP gain happened.
	private int lastGatherTick = -1;
	// Cache last-seen XP per gathering skill to detect increases.
	private final Map<Skill, Integer> lastXp = new HashMap<>();

	// Per hold container: last-seen salvage contents (itemId -> qty), to detect withdrawals.
	private final Map<Integer, Map<Integer, Integer>> holdSnapshots = new HashMap<>();
	// Salvage removed from any hold on the current tick (itemId -> qty) — cancels matching inv gains.
	private final Map<Integer, Integer> holdDecreasesThisTick = new HashMap<>();
	// Salvage inventory gains staged this tick, adjudicated on GameTick (itemId -> qty).
	private final Map<Integer, Integer> stagedSalvageGains = new HashMap<>();
	// Plain (non-salvage) gathers staged this tick, adjudicated on GameTick like salvage (M12).
	private final Map<Integer, Integer> stagedGatherGains = new HashMap<>();
	// The tick the staged maps above belong to (-1 = empty).
	private int salvageTick = -1;

	@Inject
	public ActivityDetector(Client client)
	{
		this.client = client;
	}

	public void reset()
	{
		invSnapshot.clear();
		snapshotPrimed = false;
		lastXp.clear();
		lastGatherTick = -1;
		holdSnapshots.clear();
		holdDecreasesThisTick.clear();
		stagedSalvageGains.clear();
		stagedGatherGains.clear();
		salvageTick = -1;
		pendingMinigame = null;
	}

	/** Record gathering XP ticks so inventory increases can be gated to genuine gathering. */
	public void onStatChanged(StatChanged e)
	{
		Skill skill = e.getSkill();
		if (!GATHERING.contains(skill))
		{
			return;
		}
		Integer prev = lastXp.get(skill);
		int xp = e.getXp();
		lastXp.put(skill, xp);
		// prev == null is the login/hop replay of the current XP value, not a gain (audit C2).
		if (prev != null && xp > prev)
		{
			lastGatherTick = client.getTickCount();
		}
	}

	/**
	 * Diff the inventory. Items whose quantity increased on the SAME tick as a gathering-XP gain
	 * are reported as SKILL_GATHER (one report per item id). Bank withdrawals / pickups are
	 * rejected because they grant no gathering XP. Returns the classified gathers (possibly empty).
	 */
	public List<ActivityType> onItemContainerChanged(ItemContainerChanged e)
	{
		List<ActivityType> out = new ArrayList<>();
		final int cid = e.getContainerId();

		// Cargo hold: record salvage DECREASES this tick (a withdrawal signature). Never emits.
		if (CARGO_HOLD_IDS.contains(cid))
		{
			ensureStagedTick(client.getTickCount());
			Map<Integer, Integer> prev = holdSnapshots.get(cid);
			Map<Integer, Integer> cur = salvageContentsOf(e.getItemContainer());
			if (prev != null)
			{
				for (Integer salvageId : SALVAGE_ITEM_IDS)
				{
					int dec = prev.getOrDefault(salvageId, 0) - cur.getOrDefault(salvageId, 0);
					if (dec > 0)
					{
						holdDecreasesThisTick.merge(salvageId, dec, Integer::sum);
					}
				}
			}
			holdSnapshots.put(cid, cur);
			return out;
		}

		if (cid != InventoryID.INVENTORY.getId())
		{
			return out;
		}

		ItemContainer container = e.getItemContainer();
		Map<Integer, Integer> current = new HashMap<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() >= 0)
				{
					current.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}

		if (!snapshotPrimed)
		{
			invSnapshot.clear();
			invSnapshot.putAll(current);
			snapshotPrimed = true;
			return out;
		}

		for (Map.Entry<Integer, Integer> entry : current.entrySet())
		{
			int itemId = entry.getKey();
			int qty = entry.getValue();
			int before = invSnapshot.getOrDefault(itemId, 0);
			if (qty <= before)
			{
				continue;
			}
			int gain = qty - before;
			if (SALVAGE_ITEM_IDS.contains(itemId))
			{
				// Defer: the matching hold-decrease event of a withdrawal may not have fired yet
				// this tick. Adjudicated once per tick in flushGathers() (called on GameTick).
				ensureStagedTick(client.getTickCount());
				stagedSalvageGains.merge(itemId, gain, Integer::sum);
			}
			else
			{
				// Deferred like salvage: XP and inventory events can land in either order within
				// the tick; flushGathers() adjudicates once per GameTick (M12).
				ensureStagedTick(client.getTickCount());
				stagedGatherGains.merge(itemId, gain, Integer::sum);
			}
		}

		// Refresh the snapshot regardless.
		invSnapshot.clear();
		invSnapshot.putAll(current);
		return out;
	}

	/** Salvage-only contents of a container (itemId -> qty). */
	private Map<Integer, Integer> salvageContentsOf(ItemContainer container)
	{
		Map<Integer, Integer> m = new HashMap<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (SALVAGE_ITEM_IDS.contains(item.getId()))
				{
					m.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return m;
	}

	/** New tick began without a flush (shouldn't happen — flush runs every GameTick): drop stale state. */
	private void ensureStagedTick(int tick)
	{
		if (salvageTick != tick)
		{
			stagedSalvageGains.clear();
			stagedGatherGains.clear();
			holdDecreasesThisTick.clear();
			salvageTick = tick;
		}
	}

	/**
	 * Adjudicate gathers staged during the tick that just ended (both plain skilling and Sailing
	 * salvage). A gain rolls only if a gathering-XP gain occurred within ±1 tick; salvage additionally
	 * must be NET-created (inventory gain − same-tick hold withdrawal). Returns reports; clears
	 * per-tick state. Call once per GameTick (M12).
	 */
	public List<ActivityType> flushGathers()
	{
		List<ActivityType> out = new ArrayList<>();
		// Gate on the STAGED tick vs the XP tick (both stamped during the same cycle's events),
		// not on getTickCount() now — avoids depending on when the tick counter increments.
		final boolean gathered = lastGatherTick >= 0 && Math.abs(salvageTick - lastGatherTick) <= 1;
		if (gathered)
		{
			for (Map.Entry<Integer, Integer> e : stagedSalvageGains.entrySet())
			{
				int net = e.getValue() - holdDecreasesThisTick.getOrDefault(e.getKey(), 0);
				if (net > 0)
				{
					out.add(new ActivityType("SKILL_GATHER", e.getKey(), net));
				}
			}
			for (Map.Entry<Integer, Integer> e : stagedGatherGains.entrySet())
			{
				out.add(new ActivityType("SKILL_GATHER", e.getKey(), e.getValue()));
			}
		}
		stagedSalvageGains.clear();
		stagedGatherGains.clear();
		holdDecreasesThisTick.clear();
		salvageTick = -1;
		return out;
	}

	/** Classify an NPC kill from its loot event. */
	public ActivityType onNpcKill(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		return classifyNpcKill(npc.getName(), npc.getCombatLevel(), npc.getId());
	}

	/**
	 * Pure classification (no RuneLite objects) so it is unit-testable. A named/ided boss wins via
	 * the BossRegistry (id rules first, then name); otherwise the kill buckets by combat level.
	 * Every return carries the combat level so the server can bucket an excluded boss (Phase 2).
	 */
	ActivityType classifyNpcKill(String name, int combat, int npcId)
	{
		if (name != null && BARROWS_BROTHERS.contains(name))
		{
			return null; // the chest is the droppable event, not the brothers (see onLootReceived)
		}
		String boss = BossRegistry.match(npcId, name); // KILL_<BOSS> or null (id rules first)
		if (boss != null)
		{
			return new ActivityType(boss).withCombatLevel(combat);
		}
		String id = combat >= 400 ? "KILL_MASTER_MONSTER"
			: combat >= 300 ? "KILL_ELITE_MONSTER"
			: combat >= 200 ? "KILL_HIGH_MONSTER"
			: combat >= 100 ? "KILL_MID_MONSTER"
			: "KILL_LOW_MONSTER";
		return new ActivityType(id).withCombatLevel(combat);
	}

	/**
	 * Pure: classify a general LootReceived event (a chest/reward, not an NPC kill). RuneLite fires
	 * a type=EVENT "Barrows" once per chest, which is exactly the droppable moment we want.
	 */
	public ActivityType classifyEventLoot(String type, String name)
	{
		if ("EVENT".equals(type) && "Barrows".equals(name))
		{
			return new ActivityType("KILL_BARROWS");
		}
		return null;
	}

	/**
	 * Pure: which completion-gated minigame (if any) this chat message signals. Returns the activity
	 * id or null. No RuneLite objects touched, so it is unit-testable with a plain String.
	 */
	@javax.annotation.Nullable
	String detectMinigameCompletion(String message)
	{
		if (message == null)
		{
			return null;
		}
		String m = message.toLowerCase();
		for (MinigameSignal s : MINIGAME_SIGNALS)
		{
			boolean all = true;
			for (String needle : s.needles)
			{
				if (!m.contains(needle))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				return s.activityId;
			}
		}
		return null;
	}

	/**
	 * Flush a completed minigame once the player is OUT of any instanced region (DESIGN §6.2). Called
	 * every GameTick with the live instance state; returns the activity to report (clearing the
	 * pending slot) exactly once, else null. The boolean is passed in (not read from the client here)
	 * so this stays unit-testable without a live Client.
	 */
	@javax.annotation.Nullable
	public ActivityType flushMinigame(boolean inInstancedRegion)
	{
		if (pendingMinigame == null || inInstancedRegion)
		{
			return null;
		}
		ActivityType a = new ActivityType(pendingMinigame);
		pendingMinigame = null;
		return a;
	}

	/** Classify a chat message for clue caskets and raid completions; stage minigame completions. */
	public ActivityType onChatMessage(ChatMessage e)
	{
		String msg = e.getMessage();
		if (msg == null)
		{
			return null;
		}
		// Completion-gated minigames stage a DEFERRED report (flushed on leaving the instance) rather
		// than reporting inline, so the pack spawns in the overworld. Never returns an ActivityType here.
		String minigame = detectMinigameCompletion(msg);
		if (minigame != null)
		{
			pendingMinigame = minigame;
			return null;
		}
		String m = msg.toLowerCase();
		if (m.contains("chambers of xeric") && m.contains("count"))
		{
			return new ActivityType("RAID_COX");
		}
		if (m.contains("theatre of blood") && m.contains("count"))
		{
			return new ActivityType("RAID_TOB");
		}
		if (m.contains("tombs of amascut") && m.contains("count"))
		{
			return new ActivityType("RAID_TOA");
		}
		if (m.contains("treasure trail"))
		{
			if (m.contains("beginner"))
			{
				return null; // beginner tier not in the vocabulary
			}
			if (m.contains("easy"))
			{
				return new ActivityType("CLUE_EASY");
			}
			if (m.contains("medium"))
			{
				return new ActivityType("CLUE_MEDIUM");
			}
			if (m.contains("hard"))
			{
				return new ActivityType("CLUE_HARD");
			}
			if (m.contains("elite"))
			{
				return new ActivityType("CLUE_ELITE");
			}
			if (m.contains("master"))
			{
				return new ActivityType("CLUE_MASTER");
			}
		}
		return null;
	}
}
