package com.lootdeck.tcg.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import net.runelite.client.ui.ColorScheme;

/**
 * Minimal in-client bug-report / feedback dialog. Dumb by design: it collects a kind + message
 * and hands them to a callback; all networking lives in TcgPlugin. Styled with ColorScheme so it
 * matches the RuneLite dark theme.
 */
public class FeedbackDialog extends JDialog
{
	private static final int MAX_CHARS = 2000;
	private static final int MIN_CHARS = 10;

	public FeedbackDialog(Component parent, BiConsumer<String, String> onSubmit)
	{
		super(SwingUtilities.getWindowAncestor(parent) instanceof Frame
			? (Frame) SwingUtilities.getWindowAncestor(parent) : null,
			"LootDeck feedback", ModalityType.MODELESS);

		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Kind toggle: Bug (default) / Feedback.
		JToggleButton bug = themedToggle("Bug");
		JToggleButton feedback = themedToggle("Feedback");
		bug.setSelected(true);
		ButtonGroup group = new ButtonGroup();
		group.add(bug);
		group.add(feedback);
		JPanel kinds = new JPanel(new GridLayout(1, 2, 4, 0));
		kinds.setBackground(ColorScheme.DARK_GRAY_COLOR);
		kinds.add(bug);
		kinds.add(feedback);

		JTextArea area = new JTextArea(8, 30);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setForeground(Color.WHITE);
		area.setCaretColor(Color.WHITE);
		((AbstractDocument) area.getDocument()).setDocumentFilter(new MaxLengthFilter(MAX_CHARS));
		JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(320, 160));

		JLabel hint = new JLabel("Minimum " + MIN_CHARS + " characters.");
		hint.setForeground(Color.GRAY);

		JButton submit = themedButton("Submit");
		JButton cancel = themedButton("Cancel");
		submit.setEnabled(false);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(cancel);
		buttons.add(submit);

		// Enable Submit only when there are >= MIN_CHARS non-space characters.
		area.getDocument().addDocumentListener(new DocumentListener()
		{
			private void update()
			{
				submit.setEnabled(area.getText().trim().length() >= MIN_CHARS);
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				update();
			}
		});

		cancel.addActionListener(e -> dispose());
		submit.addActionListener(e -> {
			String kind = bug.isSelected() ? "bug" : "feedback";
			onSubmit.accept(kind, area.getText().trim());
			dispose();
		});

		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(kinds, BorderLayout.NORTH);
		top.add(hint, BorderLayout.SOUTH);

		root.add(top, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);

		setContentPane(root);
		pack();
		setLocationRelativeTo(parent);
	}

	private static JToggleButton themedToggle(String text)
	{
		JToggleButton b = new JToggleButton(text);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setFocusPainted(false);
		return b;
	}

	private static JButton themedButton(String text)
	{
		JButton b = new JButton(text);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setFocusPainted(false);
		return b;
	}

	/** Hard-caps the text area at maxLength characters (paste-safe). */
	private static final class MaxLengthFilter extends DocumentFilter
	{
		private final int maxLength;

		private MaxLengthFilter(int maxLength)
		{
			this.maxLength = maxLength;
		}

		@Override
		public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
			throws BadLocationException
		{
			if (string == null)
			{
				return;
			}
			int room = maxLength - fb.getDocument().getLength();
			if (room <= 0)
			{
				return;
			}
			super.insertString(fb, offset, string.length() > room ? string.substring(0, room) : string, attr);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attr)
			throws BadLocationException
		{
			if (text == null)
			{
				super.replace(fb, offset, length, text, attr);
				return;
			}
			int room = maxLength - (fb.getDocument().getLength() - length);
			if (room <= 0)
			{
				return;
			}
			super.replace(fb, offset, length, text.length() > room ? text.substring(0, room) : text, attr);
		}
	}
}
