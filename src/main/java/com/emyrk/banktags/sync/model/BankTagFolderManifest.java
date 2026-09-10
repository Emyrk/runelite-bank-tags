package com.emyrk.banktags.sync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Separate manifest for folders. The frozen tag v1 manifest is intentionally unchanged. */
public final class BankTagFolderManifest
{
	private final int schemaVersion;
	private final long groupRevision;
	private final long orderRevision;
	private final List<String> orderedFolderIds;
	private final List<Entry> folders;

	public BankTagFolderManifest(int schemaVersion, long groupRevision, long orderRevision,
		List<String> orderedFolderIds, List<Entry> folders)
	{
		this.schemaVersion = schemaVersion;
		this.groupRevision = groupRevision;
		this.orderRevision = orderRevision;
		this.orderedFolderIds = Collections.unmodifiableList(new ArrayList<>(orderedFolderIds));
		this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
	}

	public int getSchemaVersion() { return schemaVersion; }
	public long getGroupRevision() { return groupRevision; }
	public long getOrderRevision() { return orderRevision; }
	public List<String> getOrderedFolderIds() { return orderedFolderIds; }
	public List<Entry> getFolders() { return folders; }

	public static final class Entry
	{
		private final String folderId;
		private final String name;
		private final long revision;
		private final boolean deleted;

		public Entry(String folderId, String name, long revision, boolean deleted)
		{
			this.folderId = folderId;
			this.name = name;
			this.revision = revision;
			this.deleted = deleted;
		}

		public String getFolderId() { return folderId; }
		public String getName() { return name; }
		public long getRevision() { return revision; }
		public boolean isDeleted() { return deleted; }
	}
}
