package com.emyrk.banktags.sync;

import com.emyrk.banktags.sync.model.BankTagManifest;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Encodes and decodes the v1 sync protocol documents from docs/remote-sync-protocol.md.
 * Parsing walks JSON trees explicitly so unknown fields are ignored and every required field is
 * checked, instead of relying on reflection-based binding.
 */
@Singleton
public class BankTagSyncJson
{
	public static final int SCHEMA_VERSION = SharedBankTag.SCHEMA_VERSION;

	private final Gson gson;

	@Inject
	public BankTagSyncJson(Gson gson)
	{
		// Request bodies must carry an explicit "layout": null, which Gson drops unless nulls are serialized.
		this.gson = gson.newBuilder().serializeNulls().create();
	}

	/**
	 * Thrown when a document declares a schemaVersion this client does not understand.
	 */
	public static class UnsupportedSchemaException extends Exception
	{
		private final int schemaVersion;

		public UnsupportedSchemaException(int schemaVersion)
		{
			super("unsupported schemaVersion " + schemaVersion);
			this.schemaVersion = schemaVersion;
		}

		public int getSchemaVersion()
		{
			return schemaVersion;
		}
	}

	/**
	 * Thrown when a body is not valid JSON or is missing a required field.
	 */
	public static class InvalidDocumentException extends Exception
	{
		public InvalidDocumentException(String message)
		{
			super(message);
		}

		public InvalidDocumentException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	public SharedBankTag parseTag(String body) throws UnsupportedSchemaException, InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchemaVersion(object);
		return tagFromObject(object, true);
	}

