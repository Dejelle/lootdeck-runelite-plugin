package com.lootdeck.tcg.ui;

import com.lootdeck.tcg.net.Dtos;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.util.LinkBrowser;

/** LootDeck side panel: link, pack counts, pending "Take", recent feed. */
public class TcgPanel extends PluginPanel
{
	// Pack grid sizing. ~1/4 the previous 84x118 art, 4 columns fit the ~209px usable panel width.
	private static final int PACK_W = 42;
	private static final int PACK_H = 59;
	private static final int PACK_COLS = 4;
	// Cap the grid so a hoard of packs can't bloat the panel: 2 rows, last cell becomes a "+N" tile.
	private static final int PACK_MAX_CELLS = PACK_COLS * 2;
	private static final int OPENINGS_MAX = 3;

	// Stable website URLs only — never a payment-provider URL (plugin ships once;
	// the website can change providers without a plugin update).
	private static final String WEBSITE_URL = "https://www.lootdeck.org";
	private static final String DONATE_URL = WEBSITE_URL + "/donate";
	private static final String LINK_PAGE_URL = WEBSITE_URL + "/link";
	private static final String SIGNIN_URL = WEBSITE_URL + "/signin";

	private final JPanel content = new JPanel();

	private Consumer<String> linkHandler = c -> {
	};
	private Consumer<String> claimHandler = id -> {
	};
	private Runnable refreshHandler = () -> {
	};

	private boolean linked = false;
	private String rsn = "";
	private Map<String, Integer> packCounts = java.util.Collections.emptyMap();
	private List<Dtos.UserPack> packList = java.util.Collections.emptyList();
	private List<Dtos.PendingPack> pending = java.util.Collections.emptyList();
	private final java.util.Deque<String> feed = new java.util.ArrayDeque<>();
	private Consumer<Dtos.UserPack> openHandler = p -> {
	};
	private List<Dtos.Opening> openings = java.util.Collections.emptyList();
	private boolean openingsExpanded = false;
	private com.lootdeck.tcg.net.ImageCache images;
	private List<Dtos.Release> releases = java.util.Collections.emptyList();
	private String selectedRelease; // CardSet code; null = follow the latest release
	private Consumer<String> releaseHandler = code -> {
	};

	public void updateOpenings(List<Dtos.Opening> o)
	{
		this.openings = o != null ? o : java.util.Collections.emptyList();
		SwingUtilities.invokeLater(this::rebuild);
	}

	public void setImageCache(com.lootdeck.tcg.net.ImageCache c)
	{
		this.images = c;
	}

