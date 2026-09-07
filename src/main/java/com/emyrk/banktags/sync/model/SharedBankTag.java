package com.emyrk.banktags.sync.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import net.runelite.client.util.Text;

public final class SharedBankTag
{
	public static final int SCHEMA_VERSION = 1;

	private final int schemaVersion;
	private final String tagId;
	private final String name;
	private final int iconItemId;
	private final List<Integer> itemIds;
	private final int[] layout;
	private final long revision;
	private final boolean deleted;

	public SharedBankTag(String tagId, String name, int iconItemId, List<Integer> itemIds,
		int[] layout, long revision, boolean deleted)
	{
		this.schemaVersion = SCHEMA_VERSION;
		this.tagId = tagId;
		this.name = Text.standardize(name);
		this.iconItemId = iconItemId;
		this.itemIds = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(itemIds)));
		this.layout = layout == null ? null : layout.clone();
		this.revision = revision;
		this.deleted = deleted;
	}

	public int getSchemaVersion()
	{
		return schemaVersion;
	}

	public String getTagId()
	{
		return tagId;
	}

	public String getName()
	{
		return name;
	}

	public int getIconItemId()
	{
		return iconItemId;
	}

	public List<Integer> getItemIds()
	{
		return itemIds;
	}

	public int[] getLayout()
	{
		return layout == null ? null : layout.clone();
	}

	public long getRevision()
	{
		return revision;
	}

	public boolean isDeleted()
	{
		return deleted;
	}

	public String contentHash()
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream data = new DataOutputStream(bytes))
			{
				writeString(data, name);
				data.writeInt(iconItemId);
				data.writeInt(itemIds.size());
				for (int itemId : itemIds)
				{
					data.writeInt(itemId);
				}
				data.writeBoolean(layout != null);
				if (layout != null)
				{
					data.writeInt(layout.length);
					for (int itemId : layout)
					{
						data.writeInt(itemId);
					}
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
			throw new IllegalStateException("Unable to hash bank tag", ex);
		}
	}

	private static void writeString(DataOutputStream data, String value) throws IOException
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		data.writeInt(bytes.length);
		data.write(bytes);
	}
}
