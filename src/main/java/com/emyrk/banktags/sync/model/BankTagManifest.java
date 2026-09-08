package com.emyrk.banktags.sync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable group manifest returned by {@code GET /bank-tags} and {@code PUT /bank-tag-order}.
 * See docs/remote-sync-protocol.md.
 */
public final class BankTagManifest
{
	private final int schemaVersion;
	private final long groupRevision;
	private final long orderRevision;
	private final List<String> orderedTagIds;
	private final List<Entry> tags;

	public BankTagManifest(int schemaVersion, long groupRevision, long orderRevision,
		List<String> orderedTagIds, List<Entry> tags)
	{
		this.schemaVersion = schemaVersion;
		this.groupRevision = groupRevision;
		this.orderRevision = orderRevision;
		this.orderedTagIds = Collections.unmodifiableList(new ArrayList<>(orderedTagIds));
		this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
	}

	public int getSchemaVersion()
	{
		return schemaVersion;
	}

	public long getGroupRevision()
	{
		return groupRevision;
	}

	public long getOrderRevision()
	{
		return orderRevision;
	}

	public List<String> getOrderedTagIds()
	{
		return orderedTagIds;
	}

	public List<Entry> getTags()
	{
		return tags;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof BankTagManifest))
		{
			return false;
		}
		BankTagManifest that = (BankTagManifest) other;
		return schemaVersion == that.schemaVersion
			&& groupRevision == that.groupRevision
			&& orderRevision == that.orderRevision
			&& orderedTagIds.equals(that.orderedTagIds)
			&& tags.equals(that.tags);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(schemaVersion, groupRevision, orderRevision, orderedTagIds, tags);
	}

	@Override
	public String toString()
	{
		return "BankTagManifest{schemaVersion=" + schemaVersion + ", groupRevision=" + groupRevision
			+ ", orderRevision=" + orderRevision + ", orderedTagIds=" + orderedTagIds + ", tags=" + tags + '}';
	}

	/**
	 * One tag's metadata within the manifest. Tombstones are listed with {@code deleted == true}.
	 */
	public static final class Entry
	{
		private final String tagId;
		private final String name;
		private final long revision;
		private final boolean deleted;

		public Entry(String tagId, String name, long revision, boolean deleted)
		{
			this.tagId = tagId;
			this.name = name;
			this.revision = revision;
			this.deleted = deleted;
		}

		public String getTagId()
		{
			return tagId;
		}

		public String getName()
		{
			return name;
		}

		public long getRevision()
		{
			return revision;
		}

		public boolean isDeleted()
		{
			return deleted;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof Entry))
			{
				return false;
			}
			Entry that = (Entry) other;
			return revision == that.revision
				&& deleted == that.deleted
				&& Objects.equals(tagId, that.tagId)
				&& Objects.equals(name, that.name);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(tagId, name, revision, deleted);
		}

		@Override
		public String toString()
		{
			return "Entry{tagId=" + tagId + ", name=" + name + ", revision=" + revision + ", deleted=" + deleted + '}';
		}
	}
}
