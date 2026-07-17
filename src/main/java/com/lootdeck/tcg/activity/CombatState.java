package com.lootdeck.tcg.activity;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;

/**
 * Tracks whether the local player is in combat, using the standard
 * hitsplat/interaction decay window (~10 game ticks = 6 seconds).
 */
@Singleton
public class CombatState
{
	private static final int COMBAT_TICKS = 10;

	private final Client client;
	private int lastCombatTick = -COMBAT_TICKS - 1;
	// The tick of the last relevant hitsplat. onInteractingChanged only counts as combat within
	// 25 ticks of one — interaction alone (following / talking) is not combat (audit L5).
	private int lastHitsplatTick = -1000;

	@Inject
	public CombatState(Client client)
	{
		this.client = client;
	}

	public void onHitsplatApplied(HitsplatApplied e)
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		final Actor target = e.getActor();
		if (target == local)
		{
			// We are being hit.
			lastHitsplatTick = client.getTickCount();
			mark();
		}
		else if (e.getHitsplat().isMine() && target == local.getInteracting())
		{
			// Our own damage on the thing we are fighting.
			lastHitsplatTick = client.getTickCount();
			mark();
		}
	}

	public void onInteractingChanged(InteractingChanged e)
	{
		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		// Interaction alone isn't combat — following someone / talking to an NPC also "interacts".
		// Require a hitsplat within the last 25 ticks to confirm real combat (audit L5).
		if (client.getTickCount() - lastHitsplatTick > 25)
		{
			return;
		}
		// We start attacking an NPC, or an NPC starts attacking us.
		if (e.getSource() == local && e.getTarget() instanceof NPC)
		{
			mark();
		}
		else if (e.getTarget() == local && e.getSource() instanceof NPC)
		{
			mark();
		}
	}

	public boolean isInCombat()
	{
		return client.getTickCount() - lastCombatTick <= COMBAT_TICKS;
	}

	public void reset()
	{
		lastCombatTick = -COMBAT_TICKS - 1;
	}

	private void mark()
	{
		lastCombatTick = client.getTickCount();
	}
}
