package com.emyrk.banktags;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Map-backed {@link ConfigManager} mock for tests. Keys are {@code group.key}. The map is
 * synchronized because sync tests write from OkHttp callback threads.
 */
public final class FakeConfigManager
{
	private FakeConfigManager()
	{
	}

	public static String key(String group, String name)
	{
		return group + "." + name;
	}

	public static ConfigManager create(Map<String, String> values)
	{
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
			values.get(key(invocation.getArgument(0), invocation.getArgument(1))));
		when(configManager.getConfiguration(anyString(), anyString(), org.mockito.ArgumentMatchers.<Type>any())).thenAnswer(invocation ->
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
			if (type == Integer.class)
			{
				return Integer.valueOf(value);
			}
			return value;
		});
		when(configManager.getConfigurationKeys(anyString())).thenAnswer(invocation ->
		{
			String prefix = invocation.getArgument(0);
			synchronized (values)
			{
				return values.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().collect(Collectors.toList());
			}
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
		return configManager;
	}

	public static Map<String, String> newValues()
	{
		return Collections.synchronizedMap(new LinkedHashMap<>());
	}

	/**
	 * Snapshot of every entry whose key starts with {@code group + "."}.
	 */
	public static Map<String, String> group(Map<String, String> values, String group)
	{
		String prefix = group + ".";
		synchronized (values)
		{
			List<Map.Entry<String, String>> entries = values.entrySet().stream()
				.filter(e -> e.getKey().startsWith(prefix))
				.collect(Collectors.toList());
			Map<String, String> result = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : entries)
			{
				result.put(entry.getKey(), entry.getValue());
			}
			return result;
		}
	}
}
