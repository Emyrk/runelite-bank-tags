package com.emyrk.banktags.sync.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nullable;

/** One synchronized one-level folder containing an ordered list of stable tag UUIDs. */
public final class SharedBankTagFolder
{
	public static final int SCHEMA_VERSION = 1;

	private final int schemaVersion;
	private final String folderId;
	private final String name;
	private final int iconItemId;
	private final List<String> orderedTagIds;
	private final long revision;
	private final boolean deleted;
	@Nullable
	private final String updatedAt;

	public SharedBankTagFolder(String folderId, String name, int iconItemId, List<String> orderedTagIds,
		long revision, boolean deleted, @Nullable String updatedAt)
	{
		this.schemaVersion = SCHEMA_VERSION;
		this.folderId = folderId;
		this.name = normalizeName(name);
		if (iconItemId < 0)
		{
			throw new IllegalArgumentException("folder icon item id must be non-negative");
		}
		this.iconItemId = iconItemId;
		this.orderedTagIds = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(orderedTagIds)));
		this.revision = revision;
		this.deleted = deleted;
		this.updatedAt = updatedAt;
	}

	public int getSchemaVersion() { return schemaVersion; }
	public String getFolderId() { return folderId; }
	public String getName() { return name; }
	public int getIconItemId() { return iconItemId; }
	public List<String> getOrderedTagIds() { return orderedTagIds; }
	public long getRevision() { return revision; }
	public boolean isDeleted() { return deleted; }
	@Nullable public String getUpdatedAt() { return updatedAt; }

	public String contentHash()
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream data = new DataOutputStream(bytes))
			{
				writeString(data, name);
				data.writeInt(iconItemId);
				data.writeInt(orderedTagIds.size());
				for (String tagId : orderedTagIds)
				{
					writeString(data, tagId);
				}
			}
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
			StringBuilder result = new StringBuilder(hash.length * 2);
			for (byte value : hash)
			{
				result.append(String.format("%02x", value & 0xff));
			}
			return result.toString();
		}
		catch (IOException | NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("Unable to hash bank tag folder", ex);
		}
	}

	private static String normalizeName(String name)
	{
		if (name == null || name.trim().isEmpty() || name.trim().length() > 50)
		{
			throw new IllegalArgumentException("folder name must contain 1 to 50 characters");
		}
		return name.trim();
	}

	private static void writeString(DataOutputStream data, String value) throws IOException
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		data.writeInt(bytes.length);
		data.write(bytes);
	}
}
