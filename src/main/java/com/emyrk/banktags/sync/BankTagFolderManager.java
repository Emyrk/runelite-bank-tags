package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.sync.model.SharedBankTagFolder;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/** Local synchronized-folder domain and persistence boundary. */
@Singleton
public class BankTagFolderManager
{
	static final String ORDER_KEY = "folderOrder";
	static final String FOLDER_PREFIX = "folder_";

	private static final class Record
	{
		String name;
		int iconItemId;
		List<String> orderedTagIds;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	BankTagFolderManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public SharedBankTagFolder create(String name)
	{
		String id = UUID.randomUUID().toString();
		SharedBankTagFolder folder = new SharedBankTagFolder(id, name, 0, Collections.emptyList(), 0, false, null);
		apply(folder);
		return folder;
	}

	public List<SharedBankTagFolder> folders()
	{
		List<SharedBankTagFolder> result = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String id : Text.fromCSV(value(ORDER_KEY)))
		{
			SharedBankTagFolder folder = get(id);
			if (folder != null && seen.add(id))
			{
				result.add(folder);
			}
		}
		for (String fullKey : configManager.getConfigurationKeys(BankTagsStorage.SYNC_DATA_GROUP + "." + FOLDER_PREFIX))
		{
			String id = fullKey.substring((BankTagsStorage.SYNC_DATA_GROUP + "." + FOLDER_PREFIX).length());
			SharedBankTagFolder folder = get(id);
			if (folder != null && seen.add(id))
			{
				result.add(folder);
			}
		}
		return result;
	}

	@Nullable
	public SharedBankTagFolder get(String folderId)
	{
		String value = configManager.getConfiguration(BankTagsStorage.SYNC_DATA_GROUP, FOLDER_PREFIX + folderId);
		if (value == null)
		{
			return null;
		}
		try
		{
			Record record = gson.fromJson(value, Record.class);
			if (record == null || record.name == null)
			{
				return null;
			}
			return new SharedBankTagFolder(folderId, record.name, record.iconItemId,
				record.orderedTagIds == null ? Collections.emptyList() : record.orderedTagIds, 0, false, null);
		}
		catch (JsonSyntaxException | IllegalArgumentException ex)
		{
			return null;
		}
	}

	public void apply(SharedBankTagFolder folder)
	{
		if (folder.isDeleted())
		{
			delete(folder.getFolderId());
			return;
		}
		Record record = new Record();
		record.name = folder.getName();
		record.iconItemId = folder.getIconItemId();
		record.orderedTagIds = new ArrayList<>(folder.getOrderedTagIds());
		configManager.setConfiguration(BankTagsStorage.SYNC_DATA_GROUP, FOLDER_PREFIX + folder.getFolderId(), gson.toJson(record));
		List<String> order = folderIds();
		if (!order.contains(folder.getFolderId()))
		{
			order.add(folder.getFolderId());
			saveOrder(order);
		}
	}

	public void rename(String folderId, String name)
	{
		SharedBankTagFolder folder = require(folderId);
		apply(new SharedBankTagFolder(folderId, name, folder.getIconItemId(), folder.getOrderedTagIds(), 0, false, null));
	}

	public void setIcon(String folderId, int iconItemId)
	{
		SharedBankTagFolder folder = require(folderId);
		apply(new SharedBankTagFolder(folderId, folder.getName(), iconItemId,
			folder.getOrderedTagIds(), 0, false, null));
	}

	/** Deleting a folder removes only its record. Its tags consequently become unfiled. */
	public void delete(String folderId)
	{
		List<String> order = new ArrayList<>(Text.fromCSV(value(ORDER_KEY)));
		configManager.unsetConfiguration(BankTagsStorage.SYNC_DATA_GROUP, FOLDER_PREFIX + folderId);
		if (order.remove(folderId))
		{
			saveOrder(order);
		}
	}

	/** Moves a tag into exactly one folder and returns every folder whose document changed. */
	public Set<String> moveTag(String tagId, @Nullable String destinationFolderId)
	{
		return moveTag(tagId, destinationFolderId, null, true);
	}

