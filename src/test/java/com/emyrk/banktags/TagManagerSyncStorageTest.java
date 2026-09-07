package com.emyrk.banktags;

import com.google.inject.Guice;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import org.junit.runner.RunWith;

@RunWith(MockitoJUnitRunner.class)
public class TagManagerSyncStorageTest
{
	@Mock
	@Bind
	private ConfigManager configManager;

	@Mock
	@Bind
	private ItemManager itemManager;

	@Mock
	@Bind
	private BankTagsSyncConfig syncConfig;

	@Inject
	private TagManager tagManager;

	private final Map<String, String> values = new HashMap<>();

	@Before
	public void before()
	{
		when(syncConfig.enabled()).thenReturn(true);
		when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
			values.get(key(invocation.getArgument(0), invocation.getArgument(1))));
		when(configManager.getConfiguration(anyString(), anyString(), org.mockito.ArgumentMatchers.<java.lang.reflect.Type>any())).thenAnswer(invocation ->
		{
			String value = values.get(key(invocation.getArgument(0), invocation.getArgument(1)));
			return value == null ? null : Boolean.valueOf(value);
		});
		when(configManager.getConfigurationKeys(anyString())).thenAnswer(invocation ->
		{
			String prefix = invocation.getArgument(0);
			return values.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().collect(Collectors.toList());
		});
		doAnswer(invocation ->
		{
			Object value = invocation.getArgument(2);
			values.put(key(invocation.getArgument(0), invocation.getArgument(1)), String.valueOf(value));
			return null;
		}).when(configManager).setConfiguration(anyString(), anyString(), org.mockito.ArgumentMatchers.<Object>any());
		values.put(key(BankTagsStorage.SYNC_DATA_GROUP, BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY), "true");
		Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);
	}

	@Test
	public void testReplaceItemsPreservesUnrelatedTagsAndSourceGroups()
	{
		values.put(key(BankTagsStorage.SYNC_DATA_GROUP, "item_100"), "herbs,other");
		values.put(key(BankTagsStorage.SYNC_DATA_GROUP, "item_200"), "other");
		values.put(key(BankTagsStorage.SYNC_DATA_GROUP, "item_-300"), "herbs");
		values.put(key(BankTagsPlugin.CONFIG_GROUP, "item_100"), "local-backup");
		values.put(key("banktags", "item_100"), "builtin-backup");

		assertEquals(BankTagsStorage.SYNC_DATA_GROUP,
			new BankTagsStorage(configManager, syncConfig).getActiveGroup());
		assertEquals(Arrays.asList(-300, 100), tagManager.getItemsForTag("herbs"));

		tagManager.replaceItemsForTag("Herbs", Arrays.asList(200, -300));

		assertEquals("other", values.get(key(BankTagsStorage.SYNC_DATA_GROUP, "item_100")));
		assertEquals("other,herbs", values.get(key(BankTagsStorage.SYNC_DATA_GROUP, "item_200")));
		assertEquals("herbs", values.get(key(BankTagsStorage.SYNC_DATA_GROUP, "item_-300")));
		assertEquals("local-backup", values.get(key(BankTagsPlugin.CONFIG_GROUP, "item_100")));
		assertEquals("builtin-backup", values.get(key("banktags", "item_100")));
	}

	private static String key(String group, String name)
	{
		return group + "." + name;
	}
}