	public BankTagManifest parseManifest(String body) throws UnsupportedSchemaException, InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchemaVersion(object);
		return manifestFromObject(object);
	}

	/**
	 * Builds a failure for a non-success status. Non-JSON bodies (for example actix's own plain-text
	 * {@code 413} and {@code 428} responses) yield a failure whose kind comes from the status alone.
	 *
	 * @param orderRoute whether the {@code current} field, if present, is a manifest rather than a tag
	 */
	public SyncFailure parseErrorBody(int status, @Nullable String body, boolean orderRoute)
	{
		SyncFailure.Kind kind = SyncFailure.kindForStatus(status);
		JsonObject object;
		try
		{
			object = parseObject(body);
		}
		catch (InvalidDocumentException ex)
		{
			return new SyncFailure(kind, status, null, null, null, null);
		}

		String errorCode = optionalString(object, "error");
		String message = optionalString(object, "message");
		SharedBankTag currentTag = null;
		BankTagManifest currentManifest = null;
		JsonElement current = object.get("current");
		if (current != null && current.isJsonObject())
		{
			try
			{
				if (orderRoute)
				{
					currentManifest = manifestFromObject(current.getAsJsonObject());
				}
				else
				{
					currentTag = tagFromObject(current.getAsJsonObject(), false);
				}
			}
			catch (InvalidDocumentException ex)
			{
				// A malformed "current" must not hide the error code itself.
			}
		}
		return new SyncFailure(kind, status, errorCode, message, currentTag, currentManifest);
	}

	/**
	 * Serializes exactly the client-owned fields of a tag document:
	 * {@code schemaVersion, name, iconItemId, itemIds, layout}. Server-managed fields
	 * ({@code tagId, revision, deleted, updatedAt}) are never sent.
	 */
	public String tagRequestBody(SharedBankTag tag)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SCHEMA_VERSION);
		object.addProperty("name", tag.getName());
		object.addProperty("iconItemId", tag.getIconItemId());
		JsonArray itemIds = new JsonArray();
		for (int itemId : tag.getItemIds())
		{
			itemIds.add(itemId);
		}
		object.add("itemIds", itemIds);
		int[] layout = tag.getLayout();
		if (layout == null)
		{
			object.add("layout", null);
		}
		else
		{
			JsonArray layoutArray = new JsonArray();
			for (int itemId : layout)
			{
				layoutArray.add(itemId);
			}
			object.add("layout", layoutArray);
		}
		return gson.toJson(object);
	}

	public String orderRequestBody(List<String> orderedTagIds)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SCHEMA_VERSION);
		JsonArray ids = new JsonArray();
		for (String tagId : orderedTagIds)
		{
			ids.add(tagId);
		}
		object.add("orderedTagIds", ids);
		return gson.toJson(object);
	}

	private JsonObject parseObject(@Nullable String body) throws InvalidDocumentException
	{
		if (body == null || body.trim().isEmpty())
		{
			throw new InvalidDocumentException("empty body");
		}
		JsonElement element;
		try
		{
			element = gson.fromJson(body, JsonElement.class);
		}
		catch (JsonParseException ex)
		{
			throw new InvalidDocumentException("body is not valid JSON", ex);
		}
		if (element == null || !element.isJsonObject())
		{
			throw new InvalidDocumentException("body is not a JSON object");
		}
		return element.getAsJsonObject();
	}

	private static void checkSchemaVersion(JsonObject object) throws UnsupportedSchemaException, InvalidDocumentException
	{
		int schemaVersion = requiredInt(object, "schemaVersion");
		if (schemaVersion != SCHEMA_VERSION)
		{
			throw new UnsupportedSchemaException(schemaVersion);
		}
	}

	/**
	 * @param full whether item fields are required; the {@code current} of a 409 only carries
	 *             {@code tagId, name, revision, deleted}
	 */
	private static SharedBankTag tagFromObject(JsonObject object, boolean full) throws InvalidDocumentException
	{
		String tagId = requiredString(object, "tagId");
		String name = requiredString(object, "name");
		long revision = requiredLong(object, "revision");
		boolean deleted = requiredBoolean(object, "deleted");

		int iconItemId = 0;
		List<Integer> itemIds = new ArrayList<>();
		int[] layout = null;
		if (full)
		{
			iconItemId = requiredInt(object, "iconItemId");
			for (int itemId : intArray(requiredArray(object, "itemIds"), "itemIds"))
			{
				itemIds.add(itemId);
			}
			JsonElement layoutElement = object.get("layout");
			if (layoutElement != null && !layoutElement.isJsonNull())
			{
				if (!layoutElement.isJsonArray())
				{
					throw new InvalidDocumentException("layout must be an array or null");
				}
				layout = intArray(layoutElement.getAsJsonArray(), "layout");
			}
		}
		else
		{
			JsonElement icon = object.get("iconItemId");
			if (icon != null && icon.isJsonPrimitive() && icon.getAsJsonPrimitive().isNumber())
			{
				iconItemId = icon.getAsInt();
			}
		}
		return new SharedBankTag(tagId, name, iconItemId, itemIds, layout, revision, deleted);
	}

	private static BankTagManifest manifestFromObject(JsonObject object) throws InvalidDocumentException
	{
		long groupRevision = requiredLong(object, "groupRevision");
		long orderRevision = requiredLong(object, "orderRevision");

		List<String> orderedTagIds = new ArrayList<>();
		for (JsonElement element : requiredArray(object, "orderedTagIds"))
		{
			if (!isString(element))
			{
				throw new InvalidDocumentException("orderedTagIds must contain strings");
			}
			orderedTagIds.add(element.getAsString());
		}

		List<BankTagManifest.Entry> tags = new ArrayList<>();
		for (JsonElement element : requiredArray(object, "tags"))
		{
			if (!element.isJsonObject())
			{
				throw new InvalidDocumentException("tags must contain objects");
			}
			JsonObject entry = element.getAsJsonObject();
			tags.add(new BankTagManifest.Entry(
				requiredString(entry, "tagId"),
				requiredString(entry, "name"),
				requiredLong(entry, "revision"),
				requiredBoolean(entry, "deleted")));
		}

		int schemaVersion = object.has("schemaVersion") && isNumber(object.get("schemaVersion"))
			? object.get("schemaVersion").getAsInt()
			: SCHEMA_VERSION;
		return new BankTagManifest(schemaVersion, groupRevision, orderRevision, orderedTagIds, tags);
	}

	private static int[] intArray(JsonArray array, String field) throws InvalidDocumentException
	{
		int[] values = new int[array.size()];
		for (int i = 0; i < values.length; i++)
		{
			JsonElement element = array.get(i);
			if (!isNumber(element))
			{
				throw new InvalidDocumentException(field + " must contain integers");
			}
			values[i] = element.getAsInt();
		}
		return values;
	}

	private static JsonElement required(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = object.get(field);
		if (element == null || element.isJsonNull())
		{
			throw new InvalidDocumentException("missing field " + field);
		}
		return element;
	}

	private static String requiredString(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = required(object, field);
		if (!isString(element))
		{
			throw new InvalidDocumentException(field + " must be a string");
		}
		return element.getAsString();
	}

	private static int requiredInt(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = required(object, field);
		if (!isNumber(element))
		{
			throw new InvalidDocumentException(field + " must be a number");
		}
		return element.getAsInt();
	}

	private static long requiredLong(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = required(object, field);
		if (!isNumber(element))
		{
			throw new InvalidDocumentException(field + " must be a number");
		}
		return element.getAsLong();
	}

	private static boolean requiredBoolean(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = required(object, field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
		{
			throw new InvalidDocumentException(field + " must be a boolean");
		}
		return element.getAsBoolean();
	}

	private static JsonArray requiredArray(JsonObject object, String field) throws InvalidDocumentException
	{
		JsonElement element = required(object, field);
		if (!element.isJsonArray())
		{
			throw new InvalidDocumentException(field + " must be an array");
		}
		return element.getAsJsonArray();
	}

	@Nullable
	private static String optionalString(JsonObject object, String field)
	{
		JsonElement element = object.get(field);
		return isString(element) ? element.getAsString() : null;
	}

	private static boolean isString(@Nullable JsonElement element)
	{
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	private static boolean isNumber(@Nullable JsonElement element)
	{
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
	}
}
