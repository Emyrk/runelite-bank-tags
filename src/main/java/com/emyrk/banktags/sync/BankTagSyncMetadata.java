package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Local sync metadata (docs/remote-sync-protocol.md, "Client-side (plugin) local metadata").
 * <p>
 * Everything lives in {@link BankTagsStorage#SYNC_DATA_GROUP} under reserved {@code sync}-prefixed
 * keys, written directly through {@link ConfigManager}: metadata belongs to the synchronized
 * namespace regardless of which repository is currently active. Nothing here touches the built-in
 * {@code banktags} group or the local {@code emyrk-bank-tags} data.
 */
@Slf4j
@Singleton
public class BankTagSyncMetadata
{
	static final String GROUP = BankTagsStorage.SYNC_DATA_GROUP;
	static final String KEY_PREFIX = "sync";
	static final String GROUP_REVISION_KEY = "syncGroupRevision";
	static final String ORDER_REVISION_KEY = "syncOrderRevision";
	static final String TAG_PREFIX = "syncTag_";
	static final String PENDING_DELETE_PREFIX = "syncPendingDelete_";
	static final String CONFLICT_PREFIX = "syncConflict_";

	public static final String REASON_STALE_REVISION = "stale_revision";
	public static final String REASON_REMOTE_CHANGED = "remote_changed";
	public static final String REASON_DUPLICATE_NAME = "duplicate_name";
	public static final String REASON_TAG_EXISTS = "tag_exists";

	/**
	 * Per-tag sync state: the current local standardized name, the last remote revision this client
	 * applied or received on write ({@code 0} = never created remotely), and the content hash of that
	 * synced state ({@code ""} when there is none).
	 */
	public static final class TagMeta
	{
		public String name;
		public long revision;
		public String baseHash;

		public TagMeta()
		{
		}

		public TagMeta(String name, long revision, String baseHash)
		{
			this.name = Text.standardize(name);
			this.revision = revision;
			this.baseHash = baseHash == null ? "" : baseHash;
		}

		@Override
		public String toString()
		{
			return "TagMeta{name=" + name + ", revision=" + revision + '}';
		}
	}

	/**
	 * A recorded conflict: the remote document at the time the conflict was detected and why.
	 */
	public static final class Conflict
	{
		public final SharedBankTag remote;
		public final String reason;

		public Conflict(SharedBankTag remote, String reason)
		{
			this.remote = remote;
			this.reason = reason;
		}

		@Override
		public String toString()
		{
			return "Conflict{reason=" + reason + ", remoteRevision=" + (remote == null ? "-" : remote.getRevision()) + '}';
		}
	}

	private static final class PendingDelete
	{
		long revision;
	}

	private final ConfigManager configManager;
	private final Gson gson;
	private final BankTagSyncJson json;

	@Inject
	BankTagSyncMetadata(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.json = new BankTagSyncJson(gson);
	}

	public long groupRevision()
	{
		return readLong(GROUP_REVISION_KEY);
	}

	public void setGroupRevision(long value)
	{
		configManager.setConfiguration(GROUP, GROUP_REVISION_KEY, Long.toString(value));
	}

	public long orderRevision()
	{
		return readLong(ORDER_REVISION_KEY);
	}

	public void setOrderRevision(long value)
	{
		configManager.setConfiguration(GROUP, ORDER_REVISION_KEY, Long.toString(value));
	}

	@Nullable
	public TagMeta tag(String tagId)
	{
		String value = configManager.getConfiguration(GROUP, TAG_PREFIX + tagId);
		if (value == null)
		{
			return null;
		}
		try
		{
			TagMeta meta = gson.fromJson(value, TagMeta.class);
			if (meta == null || meta.name == null)
			{
				return null;
			}
			if (meta.baseHash == null)
			{
				meta.baseHash = "";
			}
			return meta;
		}
		catch (JsonSyntaxException ex)
		{
			log.debug("bank tag sync: unreadable tag metadata for {}", tagId);
			return null;
		}
	}

	public void putTag(String tagId, TagMeta meta)
	{
		configManager.setConfiguration(GROUP, TAG_PREFIX + tagId, gson.toJson(meta));
	}

	public void removeTag(String tagId)
	{
		configManager.unsetConfiguration(GROUP, TAG_PREFIX + tagId);
	}

	public Map<String, TagMeta> allTags()
	{
		Map<String, TagMeta> result = new LinkedHashMap<>();
		for (String tagId : idsWithPrefix(TAG_PREFIX))
		{
			TagMeta meta = tag(tagId);
			if (meta != null)
			{
				result.put(tagId, meta);
			}
		}
		return result;
	}

	@Nullable
	public String tagIdForName(String standardizedName)
	{
		String name = Text.standardize(standardizedName);
		for (Map.Entry<String, TagMeta> entry : allTags().entrySet())
		{
			if (name.equals(entry.getValue().name))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	@Nullable
	public Long pendingDelete(String tagId)
	{
		String value = configManager.getConfiguration(GROUP, PENDING_DELETE_PREFIX + tagId);
		if (value == null)
		{
			return null;
		}
		try
		{
			PendingDelete pending = gson.fromJson(value, PendingDelete.class);
			return pending == null ? null : pending.revision;
		}
		catch (JsonSyntaxException ex)
		{
			return null;
		}
	}

	public void putPendingDelete(String tagId, long revision)
	{
		PendingDelete pending = new PendingDelete();
		pending.revision = revision;
		configManager.setConfiguration(GROUP, PENDING_DELETE_PREFIX + tagId, gson.toJson(pending));
	}

	public void removePendingDelete(String tagId)
	{
		configManager.unsetConfiguration(GROUP, PENDING_DELETE_PREFIX + tagId);
	}

	public Map<String, Long> allPendingDeletes()
	{
		Map<String, Long> result = new LinkedHashMap<>();
		for (String tagId : idsWithPrefix(PENDING_DELETE_PREFIX))
		{
			Long revision = pendingDelete(tagId);
			if (revision != null)
			{
				result.put(tagId, revision);
			}
		}
		return result;
	}

	@Nullable
	public Conflict conflict(String tagId)
	{
		String value = configManager.getConfiguration(GROUP, CONFLICT_PREFIX + tagId);
		if (value == null)
		{
			return null;
		}
		try
		{
			JsonObject object = gson.fromJson(value, JsonObject.class);
			if (object == null || !object.has("remote") || !object.has("reason"))
			{
				return null;
			}
			SharedBankTag remote = json.parseTag(gson.toJson(object.get("remote")));
			return new Conflict(remote, object.get("reason").getAsString());
		}
		catch (JsonSyntaxException | BankTagSyncJson.UnsupportedSchemaException
			| BankTagSyncJson.InvalidDocumentException | IllegalStateException ex)
		{
			log.debug("bank tag sync: unreadable conflict record for {}", tagId);
			return null;
		}
	}

	public void putConflict(String tagId, Conflict conflict)
	{
		JsonObject object = new JsonObject();
		object.add("remote", gson.fromJson(json.tagDocument(conflict.remote), JsonObject.class));
		object.addProperty("reason", conflict.reason);
		configManager.setConfiguration(GROUP, CONFLICT_PREFIX + tagId, gson.toJson(object));
	}

	public void removeConflict(String tagId)
	{
		configManager.unsetConfiguration(GROUP, CONFLICT_PREFIX + tagId);
	}

	public Map<String, Conflict> allConflicts()
	{
		Map<String, Conflict> result = new LinkedHashMap<>();
		for (String tagId : idsWithPrefix(CONFLICT_PREFIX))
		{
			Conflict conflict = conflict(tagId);
			if (conflict != null)
			{
				result.put(tagId, conflict);
			}
		}
		return result;
	}

	/**
	 * Removes every {@code sync}-prefixed key from the synchronized namespace, including the
	 * {@code syncStorageInitialized} marker. Tag data keys are left alone.
	 */
	public void clearAll()
	{
		String prefix = GROUP + "." + KEY_PREFIX;
		for (String fullKey : configManager.getConfigurationKeys(prefix))
		{
			String[] parts = fullKey.split("\\.", 2);
			if (parts.length == 2)
			{
				configManager.unsetConfiguration(parts[0], parts[1]);
			}
		}
	}

	private long readLong(String key)
	{
		String value = configManager.getConfiguration(GROUP, key);
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private List<String> idsWithPrefix(String keyPrefix)
	{
		String fullPrefix = GROUP + "." + keyPrefix;
		List<String> keys = configManager.getConfigurationKeys(fullPrefix);
		List<String> ids = new java.util.ArrayList<>(keys.size());
		for (String key : keys)
		{
			if (key.startsWith(fullPrefix))
			{
				ids.add(key.substring(fullPrefix.length()));
			}
		}
		return ids;
	}
}
