package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsStorage;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Revision and hash cache for the separate folder protocol. */
@Singleton
public class BankTagFolderSyncMetadata
{
	static final String GROUP_REVISION_KEY = "syncFolderGroupRevision";
	static final String ORDER_REVISION_KEY = "syncFolderOrderRevision";
	static final String META_PREFIX = "syncFolderMeta_";
	static final String PENDING_DELETE_PREFIX = "syncFolderPendingDelete_";

	public static final class FolderMeta
	{
		public String name;
		public long revision;
		public String baseHash;

		public FolderMeta()
		{
		}

		public FolderMeta(String name, long revision, String baseHash)
		{
			this.name = name == null ? "" : name.trim();
			this.revision = revision;
			this.baseHash = baseHash == null ? "" : baseHash;
		}
	}

	private static final class PendingDelete
	{
		long revision;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	BankTagFolderSyncMetadata(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public long groupRevision() { return readLong(GROUP_REVISION_KEY); }
	public void setGroupRevision(long revision) { set(GROUP_REVISION_KEY, Long.toString(revision)); }
	public long orderRevision() { return readLong(ORDER_REVISION_KEY); }
	public void setOrderRevision(long revision) { set(ORDER_REVISION_KEY, Long.toString(revision)); }

	@Nullable
	public FolderMeta folder(String folderId)
	{
		String value = get(META_PREFIX + folderId);
		if (value == null)
		{
			return null;
		}
		try
		{
			FolderMeta meta = gson.fromJson(value, FolderMeta.class);
			if (meta == null || meta.name == null)
			{
				return null;
			}
			if (meta.baseHash == null) meta.baseHash = "";
			return meta;
		}
		catch (JsonSyntaxException ex)
		{
			return null;
		}
	}

	public void putFolder(String folderId, FolderMeta meta) { set(META_PREFIX + folderId, gson.toJson(meta)); }
	public void removeFolder(String folderId) { unset(META_PREFIX + folderId); }

	public Map<String, FolderMeta> allFolders()
	{
		Map<String, FolderMeta> result = new LinkedHashMap<>();
		for (String id : ids(META_PREFIX))
		{
			FolderMeta meta = folder(id);
			if (meta != null) result.put(id, meta);
		}
		return result;
	}

	@Nullable
	public Long pendingDelete(String folderId)
	{
		String value = get(PENDING_DELETE_PREFIX + folderId);
		if (value == null) return null;
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

	public void putPendingDelete(String folderId, long revision)
	{
		PendingDelete pending = new PendingDelete();
		pending.revision = revision;
		set(PENDING_DELETE_PREFIX + folderId, gson.toJson(pending));
	}

	public void removePendingDelete(String folderId) { unset(PENDING_DELETE_PREFIX + folderId); }

	public Map<String, Long> allPendingDeletes()
	{
		Map<String, Long> result = new LinkedHashMap<>();
		for (String id : ids(PENDING_DELETE_PREFIX))
		{
			Long revision = pendingDelete(id);
			if (revision != null) result.put(id, revision);
		}
		return result;
	}

	private long readLong(String key)
	{
		String value = get(key);
		if (value == null) return 0;
		try
		{
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private List<String> ids(String prefix)
	{
		String fullPrefix = BankTagsStorage.SYNC_DATA_GROUP + "." + prefix;
		List<String> result = new ArrayList<>();
		for (String key : configManager.getConfigurationKeys(fullPrefix))
		{
			if (key.startsWith(fullPrefix)) result.add(key.substring(fullPrefix.length()));
		}
		return result;
	}

	@Nullable private String get(String key) { return configManager.getConfiguration(BankTagsStorage.SYNC_DATA_GROUP, key); }
	private void set(String key, Object value) { configManager.setConfiguration(BankTagsStorage.SYNC_DATA_GROUP, key, value); }
	private void unset(String key) { configManager.unsetConfiguration(BankTagsStorage.SYNC_DATA_GROUP, key); }
}
