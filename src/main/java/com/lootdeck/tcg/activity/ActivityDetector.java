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
		Skill.WOODCUTTING, Skill.FISHING, Skill.MINING, Skill.HUNTER, Skill.FARMING);

	private final Client client;

	// Inventory snapshot (itemId -> qty) to diff gathered items.
	private final Map<Integer, Integer> invSnapshot = new HashMap<>();
	// The game tick on which the last gathering-skill XP gain happened.
	private int lastGatherTick = -1;
	// Cache last-seen XP per gathering skill to detect increases.
	private final Map<Skill, Integer> lastXp = new HashMap<>();

	@Inject
	public ActivityDetector(Client client)
	{
		this.client = client;
	}

	public void reset()
	{
		invSnapshot.clear();
		lastXp.clear();
		lastGatherTick = -1;
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
		if (prev == null || xp > prev)
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
		if (e.getContainerId() != InventoryID.INVENTORY.getId())
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

		// Tolerate event ordering: the gathering XP tick and the inventory change may be
		// reported on the same tick or one tick apart, in either order.
		final boolean gatheredThisTick = lastGatherTick >= 0 && client.getTickCount() - lastGatherTick <= 1;
		if (gatheredThisTick)
		{
			for (Map.Entry<Integer, Integer> entry : current.entrySet())
			{
				int itemId = entry.getKey();
				int qty = entry.getValue();
				int before = invSnapshot.getOrDefault(itemId, 0);
				if (qty > before)
				{
					out.add(new ActivityType("SKILL_GATHER", itemId, qty - before));
				}
			}
		}

		// Refresh the snapshot regardless.
		invSnapshot.clear();
		invSnapshot.putAll(current);
		return out;
	}

	/** Classify an NPC kill from its loot event. */
	public ActivityType onNpcKill(NPC npc)
	{
		if (npc == null)
		{
			return null;
		}
		String name = npc.getName();
		if (name != null)
		{
			String n = name.toLowerCase();
			if (n.contains("zulrah"))
			{
				return new ActivityType("KILL_ZULRAH");
			}
			if (n.contains("vorkath"))
			{
				return new ActivityType("KILL_VORKATH");
			}
		}
		int combat = npc.getCombatLevel();
		return new ActivityType(combat >= 100 ? "KILL_MID_BOSS" : "KILL_LOW_MONSTER");
	}

	/** Classify a chat message for clue caskets and raid completions. */
	public ActivityType onChatMessage(ChatMessage e)
	{
		String msg = e.getMessage();
		if (msg == null)
		{
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
