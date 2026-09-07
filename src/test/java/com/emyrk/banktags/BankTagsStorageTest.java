package com.emyrk.banktags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
		configManager = mock(ConfigManager.class);
		syncConfig = mock(BankTagsSyncConfig.class);
		when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
			values.get(key(invocation.getArgument(0), invocation.getArgument(1))));
		when(configManager.getConfiguration(anyString(), anyString(), org.mockito.ArgumentMatchers.<java.lang.reflect.Type>any())).thenAnswer(invocation ->
		{
			String value = values.get(key(invocation.getArgument(0), invocation.getArgument(1)));
			Class<?> type = invocation.getArgument(2);
			if (value == null)
			{
				return null;
			}
			if (type == Boolean.class)
			{
				return Boolean.valueOf(value);
			}
			return value;
		});
		when(configManager.getConfigurationKeys(anyString())).thenAnswer(invocation ->
		{
			String prefix = invocation.getArgument(0);
			return values.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().collect(Collectors.toList());
		});
		doAnswer(invocation ->
		{
			values.put(key(invocation.getArgument(0), invocation.getArgument(1)), invocation.getArgument(2));
			return null;
		}).when(configManager).setConfiguration(anyString(), anyString(), anyString());
		doAnswer(invocation ->
		{
			Object value = invocation.getArgument(2);
			values.put(key(invocation.getArgument(0), invocation.getArgument(1)), String.valueOf(value));
			return null;
		}).when(configManager).setConfiguration(anyString(), anyString(), org.mockito.ArgumentMatchers.<Object>any());
		doAnswer(invocation ->
		{
			values.remove(key(invocation.getArgument(0), invocation.getArgument(1)));
			return null;
		}).when(configManager).unsetConfiguration(anyString(), anyString());
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
		return group + "." + name;
	}
}
