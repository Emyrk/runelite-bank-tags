package com.emyrk.banktags;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankTagsConfigTest
{
	@Test
	public void visibleConfigContainsLocalAndSyncSettings()
	{
		ConfigGroup group = BankTagsConfig.class.getAnnotation(ConfigGroup.class);
		assertEquals(BankTagsStorage.SYNC_SETTINGS_GROUP, group.value());

		Set<String> visibleKeys = Arrays.stream(BankTagsConfig.class.getMethods())
			.map(method -> method.getAnnotation(ConfigItem.class))
			.filter(item -> item != null && !item.hidden())
			.map(ConfigItem::keyName)
			.collect(Collectors.toSet());
		assertTrue(visibleKeys.containsAll(Arrays.asList(
			"useTabs", "rememberTab", "removeTabSeparators", "preventTagTabDrags",
			"enabled", "groupName", "groupToken", "serverBaseUrl",
			"pollIntervalSeconds", "uploadDebounceSeconds", "forceResync", "resetSyncCache")));
		assertFalse(visibleKeys.stream().anyMatch(key -> key.toLowerCase().contains("combat")));

		Method enabled = Arrays.stream(BankTagsConfig.class.getMethods())
			.filter(method -> method.getName().equals("enabled"))
			.findFirst()
			.orElseThrow(AssertionError::new);
		assertEquals("This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
			enabled.getAnnotation(ConfigItem.class).warning());

		Method groupToken = Arrays.stream(BankTagsConfig.class.getMethods())
			.filter(method -> method.getName().equals("groupToken"))
			.findFirst()
			.orElseThrow(AssertionError::new);
		ConfigItem tokenItem = groupToken.getAnnotation(ConfigItem.class);
		assertTrue(tokenItem.secret());
		assertEquals(BankTagsConfig.SYNC_SECTION, tokenItem.section());
	}

	@Test
	public void migrateLocalUiSettingsCopiesMissingValuesWithoutOverwriting()
	{
		Map<String, String> values = FakeConfigManager.newValues();
		ConfigManager configManager = FakeConfigManager.create(values);
		values.put(FakeConfigManager.key(BankTagsPlugin.CONFIG_GROUP, "useTabs"), "false");
		values.put(FakeConfigManager.key(BankTagsPlugin.CONFIG_GROUP, "rememberTab"), "false");
		values.put(FakeConfigManager.key(BankTagsStorage.SYNC_SETTINGS_GROUP, "useTabs"), "true");

		BankTagsPlugin.migrateLocalUiSettings(configManager);

		assertEquals("true", values.get(FakeConfigManager.key(BankTagsStorage.SYNC_SETTINGS_GROUP, "useTabs")));
		assertEquals("false", values.get(FakeConfigManager.key(BankTagsStorage.SYNC_SETTINGS_GROUP, "rememberTab")));
		assertFalse(values.containsKey(FakeConfigManager.key(BankTagsStorage.SYNC_SETTINGS_GROUP, "enabled")));
	}
}
