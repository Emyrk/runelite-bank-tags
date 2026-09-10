package com.emyrk.banktags.sync;

import com.emyrk.banktags.sync.model.BankTagFolderManifest;
import com.emyrk.banktags.sync.model.SharedBankTagFolder;
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

/** JSON codec for the separate folder v1 extension. Tag v1 encoding remains untouched. */
@Singleton
public class BankTagFolderSyncJson
{
	private final Gson gson;

	@Inject
	BankTagFolderSyncJson(Gson gson)
	{
		this.gson = gson.newBuilder().serializeNulls().create();
	}

	public SharedBankTagFolder parseFolder(String body)
		throws BankTagSyncJson.UnsupportedSchemaException, BankTagSyncJson.InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchema(object);
		return folderFromObject(object, true);
	}

	public BankTagFolderManifest parseManifest(String body)
		throws BankTagSyncJson.UnsupportedSchemaException, BankTagSyncJson.InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchema(object);
		return manifestFromObject(object);
	}

	public String folderRequestBody(SharedBankTagFolder folder)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedBankTagFolder.SCHEMA_VERSION);
		object.addProperty("name", folder.getName());
		object.addProperty("iconItemId", folder.getIconItemId());
		JsonArray tagIds = new JsonArray();
		for (String tagId : folder.getOrderedTagIds())
		{
			tagIds.add(tagId);
		}
		object.add("orderedTagIds", tagIds);
		return gson.toJson(object);
	}

	public String folderOrderRequestBody(List<String> orderedFolderIds)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedBankTagFolder.SCHEMA_VERSION);
		JsonArray ids = new JsonArray();
		for (String id : orderedFolderIds)
		{
			ids.add(id);
		}
		object.add("orderedFolderIds", ids);
		return gson.toJson(object);
	}

	public SyncFailure parseErrorBody(int status, @Nullable String body, boolean orderRoute)
	{
		SyncFailure.Kind kind = SyncFailure.kindForStatus(status);
		try
		{
			JsonObject object = parseObject(body);
			String code = optionalString(object, "error");
			String message = optionalString(object, "message");
			SharedBankTagFolder currentFolder = null;
			BankTagFolderManifest currentManifest = null;
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
						currentFolder = folderFromObject(current.getAsJsonObject(), false);
					}
				}
				catch (BankTagSyncJson.InvalidDocumentException ignored)
				{
					// Preserve the failure class even if its optional current document is malformed.
				}
			}
			return new SyncFailure(kind, status, code, message, null, null, currentFolder, currentManifest);
		}
		catch (BankTagSyncJson.InvalidDocumentException ex)
		{
			return new SyncFailure(kind, status, null, null, null, null, null, null);
		}
	}

	private SharedBankTagFolder folderFromObject(JsonObject object, boolean full)
		throws BankTagSyncJson.InvalidDocumentException
	{
		String folderId = requiredString(object, "folderId");
		String name = requiredString(object, "name");
		int iconItemId = full ? requiredInt(object, "iconItemId") : 0;
		long revision = requiredLong(object, "revision");
		boolean deleted = requiredBoolean(object, "deleted");
		String updatedAt = full ? requiredString(object, "updatedAt") : null;
		List<String> tagIds = new ArrayList<>();
		if (full)
		{
			for (JsonElement element : requiredArray(object, "orderedTagIds"))
			{
				if (!isString(element))
				{
					throw new BankTagSyncJson.InvalidDocumentException("orderedTagIds must contain strings");
				}
				tagIds.add(element.getAsString());
			}
		}
		return new SharedBankTagFolder(folderId, name, iconItemId, tagIds, revision, deleted, updatedAt);
	}

	private BankTagFolderManifest manifestFromObject(JsonObject object)
		throws BankTagSyncJson.InvalidDocumentException
	{
		long groupRevision = requiredLong(object, "groupRevision");
		long orderRevision = requiredLong(object, "orderRevision");
		List<String> ordered = new ArrayList<>();
		for (JsonElement element : requiredArray(object, "orderedFolderIds"))
		{
			if (!isString(element))
			{
				throw new BankTagSyncJson.InvalidDocumentException("orderedFolderIds must contain strings");
			}
			ordered.add(element.getAsString());
		}
		List<BankTagFolderManifest.Entry> folders = new ArrayList<>();
		for (JsonElement element : requiredArray(object, "folders"))
		{
			if (!element.isJsonObject())
			{
				throw new BankTagSyncJson.InvalidDocumentException("folders must contain objects");
			}
			JsonObject entry = element.getAsJsonObject();
			folders.add(new BankTagFolderManifest.Entry(requiredString(entry, "folderId"),
				requiredString(entry, "name"), requiredLong(entry, "revision"), requiredBoolean(entry, "deleted")));
		}
		return new BankTagFolderManifest(SharedBankTagFolder.SCHEMA_VERSION, groupRevision, orderRevision, ordered, folders);
	}

	private JsonObject parseObject(@Nullable String body) throws BankTagSyncJson.InvalidDocumentException
	{
		if (body == null || body.trim().isEmpty())
		{
			throw new BankTagSyncJson.InvalidDocumentException("empty body");
		}
		try
		{
			JsonElement element = gson.fromJson(body, JsonElement.class);
			if (element == null || !element.isJsonObject())
			{
				throw new BankTagSyncJson.InvalidDocumentException("body is not a JSON object");
			}
			return element.getAsJsonObject();
		}
		catch (JsonParseException ex)
		{
			throw new BankTagSyncJson.InvalidDocumentException("body is not valid JSON", ex);
		}
	}

	private static void checkSchema(JsonObject object)
		throws BankTagSyncJson.UnsupportedSchemaException, BankTagSyncJson.InvalidDocumentException
	{
		int version = requiredInt(object, "schemaVersion");
		if (version != SharedBankTagFolder.SCHEMA_VERSION)
		{
			throw new BankTagSyncJson.UnsupportedSchemaException(version);
		}
	}

	private static JsonElement required(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull())
		{
			throw new BankTagSyncJson.InvalidDocumentException("missing field " + field);
		}
		return value;
	}

	private static String requiredString(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = required(object, field);
		if (!isString(value))
		{
			throw new BankTagSyncJson.InvalidDocumentException(field + " must be a string");
		}
		return value.getAsString();
	}

	private static int requiredInt(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = required(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			throw new BankTagSyncJson.InvalidDocumentException(field + " must be a number");
		}
		return value.getAsInt();
	}

	private static long requiredLong(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		return requiredIntLike(object, field).getAsLong();
	}

	private static JsonElement requiredIntLike(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = required(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			throw new BankTagSyncJson.InvalidDocumentException(field + " must be a number");
		}
		return value;
	}

	private static boolean requiredBoolean(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = required(object, field);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
		{
			throw new BankTagSyncJson.InvalidDocumentException(field + " must be a boolean");
		}
		return value.getAsBoolean();
	}

	private static JsonArray requiredArray(JsonObject object, String field) throws BankTagSyncJson.InvalidDocumentException
	{
		JsonElement value = required(object, field);
		if (!value.isJsonArray())
		{
			throw new BankTagSyncJson.InvalidDocumentException(field + " must be an array");
		}
		return value.getAsJsonArray();
	}

	@Nullable
	private static String optionalString(JsonObject object, String field)
	{
		JsonElement value = object.get(field);
		return isString(value) ? value.getAsString() : null;
	}

	private static boolean isString(@Nullable JsonElement value)
	{
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
	}
}
