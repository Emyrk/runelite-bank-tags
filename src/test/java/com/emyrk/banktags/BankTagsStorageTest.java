package com.emyrk.banktags;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BankTagsStorageTest
{
	private final Map<String, String> values = new HashMap<>();
	private ConfigManager configManager;
	private BankTagsSyncConfig syncConfig;
	private BankTagsStorage storage;

	@Before
	public void before()
	{
		configManager = FakeConfigManager.create(values);
		syncConfig = mock(BankTagsSyncConfig.class);
		storage = new BankTagsStorage(configManager, syncConfig);
	}

	@Test
	public void testSyncStorageRequiresEnableAndInitialization()
	{
		when(syncConfig.enabled()).thenReturn(true);
		assertEquals(BankTagsPlugin.CONFIG_GROUP, storage.getActiveGroup());

		values.put(key(BankTagsStorage.SYNC_DATA_GROUP, BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY), "true");
		assertEquals(BankTagsStorage.SYNC_DATA_GROUP, storage.getActiveGroup());
	}

	@Test
	public void testInitializeCopiesOnlyTagDataAndPreservesSources()
	{
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "tagtabs"), "herbs");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "icon_herbs"), "952");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "layout_herbs"), "100,-1,200");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "item_100"), "herbs,other");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "hidden_secret"), "true");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "rememberTab"), "false");
		values.put(key("banktags", "item_100"), "builtin");

		storage.initializeSyncStorageFromLocal();

		assertEquals("herbs", values.get(key(BankTagsStorage.SYNC_DATA_GROUP, "tagtabs")));
		assertEquals("herbs,other", values.get(key(BankTagsStorage.SYNC_DATA_GROUP, "item_100")));
		assertFalse(values.containsKey(key(BankTagsStorage.SYNC_DATA_GROUP, "rememberTab")));
		assertEquals("herbs,other", values.get(key(BankTagsPlugin.CONFIG_GROUP, "item_100")));
		assertEquals("builtin", values.get(key("banktags", "item_100")));
		assertTrue(Boolean.parseBoolean(values.get(key(BankTagsStorage.SYNC_DATA_GROUP,
			BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY))));
	}

	private static String key(String group, String name)
	{
		return FakeConfigManager.key(group, name);
	}
}
