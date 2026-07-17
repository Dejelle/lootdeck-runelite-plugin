package com.lootdeck.tcg.net;

import java.util.List;

/** Gson POJOs mirroring the @lootdeck/types shapes. Fields are serialized as-is. */
public final class Dtos
{
	private Dtos()
	{
	}

	public static final class DropContext
	{
		// Integer (not int) so Gson OMITS these when null: a kill sends {quantity,combatLevel}
		// with no itemId; a SKILL_GATHER sends {itemId,quantity} with no combatLevel. A primitive
		// int would serialize as 0 and fail the server's positive-int validation.
		public Integer itemId;
		public int quantity;
		public Integer combatLevel;

		public DropContext(Integer itemId, int quantity, Integer combatLevel)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.combatLevel = combatLevel;
		}
	}

	public static final class DropReport
	{
		public String idempotencyKey;
		public String accountHash; // signed 64-bit as string
		public String rsn;
		public String profileType;
		public String activity;
		public long clientTs;
		public DropContext context; // only for SKILL_GATHER
	}

	public static final class DropResult
	{
		public boolean dropped;
		public String pendingPackId;
		public String tier;
		public String expiresAt;
		public String packArtUrl;  // release-specific booster art (CDN .png); null = bundled tier art
	}

	public static final class RedeemReq
	{
		public String code;
		public String accountHash;
		public String rsn;
		public String profileType;
	}

	public static final class TokenResp
	{
		public String token;
		public String osrsAccountId;
	}

	public static final class UserPack
	{
		public String id;
		public String tier;
		public String setCode;     // release this pack mints from; null = latest at open
		public String packArtUrl;  // release-specific booster art (CDN .png); null = bundled tier art
	}

	public static final class PacksResp
	{
		public List<UserPack> packs;
		public java.util.Map<String, Integer> countsByTier;
		public String cardBackUrl;  // global card back (CDN .png?v=); null = use bundled
	}

	/** A card definition as returned inside an opened-pack item. */
	public static final class CardDef
	{
		public String id;
		public String name;
		public String subtitle;
		public String rarity;     // common..legendary
		public String category;
		public int numberInSet;
		public int totalInSet;
		public String baseImageUrl;  // *.webp — swap to .png for Java
		public String foilImageUrl;
		public String thumbImageUrl;
	}

	/** One card produced by opening a pack. */
	public static final class OpenedCard
	{
		public String definitionId;
		public boolean isFoil;
		public String mintNumber;
		public String instanceId;
		public CardDef definition;
	}

	public static final class OpenResp
	{
		public List<OpenedCard> items;
	}

	/** One recent opening (a pack + the cards it produced). */
	public static final class Opening
	{
		public String userPackId;
		public String tier;
		public String openedAt;
		public List<OpenedCard> cards;
	}

	public static final class PendingPack
	{
		public String id;
		public String tier;
		public String setCode;
		public String status;
		public String expiresAt;
	}

	public static final class ClaimResp
	{
		public String userPackId;
	}

	/** A card release (one CardSet), for the pack-release picker. */
	public static final class Release
	{
		public String code;          // e.g. LUMBRIDGE
		public String name;          // e.g. Lumbridge
		public String releasedAt;
		public String packArtPrefix; // CDN key prefix for this release's booster art (may be null)
	}

	/** GET /plugin/packs/releases */
	public static final class ReleasesResp
	{
		public List<Release> releases; // newest first
		public String selected;        // current preference; null = latest release
	}

	/** POST /plugin/packs/releases/select */
	public static final class SelectReleaseReq
	{
		public String setCode; // null = follow the latest release

		public SelectReleaseReq(String setCode)
		{
			this.setCode = setCode;
		}
	}

	/** POST /feedback/plugin body. */
	public static final class FeedbackReq
	{
		public String kind;      // "bug" | "feedback"
		public String message;
		public java.util.Map<String, String> context;
	}

	/** POST /feedback/plugin response. */
	public static final class FeedbackResp
	{
		public String id;
		public String createdAt;
	}

	/** GET /plugin/link/status?accountHash= — per-character link status for the sidebar. */
	public static final class LinkStatusResp
	{
		public boolean linked;      // some LootDeck account has linked this character
		public boolean thisUser;    // ...and it is the token's owner
		public String displayName;  // this character's RSN (null unless thisUser)
		public int linkedCount;     // characters the token's user has linked
		public int maxAccounts;     // admin-configured cap
		public boolean atMax;       // linkedCount >= maxAccounts
	}

	/** { error: { code, message } } */
	public static final class ApiError
	{
		public Inner error;

		public static final class Inner
		{
			public String code;
			public String message;
		}
	}
}
