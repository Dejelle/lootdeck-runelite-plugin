package com.lootdeck.tcg.activity;

import javax.annotation.Nullable;

/** A classified activity: an id from the bounded ACTIVITY_IDS vocabulary + optional context. */
public class ActivityType
{
	private final String id;
	@Nullable
	private final Integer itemId;
	private final int quantity;

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
