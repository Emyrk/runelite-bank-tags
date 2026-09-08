package com.emyrk.banktags;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

@Singleton
public class BankTagsStorage
{
	public static final String SYNC_DATA_GROUP = "emyrk-bank-tags-sync";
	public static final String SYNC_SETTINGS_GROUP = "emyrk-bank-tags-sync-settings";
	public static final String SYNC_STORAGE_INITIALIZED_KEY = "syncStorageInitialized";

	private final ConfigManager configManager;
	private final BankTagsSyncConfig syncConfig;

	@Inject
	BankTagsStorage(ConfigManager configManager, BankTagsSyncConfig syncConfig)
	{
		this.configManager = configManager;
		this.syncConfig = syncConfig;
	}

	public void initializeSyncStorageFromLocal()
	{
		for (String fullKey : configManager.getConfigurationKeys(BankTagsPlugin.CONFIG_GROUP + "."))
		{
			String[] keyParts = fullKey.split("\\.", 2);
			if (keyParts.length != 2 || !isSharedDataKey(keyParts[1]))
			{
				continue;
			}

			String value = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, keyParts[1]);
			if (value != null)
			{
				configManager.setConfiguration(SYNC_DATA_GROUP, keyParts[1], value);
			}
		}
		markSyncStorageInitialized();
	}

	/**
	 * Sets the marker that makes {@link #SYNC_DATA_GROUP} the active repository while sync is enabled.
	 * The coordinator calls this before applying remote tags on first enable so the applied data lands
	 * in the synchronized namespace.
	 */
	public void markSyncStorageInitialized()
	{
		configManager.setConfiguration(SYNC_DATA_GROUP, SYNC_STORAGE_INITIALIZED_KEY, "true");
	}

	private static boolean isSharedDataKey(String key)
	{
		return key.equals(BankTagsPlugin.TAG_TABS_CONFIG)
			|| key.startsWith(BankTagsPlugin.ITEM_KEY_PREFIX)
			|| key.startsWith(BankTagsPlugin.TAG_ICON_PREFIX)
			|| key.startsWith(BankTagsPlugin.TAG_LAYOUT_PREFIX)
			|| key.startsWith(BankTagsPlugin.TAG_HIDDEN_PREFIX);
	}

	public String getActiveGroup()
	{
		return isSyncStorageActive() ? SYNC_DATA_GROUP : BankTagsPlugin.CONFIG_GROUP;
	}

	public boolean isSyncStorageActive()
	{
		return syncConfig.enabled()
			&& Boolean.TRUE.equals(configManager.getConfiguration(
				SYNC_DATA_GROUP, SYNC_STORAGE_INITIALIZED_KEY, Boolean.class));
	}

	public String getConfiguration(String key)
	{
		return configManager.getConfiguration(getActiveGroup(), key);
	}

	public <T> T getConfiguration(String key, Class<T> type)
	{
		return configManager.getConfiguration(getActiveGroup(), key, type);
	}

	public List<String> getConfigurationKeys(String keyPrefix)
	{
		return configManager.getConfigurationKeys(getActiveGroup() + "." + keyPrefix);
	}

	public void setConfiguration(String key, Object value)
	{
		configManager.setConfiguration(getActiveGroup(), key, value);
	}

	public void unsetConfiguration(String key)
	{
		configManager.unsetConfiguration(getActiveGroup(), key);
	}
}
