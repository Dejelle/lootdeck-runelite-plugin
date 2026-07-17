package com.lootdeck.tcg;

/**
 * Per-character link status for the sidebar, under capped 1:N linking
 * (plan/multi-osrs-accounts). Resolved from GET /plugin/link/status.
 *
 * <ul>
 *   <li>{@code LINKED} — this character belongs to the token's LootDeck account.</li>
 *   <li>{@code NOT_LINKED} — no LootDeck account has linked this character yet (or no token).</li>
 *   <li>{@code MAX_REACHED} — unlinked and the user is already at the account cap.</li>
 *   <li>{@code OTHER_USER} — this character is linked to a DIFFERENT LootDeck account.</li>
 *   <li>{@code UNKNOWN} — the status check failed (network/5xx); we FAIL OPEN and allow reporting.</li>
 * </ul>
 */
public enum LinkState
{
	LINKED,
	NOT_LINKED,
	MAX_REACHED,
	OTHER_USER,
	UNKNOWN
}
