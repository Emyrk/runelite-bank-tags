package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.InventorySetupSyncFailure;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.emyrk.banktags.sync.BankTagSyncJson.InvalidDocumentException;
import com.emyrk.banktags.sync.BankTagSyncJson.UnsupportedSchemaException;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Explicit JSON codec for the Inventory Setups v1 protocol. */
@Singleton
public class InventorySetupSyncJson
{
	public enum CurrentKind
	{
		NONE,
		SETUP,
		SECTION,
		MANIFEST
	}

	private final Gson gson;

	@Inject
	public InventorySetupSyncJson(Gson gson)
	{
		this.gson = gson.newBuilder().serializeNulls().create();
	}

	public SharedInventorySetup parseSetup(String body)
		throws UnsupportedSchemaException, InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchema(object);
		JsonElement payload = required(object, "payload");
		if (!payload.isJsonObject())
		{
			throw new InvalidDocumentException("payload must be an object");
		}
		return new SharedInventorySetup(requiredId(object, "setupId"), requiredString(object, "name"),
			requiredString(object, "notes"), payload.getAsJsonObject(), requiredLong(object, "revision"),
			requiredBoolean(object, "deleted"));
	}

	public SharedInventorySetupSection parseSection(String body)
		throws UnsupportedSchemaException, InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchema(object);
		return sectionDocument(object);
	}

	public InventorySetupManifest parseManifest(String body)
		throws UnsupportedSchemaException, InvalidDocumentException
	{
		JsonObject object = parseObject(body);
		checkSchema(object);
		return manifest(object);
	}

	public InventorySetupSyncFailure parseErrorBody(int status, @Nullable String body, CurrentKind currentKind)
	{
		SyncFailure.Kind kind = SyncFailure.kindForStatus(status);
		JsonObject object;
		try
		{
			object = parseObject(body);
		}
		catch (InvalidDocumentException ex)
		{
			return new InventorySetupSyncFailure(kind, status, null, null, null, null, null);
		}

		String error = optionalString(object, "error");
		String message = optionalString(object, "message");
		InventorySetupManifest.SetupEntry setup = null;
		InventorySetupManifest.SectionEntry section = null;
		InventorySetupManifest currentManifest = null;
		JsonElement current = object.get("current");
		if (current != null && current.isJsonObject())
		{
			try
			{
				switch (currentKind)
				{
					case SETUP:
						setup = setupEntry(current.getAsJsonObject());
						break;
					case SECTION:
						section = sectionEntry(current.getAsJsonObject());
						break;
					case MANIFEST:
						currentManifest = manifest(current.getAsJsonObject());
						break;
					default:
						break;
				}
			}
			catch (InvalidDocumentException ex)
			{
				// Preserve the useful error code even if a server supplied malformed current data.
			}
		}
		return new InventorySetupSyncFailure(kind, status, error, message, setup, section, currentManifest);
	}

	public String setupRequest(SharedInventorySetup setup)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedInventorySetup.SCHEMA_VERSION);
		object.addProperty("name", setup.getName());
		object.addProperty("notes", setup.getNotes());
		object.add("payload", setup.getPayload());
		return gson.toJson(object);
	}

	public String setupDocument(SharedInventorySetup setup)
	{
		JsonObject object = gson.fromJson(setupRequest(setup), JsonObject.class);
		object.addProperty("setupId", setup.getSetupId());
		object.addProperty("revision", setup.getRevision());
		object.addProperty("deleted", setup.isDeleted());
		return gson.toJson(object);
	}

	public String sectionRequest(SharedInventorySetupSection section)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedInventorySetup.SCHEMA_VERSION);
		object.addProperty("name", section.getName());
		if (section.getDisplayColor() == null)
		{
			object.add("displayColor", null);
		}
		else
		{
			object.addProperty("displayColor", section.getDisplayColor());
		}
		addIds(object, "orderedSetupIds", section.getOrderedSetupIds());
		return gson.toJson(object);
	}

	public String sectionDocument(SharedInventorySetupSection section)
	{
		JsonObject object = gson.fromJson(sectionRequest(section), JsonObject.class);
		object.addProperty("sectionId", section.getSectionId());
		object.addProperty("revision", section.getRevision());
		object.addProperty("deleted", section.isDeleted());
		return gson.toJson(object);
	}

	public String manifestDocument(InventorySetupManifest value)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedInventorySetup.SCHEMA_VERSION);
		object.addProperty("groupRevision", value.getGroupRevision());
		object.addProperty("setupOrderRevision", value.getSetupOrderRevision());
		object.addProperty("sectionOrderRevision", value.getSectionOrderRevision());
		addIds(object, "orderedSetupIds", value.getOrderedSetupIds());
		addIds(object, "orderedSectionIds", value.getOrderedSectionIds());
		JsonArray setups = new JsonArray();
		for (InventorySetupManifest.SetupEntry entry : value.getSetups())
		{
			setups.add(setupEntryObject(entry));
		}
		object.add("setups", setups);
		JsonArray sections = new JsonArray();
		for (InventorySetupManifest.SectionEntry entry : value.getSections())
		{
			sections.add(sectionEntryObject(entry));
		}
		object.add("sections", sections);
		return gson.toJson(object);
	}

	public String setupOrder(List<String> ids)
	{
		return order("orderedSetupIds", ids);
	}

	public String sectionOrder(List<String> ids)
	{
		return order("orderedSectionIds", ids);
	}

	private String order(String key, List<String> ids)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", SharedInventorySetup.SCHEMA_VERSION);
		addIds(object, key, ids);
		return gson.toJson(object);
	}

	private InventorySetupManifest manifest(JsonObject object) throws InvalidDocumentException
	{
		List<InventorySetupManifest.SetupEntry> setups = new ArrayList<>();
		Set<String> setupIds = new HashSet<>();
		Set<String> liveSetupIds = new HashSet<>();
		for (JsonElement element : requiredArray(object, "setups"))
		{
			if (!element.isJsonObject())
			{
				throw new InvalidDocumentException("setups must contain objects");
			}
			InventorySetupManifest.SetupEntry entry = setupEntry(element.getAsJsonObject());
			if (!setupIds.add(entry.getSetupId()))
			{
				throw new InvalidDocumentException("setups contains duplicate setupId");
			}
			if (!entry.isDeleted())
			{
				liveSetupIds.add(entry.getSetupId());
			}
			setups.add(entry);
		}

		List<InventorySetupManifest.SectionEntry> sections = new ArrayList<>();
		Set<String> sectionIds = new HashSet<>();
		Set<String> liveSectionIds = new HashSet<>();
		for (JsonElement element : requiredArray(object, "sections"))
		{
			if (!element.isJsonObject())
			{
				throw new InvalidDocumentException("sections must contain objects");
			}
			InventorySetupManifest.SectionEntry entry = sectionEntry(element.getAsJsonObject());
			if (!sectionIds.add(entry.getSectionId()))
			{
				throw new InvalidDocumentException("sections contains duplicate sectionId");
			}
			if (!entry.isDeleted())
			{
				liveSectionIds.add(entry.getSectionId());
			}
			sections.add(entry);
		}

		List<String> orderedSetupIds = requiredIds(object, "orderedSetupIds");
		List<String> orderedSectionIds = requiredIds(object, "orderedSectionIds");
		checkPermutation("orderedSetupIds", orderedSetupIds, liveSetupIds);
		checkPermutation("orderedSectionIds", orderedSectionIds, liveSectionIds);
		return new InventorySetupManifest(requiredLong(object, "groupRevision"),
			requiredLong(object, "setupOrderRevision"), requiredLong(object, "sectionOrderRevision"),
			orderedSetupIds, orderedSectionIds, setups, sections);
	}

	private static InventorySetupManifest.SetupEntry setupEntry(JsonObject object)
		throws InvalidDocumentException
	{
		return new InventorySetupManifest.SetupEntry(requiredId(object, "setupId"),
			requiredString(object, "name"), requiredLong(object, "revision"),
			requiredBoolean(object, "deleted"));
	}

	private static InventorySetupManifest.SectionEntry sectionEntry(JsonObject object)
		throws InvalidDocumentException
	{
		return new InventorySetupManifest.SectionEntry(requiredId(object, "sectionId"),
			requiredString(object, "name"), requiredLong(object, "revision"),
			requiredBoolean(object, "deleted"));
	}

	private static SharedInventorySetupSection sectionDocument(JsonObject object)
		throws InvalidDocumentException
	{
		Integer displayColor = null;
		JsonElement color = object.get("displayColor");
		if (color == null)
		{
			throw new InvalidDocumentException("missing field displayColor");
		}
		if (!color.isJsonNull())
		{
			if (!color.isJsonPrimitive() || !color.getAsJsonPrimitive().isNumber())
			{
				throw new InvalidDocumentException("displayColor must be integer or null");
			}
			try
			{
				displayColor = color.getAsBigDecimal().intValueExact();
			}
			catch (ArithmeticException ex)
			{
				throw new InvalidDocumentException("displayColor must be integer or null", ex);
			}
		}
		return new SharedInventorySetupSection(requiredId(object, "sectionId"),
			requiredString(object, "name"), displayColor, requiredIds(object, "orderedSetupIds"),
			requiredLong(object, "revision"), requiredBoolean(object, "deleted"));
	}

	private static void checkSchema(JsonObject object)
		throws UnsupportedSchemaException, InvalidDocumentException
	{
		long version = requiredLong(object, "schemaVersion");
		if (version != SharedInventorySetup.SCHEMA_VERSION)
		{
			throw new UnsupportedSchemaException(version > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) version);
		}
	}

	private JsonObject parseObject(@Nullable String body) throws InvalidDocumentException
	{
		if (body == null || body.trim().isEmpty())
		{
			throw new InvalidDocumentException("empty body");
		}
		try
		{
			JsonElement element = gson.fromJson(body, JsonElement.class);
			if (element == null || !element.isJsonObject())
			{
				throw new InvalidDocumentException("body must be object");
			}
			return element.getAsJsonObject();
		}
		catch (JsonParseException ex)
		{
			throw new InvalidDocumentException("invalid json", ex);
		}
	}

	private static JsonElement required(JsonObject object, String key) throws InvalidDocumentException
	{
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull())
		{
			throw new InvalidDocumentException("missing field " + key);
		}
		return element;
	}

	private static String requiredString(JsonObject object, String key) throws InvalidDocumentException
	{
		JsonElement element = required(object, key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
		{
			throw new InvalidDocumentException(key + " must be string");
		}
		return element.getAsString();
	}

	private static String requiredId(JsonObject object, String key) throws InvalidDocumentException
	{
		String id = requiredString(object, key);
		if (InventorySetupIds.normalize(id) == null)
		{
			throw new InvalidDocumentException(key + " must be lowercase UUIDv4");
		}
		return id;
	}

	private static long requiredLong(JsonObject object, String key) throws InvalidDocumentException
	{
		JsonElement element = required(object, key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
		{
			throw new InvalidDocumentException(key + " must be a nonnegative integer");
		}
		try
		{
			BigDecimal value = element.getAsBigDecimal();
			long result = value.longValueExact();
			if (result < 0)
			{
				throw new ArithmeticException();
			}
			return result;
		}
		catch (ArithmeticException ex)
		{
			throw new InvalidDocumentException(key + " must be a nonnegative integer", ex);
		}
	}

	private static boolean requiredBoolean(JsonObject object, String key) throws InvalidDocumentException
	{
		JsonElement element = required(object, key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
		{
			throw new InvalidDocumentException(key + " must be boolean");
		}
		return element.getAsBoolean();
	}

	private static JsonArray requiredArray(JsonObject object, String key) throws InvalidDocumentException
	{
		JsonElement element = required(object, key);
		if (!element.isJsonArray())
		{
			throw new InvalidDocumentException(key + " must be array");
		}
		return element.getAsJsonArray();
	}

	private static List<String> requiredIds(JsonObject object, String key) throws InvalidDocumentException
	{
		List<String> result = new ArrayList<>();
		Set<String> unique = new HashSet<>();
		for (JsonElement element : requiredArray(object, key))
		{
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
				|| InventorySetupIds.normalize(element.getAsString()) == null)
			{
				throw new InvalidDocumentException(key + " contains invalid id");
			}
			String id = element.getAsString();
			if (!unique.add(id))
			{
				throw new InvalidDocumentException(key + " contains duplicate id");
			}
			result.add(id);
		}
		return result;
	}

	private static void checkPermutation(String field, List<String> order, Set<String> liveIds)
		throws InvalidDocumentException
	{
		if (order.size() != liveIds.size() || !liveIds.equals(new HashSet<>(order)))
		{
			throw new InvalidDocumentException(field + " must contain every live id exactly once");
		}
	}

	@Nullable
	private static String optionalString(JsonObject object, String key)
	{
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? element.getAsString() : null;
	}

	private static void addIds(JsonObject object, String key, List<String> ids)
	{
		JsonArray array = new JsonArray();
		for (String id : ids)
		{
			array.add(id);
		}
		object.add(key, array);
	}

	private static JsonObject setupEntryObject(InventorySetupManifest.SetupEntry entry)
	{
		JsonObject object = new JsonObject();
		object.addProperty("setupId", entry.getSetupId());
		object.addProperty("name", entry.getName());
		object.addProperty("revision", entry.getRevision());
		object.addProperty("deleted", entry.isDeleted());
		return object;
	}

	private static JsonObject sectionEntryObject(InventorySetupManifest.SectionEntry entry)
	{
		JsonObject object = new JsonObject();
		object.addProperty("sectionId", entry.getSectionId());
		object.addProperty("name", entry.getName());
		object.addProperty("revision", entry.getRevision());
		object.addProperty("deleted", entry.isDeleted());
		return object;
	}
}
