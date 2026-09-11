package com.emyrk.banktags.inventorysync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The single combined Inventory Setups manifest returned by GET /inventory-setups. */
public final class InventorySetupManifest
{
	public static final class SetupEntry
	{
		private final String setupId;
		private final String name;
		private final long revision;
		private final boolean deleted;

		public SetupEntry(String setupId, String name, long revision, boolean deleted)
		{
			this.setupId = setupId;
			this.name = name;
			this.revision = revision;
			this.deleted = deleted;
		}

		public String getSetupId() { return setupId; }
		public String getName() { return name; }
		public long getRevision() { return revision; }
		public boolean isDeleted() { return deleted; }
	}

	public static final class SectionEntry
	{
		private final String sectionId;
		private final String name;
		private final long revision;
		private final boolean deleted;

		public SectionEntry(String sectionId, String name, long revision, boolean deleted)
		{
			this.sectionId = sectionId;
			this.name = name;
			this.revision = revision;
			this.deleted = deleted;
		}

		public String getSectionId() { return sectionId; }
		public String getName() { return name; }
		public long getRevision() { return revision; }
		public boolean isDeleted() { return deleted; }
	}

	private final long groupRevision;
	private final long setupOrderRevision;
	private final long sectionOrderRevision;
	private final List<String> orderedSetupIds;
	private final List<String> orderedSectionIds;
	private final List<SetupEntry> setups;
	private final List<SectionEntry> sections;

	public InventorySetupManifest(long groupRevision, long setupOrderRevision, long sectionOrderRevision,
		List<String> orderedSetupIds, List<String> orderedSectionIds, List<SetupEntry> setups,
		List<SectionEntry> sections)
	{
		this.groupRevision = groupRevision;
		this.setupOrderRevision = setupOrderRevision;
		this.sectionOrderRevision = sectionOrderRevision;
		this.orderedSetupIds = immutableCopy(orderedSetupIds);
		this.orderedSectionIds = immutableCopy(orderedSectionIds);
		this.setups = Collections.unmodifiableList(new ArrayList<>(setups));
		this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
	}

	public long getGroupRevision() { return groupRevision; }
	public long getSetupOrderRevision() { return setupOrderRevision; }
	public long getSectionOrderRevision() { return sectionOrderRevision; }
	public List<String> getOrderedSetupIds() { return orderedSetupIds; }
	public List<String> getOrderedSectionIds() { return orderedSectionIds; }
	public List<SetupEntry> getSetups() { return setups; }
	public List<SectionEntry> getSections() { return sections; }

	private static List<String> immutableCopy(List<String> values)
	{
		return Collections.unmodifiableList(new ArrayList<>(values));
	}
}
