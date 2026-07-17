package com.lootdeck.tcg.activity;

import javax.annotation.Nullable;

/** A classified activity: an id from the bounded ACTIVITY_IDS vocabulary + optional context. */
public class ActivityType
{
	private final String id;
	@Nullable
	private final Integer itemId;
	private final int quantity;
	// NPC combat level for kill activities (null otherwise). Sent on the drop report so the
	// server can bucket an excluded boss by combat level (activity-taxonomy Phase 2 fallback).
	@Nullable
	private Integer combatLevel;

	public ActivityType(String id)
	{
		this(id, null, 0);
	}

	public ActivityType(String id, @Nullable Integer itemId, int quantity)
	{
		this.id = id;
		this.itemId = itemId;
		this.quantity = quantity;
	}

	@Nullable
	public Integer getCombatLevel()
	{
		return combatLevel;
	}

	/** Stamp the NPC combat level; returns this for fluent use at the call site. */
	public ActivityType withCombatLevel(@Nullable Integer c)
	{
		this.combatLevel = c;
		return this;
	}

	public String getId()
	{
		return id;
	}

	@Nullable
	public Integer getItemId()
	{
		return itemId;
	}

	public int getQuantity()
	{
		return quantity;
	}
}
