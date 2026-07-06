package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.net.Dtos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Headless smoke tests for the side panel. They exercise every rebuild() branch (unlinked,
 * linked, empty, packs below/over the grid cap, pending, and openings) to guard against the
 * threading / null / layout regressions that are easy to reintroduce when editing Swing code.
 * No display is required — components are built but never shown.
 */
public class TcgPanelTest
{
	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	/** Run r on the EDT and flush pending invokeLater()s so rebuild() has actually happened. */
	private static void onEdt(Runnable r) throws Exception
	{
		SwingUtilities.invokeAndWait(r);
		SwingUtilities.invokeAndWait(() -> { });
	}

	private static Dtos.UserPack pack(String tier)
	{
		Dtos.UserPack p = new Dtos.UserPack();
		p.id = "up-" + tier + "-" + Math.abs(tier.hashCode());
		p.tier = tier;
		return p;
	}

	private static Dtos.Opening opening(String tier, int cards)
	{
		Dtos.Opening o = new Dtos.Opening();
		o.tier = tier;
		o.cards = new ArrayList<>();
		for (int i = 0; i < cards; i++)
		{
			Dtos.OpenedCard c = new Dtos.OpenedCard();
			c.isFoil = i == 0;
			Dtos.CardDef d = new Dtos.CardDef();
			d.rarity = "common";
			c.definition = d;
			o.cards.add(c);
		}
		return o;
	}

	@Test
	public void buildsAcrossEveryState() throws Exception
	{
		final TcgPanel[] holder = new TcgPanel[1];
		onEdt(() -> holder[0] = new TcgPanel());
		TcgPanel panel = holder[0];

		// Unlinked -> linked.
		onEdt(() -> panel.updateLinked(false, ""));
		onEdt(() -> panel.updateLinked(true, "Zezima"));

		// A few packs (fits under the grid cap).
		onEdt(() -> panel.updatePackList(Arrays.asList(pack("bronze"), pack("steel"), pack("rune"))));

		// Feed lines cap at 3 — add more and ensure it survives.
		onEdt(() -> {
			panel.addFeed("one");
			panel.addFeed("two");
			panel.addFeed("three");
			panel.addFeed("four");
		});

		// Pending "Take" rows.
		Dtos.PendingPack pp = new Dtos.PendingPack();
		pp.id = "pp1";
		pp.tier = "dragon";
		pp.expiresAt = "2999-01-01T00:00:00Z";
		onEdt(() -> panel.updatePending(Arrays.asList(pp)));

		// Openings present (collapsed by default — no thumbnail rows built).
		onEdt(() -> panel.updateOpenings(Arrays.asList(
			opening("bronze", 5), opening("steel", 5), opening("rune", 5), opening("dragon", 5))));
	}

	@Test
	public void handlesPackOverflowCap() throws Exception
	{
		final TcgPanel[] holder = new TcgPanel[1];
		onEdt(() -> holder[0] = new TcgPanel());
		TcgPanel panel = holder[0];
		onEdt(() -> panel.updateLinked(true, "Zezima"));

		// 20 packs — well over the 8-cell cap; must not throw and must produce a "+N" tile path.
		List<Dtos.UserPack> many = new ArrayList<>();
		String[] tiers = {"bronze", "steel", "rune", "dragon"};
		for (int i = 0; i < 20; i++)
		{
			many.add(pack(tiers[i % tiers.length] + i));
		}
		onEdt(() -> panel.updatePackList(many));
	}

	@Test
	public void handlesEmptyAndNullUpdates() throws Exception
	{
		final TcgPanel[] holder = new TcgPanel[1];
		onEdt(() -> holder[0] = new TcgPanel());
		TcgPanel panel = holder[0];
		onEdt(() -> {
			panel.updatePackList(null);
			panel.updateOpenings(null);
			panel.updatePending(java.util.Collections.emptyList());
		});
	}
}