	public TcgPanel()
	{
		// Default (wrap = true) so PluginPanel wraps us in its own JScrollPane — the sidebar then
		// scrolls internally and never forces the RuneLite client window taller than the screen.
		super();
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.NORTH);
		rebuild();
	}

	public void setLinkHandler(Consumer<String> h)
	{
		this.linkHandler = h;
	}

	public void setClaimHandler(Consumer<String> h)
	{
		this.claimHandler = h;
	}

	public void setRefreshHandler(Runnable h)
	{
		this.refreshHandler = h;
	}

	public void updateLinked(boolean linked, String rsn)
	{
		this.linked = linked;
		this.rsn = rsn == null ? "" : rsn;
		SwingUtilities.invokeLater(this::rebuild);
	}

	public void updatePacks(Map<String, Integer> counts)
	{
		this.packCounts = counts;
		SwingUtilities.invokeLater(this::rebuild);
	}

	public void updatePackList(List<Dtos.UserPack> packs)
	{
		this.packList = packs != null ? packs : java.util.Collections.emptyList();
		SwingUtilities.invokeLater(this::rebuild);
	}

	public void setOpenHandler(Consumer<Dtos.UserPack> h)
	{
		this.openHandler = h;
	}

	public void updatePending(List<Dtos.PendingPack> pending)
	{
		this.pending = pending;
		SwingUtilities.invokeLater(this::rebuild);
	}

	/** Called after the plugin selected a new release, or the change failed (revert). */
	public void updateReleases(List<Dtos.Release> releases, String selected)
	{
		this.releases = releases != null ? releases : java.util.Collections.emptyList();
		this.selectedRelease = selected;
		SwingUtilities.invokeLater(this::rebuild);
	}

	public void setReleaseHandler(Consumer<String> h)
	{
		this.releaseHandler = h;
	}

	public void addFeed(String line)
	{
		feed.addFirst(line);
		while (feed.size() > 3)
		{
			feed.removeLast();
		}
		SwingUtilities.invokeLater(this::rebuild);
	}

	// ---- Styling helpers: nothing in this panel may rely on light LAF defaults. ----

	/** Transparent layout container — parent's dark background shows through. */
	private static <T extends JComponent> T clear(T c)
	{
		c.setOpaque(false);
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JLabel body(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(Color.WHITE);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel muted(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setFocusPainted(false);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return b;
	}

	private JLabel header(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private void rebuild()
	{
		content.removeAll();

		if (!linked)
		{
			content.add(header("Link your account"));
			JLabel help = new JLabel("<html>Get a code at the website's Link page, paste it below.</html>");
			help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			help.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(help);
			FlatTextField code = new FlatTextField();
			code.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			code.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			code.getTextField().setForeground(Color.WHITE);
			code.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
			code.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(code);
			JButton link = button("Link");
			link.addActionListener(e -> {
				String c = code.getText().trim().toUpperCase();
				if (!c.isEmpty())
				{
					link.setEnabled(false);
					linkHandler.accept(c);
				}
			});
			content.add(link);
			JButton openLinkPage = button("Open Link page");
			openLinkPage.addActionListener(e -> LinkBrowser.browse(LINK_PAGE_URL));
			content.add(openLinkPage);
		}
		else
		{
			content.add(header("Linked"));
			content.add(body("Linked as " + (rsn.isEmpty() ? "your account" : rsn)));
		}

		// Booster release picker — which card release NEW packs come from. With a single
		// release it is a one-item dropdown; it becomes meaningful from release 2 on.
		if (linked && !releases.isEmpty())
		{
			content.add(header("Booster release"));
			content.add(releasePicker());
		}

		// Packs — image grid of unopened packs (click to open).
		content.add(header("Unopened packs"));
		if (packList.isEmpty())
		{
			content.add(muted("None yet."));
		}
		else
		{
			JPanel grid = clear(new JPanel(new GridLayout(0, PACK_COLS, 4, 4)));
			int shown = Math.min(packList.size(), PACK_MAX_CELLS);
			// If everything fits, show all; otherwise reserve the last cell for a "+N" overflow tile.
			boolean overflow = packList.size() > PACK_MAX_CELLS;
			int cells = overflow ? PACK_MAX_CELLS - 1 : shown;
			for (int i = 0; i < cells; i++)
			{
				grid.add(packCell(packList.get(i)));
			}
			if (overflow)
			{
				grid.add(overflowCell(packList.size() - cells));
			}
			content.add(grid);
		}

		// Pending "Take"
		if (pending != null && !pending.isEmpty())
		{
			content.add(header("On the ground"));
			for (Dtos.PendingPack p : pending)
			{
				JPanel row = clear(new JPanel(new BorderLayout(4, 0)));
				row.add(body(p.tier + secondsLeft(p.expiresAt)), BorderLayout.CENTER);
				JButton take = button("Take");
				take.addActionListener(e -> {
					take.setEnabled(false);
					claimHandler.accept(p.id);
				});
				row.add(take, BorderLayout.EAST);
				content.add(row);
			}
		}

		// Feed
		content.add(header("Recent"));
		if (feed.isEmpty())
		{
			content.add(muted("—"));
		}
		else
		{
			for (String f : feed)
			{
				content.add(muted(f));
			}
		}

		// Recent openings — collapsed-by-default dropdown, max 3 shown. Collapsing avoids rendering
		// (and fetching thumbnails for) tall rows that would otherwise stretch the panel.
		int shownOpenings = Math.min(openings.size(), OPENINGS_MAX);
		content.add(openingsToggle(shownOpenings));
		if (openingsExpanded)
		{
			if (openings.isEmpty())
			{
				content.add(muted("None yet."));
			}
			else
			{
				for (int i = 0; i < shownOpenings; i++)
				{
					content.add(openingRow(openings.get(i)));
				}
			}
		}

		JButton refresh = button("Refresh");
		refresh.addActionListener(e -> refreshHandler.run());
		content.add(refresh);

		// Quiet footer links: create an account on the website, or donate. "Create account" is
		// the wider label, so it takes the larger share of the row (GridBag weightx) and the
		// shorter "Donate" gets the rest; tighter button padding keeps both from truncating in
		// the narrow (~209px) panel. Both are stable website URLs — the Donate button opens the
		// site's /donate page (which forwards to the current payment provider), never a
		// payment-provider URL directly, so the provider can change without a plugin update.
		// Donations keep the servers running and grant nothing in-game — hard invariant, see
		// plan/donations/DESIGN.md (D1/D5).
		JPanel links = clear(new JPanel(new java.awt.GridBagLayout()));
		links.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.gridy = 0;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;

		JButton createAccount = button("Create account");
		createAccount.setMargin(new java.awt.Insets(4, 4, 4, 4));
		createAccount.addActionListener(e -> LinkBrowser.browse(SIGNIN_URL));
		gc.gridx = 0;
		gc.weightx = 0.6;
		gc.insets = new java.awt.Insets(0, 0, 0, 2);
		links.add(createAccount, gc);

		JButton donate = button("Donate ♥");
		donate.setMargin(new java.awt.Insets(4, 4, 4, 4));
		donate.addActionListener(e -> LinkBrowser.browse(DONATE_URL));
		gc.gridx = 1;
		gc.weightx = 0.4;
		gc.insets = new java.awt.Insets(0, 2, 0, 0);
		links.add(donate, gc);

		content.add(links);

		content.revalidate();
		content.repaint();
	}

	/**
	 * Dropdown choosing which release new packs come from. Item 0 = "Latest release" (server
	 * default, code null); one item per released set after that. Selection is pushed through
	 * releaseHandler (async API call); the plugin calls updateReleases() with the result, which
	 * also reverts the combo if the call failed.
	 */
	private Component releasePicker()
	{
		String[] labels = new String[releases.size() + 1];
		String[] codes = new String[releases.size() + 1];
		labels[0] = "Latest release" + (releases.isEmpty() ? "" : " (" + releases.get(0).name + ")");
		codes[0] = null;
		for (int i = 0; i < releases.size(); i++)
		{
			labels[i + 1] = releases.get(i).name;
			codes[i + 1] = releases.get(i).code;
		}
		int current = 0;
		if (selectedRelease != null)
		{
			for (int i = 1; i < codes.length; i++)
			{
				if (selectedRelease.equals(codes[i]))
				{
					current = i;
					break;
				}
			}
		}
		javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(labels);
		combo.setSelectedIndex(current);
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setForeground(Color.WHITE);
		combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		combo.setAlignmentX(Component.LEFT_ALIGNMENT);
		combo.setToolTipText("New booster packs will contain cards from this release");
		combo.addActionListener(e -> {
			int idx = combo.getSelectedIndex();
			String code = idx >= 0 ? codes[idx] : null;
			boolean unchanged = (code == null && selectedRelease == null)
				|| (code != null && code.equals(selectedRelease));
			if (!unchanged)
			{
				combo.setEnabled(false);
				releaseHandler.accept(code);
			}
		});
		return combo;
	}

	/** Clickable header that expands/collapses the openings list. */
	private JLabel openingsToggle(int count)
	{
		String arrow = openingsExpanded ? "▾" : "▸"; // ▾ / ▸
		String label = openingsExpanded
			? arrow + " Recent openings"
			: arrow + " Recent openings" + (count > 0 ? " (" + count + ")" : "");
		JLabel l = header(label);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				openingsExpanded = !openingsExpanded;
				rebuild();
			}
		});
		return l;
	}

	private Component openingRow(Dtos.Opening o)
	{
		JPanel row = clear(new JPanel());
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.add(body(o.tier + " — " + o.cards.size() + " cards"));

		JPanel thumbs = clear(new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 3)));
		for (Dtos.OpenedCard c : o.cards)
		{
			JLabel t = new JLabel();
			t.setPreferredSize(new Dimension(34, 48));
			t.setBorder(BorderFactory.createLineBorder(
				com.lootdeck.tcg.ui.Rarity.color(c.definition != null ? c.definition.rarity : "common"),
				c.isFoil ? 2 : 1));
			thumbs.add(t);
			// Fetch thumbnail off the EDT, then set the icon back on the EDT.
			if (images != null && c.definition != null)
			{
				final String url = com.lootdeck.tcg.net.ImageCache.pngUrl(c.definition.thumbImageUrl);
				new Thread(() ->
				{
					java.awt.image.BufferedImage img = images.get(url);
					if (img != null)
					{
						java.awt.Image scaled = img.getScaledInstance(34, 48, java.awt.Image.SCALE_SMOOTH);
						SwingUtilities.invokeLater(() -> t.setIcon(new javax.swing.ImageIcon(scaled)));
					}
				}, "lootdeck-thumb").start();
			}
		}
		row.add(thumbs);
		return row;
	}

	private final Map<String, javax.swing.ImageIcon> packIcons = new java.util.HashMap<>();          // bundled, by tier
	private final Map<String, javax.swing.ImageIcon> packArtIcons = new java.util.concurrent.ConcurrentHashMap<>(); // CDN, by url

	private Component packCell(Dtos.UserPack p)
	{
		javax.swing.ImageIcon bundled = packIcons.computeIfAbsent(p.tier, t -> {
			java.awt.image.BufferedImage src = PackArt.image(t);
			if (src != null)
			{
				return new javax.swing.ImageIcon(src.getScaledInstance(PACK_W, PACK_H, java.awt.Image.SCALE_SMOOTH));
			}
			return new javax.swing.ImageIcon(PackArt.render(t, PACK_W, PACK_H));
		});
		// Release-specific booster art (if this pack carries a URL) overrides the bundled tier art.
		javax.swing.ImageIcon cached = p.packArtUrl != null ? packArtIcons.get(p.packArtUrl) : null;
		JButton b = new JButton(cached != null ? cached : bundled);
		b.setMargin(new java.awt.Insets(2, 2, 2, 2));
		b.setToolTipText("Open " + p.tier + " pack");
		b.setBorder(BorderFactory.createLineBorder(PackArt.tierColor(p.tier), 1, true));
		b.setContentAreaFilled(false);
		b.addActionListener(e -> {
			b.setEnabled(false);
			openHandler.accept(p);
		});
		// Not yet cached — fetch the release art off the EDT, then swap the icon back on the EDT.
		if (cached == null && p.packArtUrl != null && images != null)
		{
			final String url = com.lootdeck.tcg.net.ImageCache.pngUrl(p.packArtUrl);
			new Thread(() ->
			{
				java.awt.image.BufferedImage img = images.get(url);
				if (img != null)
				{
					javax.swing.ImageIcon ic = new javax.swing.ImageIcon(
						img.getScaledInstance(PACK_W, PACK_H, java.awt.Image.SCALE_SMOOTH));
					packArtIcons.put(p.packArtUrl, ic);
					SwingUtilities.invokeLater(() -> b.setIcon(ic));
				}
			}, "lootdeck-packart").start();
		}
		return b;
	}

	/** Non-interactive "+N more" tile shown when the pack hoard exceeds the grid cap. */
	private Component overflowCell(int more)
	{
		JLabel l = new JLabel("+" + more);
		l.setHorizontalAlignment(JLabel.CENTER);
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
		l.setOpaque(true);
		l.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		l.setPreferredSize(new Dimension(PACK_W, PACK_H));
		l.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true));
		l.setToolTipText(more + " more packs — open one to free a slot");
		return l;
	}

	private String secondsLeft(String expiresAt)
	{
		try
		{
			long ms = java.time.Instant.parse(expiresAt).toEpochMilli() - System.currentTimeMillis();
			long s = Math.max(0, ms / 1000);
			return " (" + s + "s)";
		}
		catch (Exception e)
		{
			return "";
		}
	}
}
