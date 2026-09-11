package com.emyrk.banktags.inventorysync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SharedInventorySetupSection
{
	private final String sectionId;
	private final String name;
	private final Integer displayColor;
	private final List<String> orderedSetupIds;
	private final long revision;
	private final boolean deleted;

	public SharedInventorySetupSection(String sectionId, String name, Integer displayColor,
		List<String> orderedSetupIds, long revision, boolean deleted)
	{
		this.sectionId = sectionId;
		this.name = name;
		this.displayColor = displayColor;
		this.orderedSetupIds = Collections.unmodifiableList(new ArrayList<>(orderedSetupIds));
		this.revision = revision;
		this.deleted = deleted;
	}

	public String getSectionId() { return sectionId; }
	public String getName() { return name; }
	public Integer getDisplayColor() { return displayColor; }
	public List<String> getOrderedSetupIds() { return orderedSetupIds; }
	public long getRevision() { return revision; }
	public boolean isDeleted() { return deleted; }

	public SharedInventorySetupSection withServerState(long revision, boolean deleted)
	{
		return new SharedInventorySetupSection(sectionId, name, displayColor, orderedSetupIds, revision, deleted);
	}

	public String contentKey()
	{
		return name + "\n" + displayColor + "\n" + String.join(",", orderedSetupIds);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof SharedInventorySetupSection))
		{
			return false;
		}
		SharedInventorySetupSection section = (SharedInventorySetupSection) other;
		return revision == section.revision && deleted == section.deleted
			&& Objects.equals(sectionId, section.sectionId) && Objects.equals(name, section.name)
			&& Objects.equals(displayColor, section.displayColor)
			&& Objects.equals(orderedSetupIds, section.orderedSetupIds);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(sectionId, name, displayColor, orderedSetupIds, revision, deleted);
	}
}
