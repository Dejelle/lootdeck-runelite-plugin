package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.LinkState;
import com.lootdeck.tcg.net.Dtos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

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

		// Every link state renders without throwing (capped 1:N linking).
		onEdt(() -> panel.updateLinkState(LinkState.NOT_LINKED, "", 0, 3));
		onEdt(() -> panel.updateLinkState(LinkState.MAX_REACHED, "Zezima", 3, 3));
		onEdt(() -> panel.updateLinkState(LinkState.OTHER_USER, "Zezima", 0, 3));
		onEdt(() -> panel.updateLinkState(LinkState.UNKNOWN, "Zezima", 1, 3));
		onEdt(() -> panel.updateLinkState(LinkState.LINKED, "Zezima", 1, 3));

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
		onEdt(() -> panel.updateLinkState(LinkState.LINKED, "Zezima", 1, 3));

		// 20 packs — well over the 8-cell cap; must not throw and must produce a "+N" tile path.
		List<Dtos.UserPack> many = new ArrayList<>();
		String[] tiers = {"bronze", "steel", "rune", "dragon"};
		for (int i = 0; i < 20; i++)
		{
			many.add(pack(tiers[i % tiers.length] + i));
		}
		onEdt(() -> panel.updatePackList(many));
	}

	private static void collectTexts(java.awt.Container c, List<String> out)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof javax.swing.JLabel)
			{
				out.add(String.valueOf(((javax.swing.JLabel) child).getText()));
			}
			if (child instanceof javax.swing.AbstractButton)
			{
				out.add(String.valueOf(((javax.swing.AbstractButton) child).getText()));
			}
			if (child instanceof java.awt.Container)
			{
				collectTexts((java.awt.Container) child, out);
			}
		}
	}

	@Test
	public void sidebarPolishInvariants() throws Exception
	{
		final TcgPanel[] holder = new TcgPanel[1];
		onEdt(() -> holder[0] = new TcgPanel());
		TcgPanel panel = holder[0];

		// LINKED with a real RSN: shows the name, never the old placeholder,
		// has View collection + the bottom disclaimer, and no Refresh button.
		onEdt(() -> panel.updateLinkState(LinkState.LINKED, "Zezima", 1, 3));
		List<String> texts = new ArrayList<>();
		onEdt(() -> collectTexts(panel, texts));
		assertTrue(texts.stream().anyMatch(t -> t.contains("Linked as Zezima")));
		assertTrue(texts.stream().noneMatch(t -> t.contains("your account")));
		assertTrue(texts.stream().anyMatch(t -> t.equals("View collection")));
		assertTrue(texts.stream().noneMatch(t -> t.equals("Refresh")));
		assertTrue(texts.stream().anyMatch(t -> t.contains("Disclaimer")));

		// LINKED with the RSN not yet resolved: plain "Linked", still no placeholder.
		onEdt(() -> panel.updateLinkState(LinkState.LINKED, "", 0, 0));
		List<String> texts2 = new ArrayList<>();
		onEdt(() -> collectTexts(panel, texts2));
		assertTrue(texts2.stream().anyMatch(t -> t.equals("Linked")));
		assertTrue(texts2.stream().noneMatch(t -> t.contains("your account")));

		// NOT_LINKED keeps the pre-opt-in disclosure AND the bottom disclaimer.
		onEdt(() -> panel.updateLinkState(LinkState.NOT_LINKED, "", 0, 3));
		List<String> texts3 = new ArrayList<>();
		onEdt(() -> collectTexts(panel, texts3));
		assertTrue(texts3.stream().anyMatch(t -> t.contains("Linking is opt-in")));
		assertTrue(texts3.stream().anyMatch(t -> t.contains("Disclaimer")));
	}

	@Test
	public void addFeedFromWorkerThreadIsSafe() throws Exception
	{
		final TcgPanel[] holder = new TcgPanel[1];
		onEdt(() -> holder[0] = new TcgPanel());
		TcgPanel panel = holder[0];

		// Hammer addFeed from 4 worker threads — the deque must only ever be touched on the EDT
		// (audit H5), so this must neither throw nor corrupt the 3-line cap.
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
		for (int i = 0; i < 200; i++)
		{
			final int n = i;
			pool.submit(() -> panel.addFeed("line " + n));
		}
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
		SwingUtilities.invokeAndWait(() -> { }); // flush every queued mutation
		final int[] size = {0};
		SwingUtilities.invokeAndWait(() -> size[0] = panel.feedSize());
		assertTrue("feed stays capped at 3", size[0] <= 3);
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
