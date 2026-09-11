package com.emyrk.banktags.inventorysync.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SharedInventorySetup
{
	public static final int SCHEMA_VERSION = 1;

	private final String setupId;
	private final String name;
	private final String notes;
	private final JsonObject payload;
	private final long revision;
	private final boolean deleted;

	public SharedInventorySetup(String setupId, String name, String notes, JsonObject payload,
		long revision, boolean deleted)
	{
		this.setupId = setupId;
		this.name = name;
		this.notes = notes == null ? "" : notes;
		this.payload = payload == null ? new JsonObject() : payload.deepCopy();
		this.revision = revision;
		this.deleted = deleted;
	}

	public String getSetupId() { return setupId; }
	public String getName() { return name; }
	public String getNotes() { return notes; }
	public JsonObject getPayload() { return payload.deepCopy(); }
	public long getRevision() { return revision; }
	public boolean isDeleted() { return deleted; }

	public SharedInventorySetup withServerState(long revision, boolean deleted)
	{
		return new SharedInventorySetup(setupId, name, notes, payload, revision, deleted);
	}

	public String contentHash()
	{
		return sha256(name + "\n" + notes + "\n" + canonical(payload));
	}

	private static String canonical(JsonElement element)
	{
		if (element.isJsonObject())
		{
			return canonicalElement(element).toString();
		}
		return canonicalElement(element).toString();
	}

	private static JsonElement canonicalElement(JsonElement element)
	{
		if (element.isJsonObject())
		{
			JsonObject object = element.getAsJsonObject();
			List<String> keys = new ArrayList<>(object.keySet());
			Collections.sort(keys);
			JsonObject sorted = new JsonObject();
			for (String key : keys)
			{
				sorted.add(key, canonicalElement(object.get(key)));
			}
			return sorted;
		}
		if (element.isJsonArray())
		{
			JsonArray array = new JsonArray();
			for (JsonElement value : element.getAsJsonArray())
			{
				array.add(canonicalElement(value));
			}
			return array;
		}
		return element.deepCopy();
	}

	private static String sha256(String value)
	{
		try
		{
			byte[] bytes = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for (byte valueByte : bytes)
			{
				result.append(String.format("%02x", valueByte));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof SharedInventorySetup))
		{
			return false;
		}
		SharedInventorySetup setup = (SharedInventorySetup) other;
		return revision == setup.revision && deleted == setup.deleted
			&& Objects.equals(setupId, setup.setupId) && Objects.equals(name, setup.name)
			&& Objects.equals(notes, setup.notes) && Objects.equals(payload, setup.payload);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(setupId, name, notes, payload, revision, deleted);
	}
}