	/**
	 * Moves or reorders a tag relative to another child. With insert mode disabled, two children in
	 * the same folder swap positions. Cross-folder moves always insert at the destination position.
	 */
	public Set<String> moveTag(String tagId, @Nullable String destinationFolderId,
		@Nullable String destinationTagId, boolean insertMode)
	{
		if (destinationFolderId != null)
		{
			require(destinationFolderId);
		}
		Set<String> changed = new LinkedHashSet<>();
		for (SharedBankTagFolder folder : folders())
		{
			List<String> ids = new ArrayList<>(folder.getOrderedTagIds());
			boolean destination = folder.getFolderId().equals(destinationFolderId);
			boolean containsSource = ids.contains(tagId);
			boolean didChange = false;
			if (destination)
			{
				if (destinationTagId != null && ids.contains(destinationTagId)
					&& containsSource && !insertMode && !tagId.equals(destinationTagId))
				{
					int sourceIndex = ids.indexOf(tagId);
					int destinationIndex = ids.indexOf(destinationTagId);
					Collections.swap(ids, sourceIndex, destinationIndex);
					didChange = true;
				}
				else if (!tagId.equals(destinationTagId))
				{
					ids.remove(tagId);
					int destinationIndex = destinationTagId == null ? ids.size() : ids.indexOf(destinationTagId);
					if (destinationIndex < 0)
					{
						destinationIndex = ids.size();
					}
					ids.add(destinationIndex, tagId);
					didChange = !containsSource || !ids.equals(folder.getOrderedTagIds());
				}
			}
			else if (ids.remove(tagId))
			{
				didChange = true;
			}
			if (didChange)
			{
				apply(new SharedBankTagFolder(folder.getFolderId(), folder.getName(), folder.getIconItemId(), ids, 0, false, null));
				changed.add(folder.getFolderId());
			}
		}
		return changed;
	}

	@Nullable
	public String folderIdForTag(String tagId)
	{
		for (SharedBankTagFolder folder : folders())
		{
			if (folder.getOrderedTagIds().contains(tagId))
			{
				return folder.getFolderId();
			}
		}
		return null;
	}

	public List<String> unfiledTagIds(Collection<String> allTagIds)
	{
		Set<String> unfiled = new LinkedHashSet<>(allTagIds);
		for (SharedBankTagFolder folder : folders())
		{
			unfiled.removeAll(folder.getOrderedTagIds());
		}
		return new ArrayList<>(unfiled);
	}

	public void reorder(List<String> orderedFolderIds)
	{
		Set<String> known = new LinkedHashSet<>(folderIds());
		List<String> order = new ArrayList<>();
		for (String id : orderedFolderIds)
		{
			if (known.contains(id) && !order.contains(id))
			{
				order.add(id);
			}
		}
		for (String id : known)
		{
			if (!order.contains(id))
			{
				order.add(id);
			}
		}
		saveOrder(order);
	}

	public List<String> folderIds()
	{
		List<String> ids = new ArrayList<>();
		for (SharedBankTagFolder folder : foldersWithoutOrderRecursion())
		{
			ids.add(folder.getFolderId());
		}
		return ids;
	}

	private List<SharedBankTagFolder> foldersWithoutOrderRecursion()
	{
		List<SharedBankTagFolder> result = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String id : Text.fromCSV(value(ORDER_KEY)))
		{
			SharedBankTagFolder folder = get(id);
			if (folder != null && seen.add(id)) result.add(folder);
		}
		for (String fullKey : configManager.getConfigurationKeys(BankTagsStorage.SYNC_DATA_GROUP + "." + FOLDER_PREFIX))
		{
			String id = fullKey.substring((BankTagsStorage.SYNC_DATA_GROUP + "." + FOLDER_PREFIX).length());
			SharedBankTagFolder folder = get(id);
			if (folder != null && seen.add(id)) result.add(folder);
		}
		return result;
	}

	public boolean isCollapsed(String folderId)
	{
		return Boolean.TRUE.equals(configManager.getConfiguration(
			BankTagsStorage.SYNC_SETTINGS_GROUP, "folderCollapsed_" + folderId, Boolean.class));
	}

	public void setCollapsed(String folderId, boolean collapsed)
	{
		configManager.setConfiguration(BankTagsStorage.SYNC_SETTINGS_GROUP,
			"folderCollapsed_" + folderId, collapsed);
	}

	private SharedBankTagFolder require(String id)
	{
		SharedBankTagFolder folder = get(id);
		if (folder == null)
		{
			throw new IllegalArgumentException("folder does not exist: " + id);
		}
		return folder;
	}

	private void saveOrder(List<String> ids)
	{
		configManager.setConfiguration(BankTagsStorage.SYNC_DATA_GROUP, ORDER_KEY, Text.toCSV(ids));
	}

	private String value(String key)
	{
		String value = configManager.getConfiguration(BankTagsStorage.SYNC_DATA_GROUP, key);
		return value == null ? "" : value;
	}
}
