package com.emyrk.banktags;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(BankTagsStorage.SYNC_SETTINGS_GROUP)
public interface BankTagsSyncConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enable group sync",
		description = "Synchronize bank tags with the configured Group Ironmen server.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = BankTagsConfig.SYNC_SECTION,
		position = 11
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groupName",
		name = "Group name",
		description = "The group name used on groupiron.men.",
		section = BankTagsConfig.SYNC_SECTION,
		position = 12
	)
	default String groupName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "groupToken",
		name = "Group token",
		description = "The authorization token for the group.",
		secret = true,
		section = BankTagsConfig.SYNC_SECTION,
		position = 13
	)
	default String groupToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "serverBaseUrl",
		name = "Server URL override",
		description = "Leave blank to use the public groupiron.men server.",
		section = BankTagsConfig.SYNC_SECTION,
		position = 14
	)
	default String serverBaseUrl()
	{
		return "";
	}

	@Range(min = 5, max = 300)
	@ConfigItem(
		keyName = "pollIntervalSeconds",
		name = "Poll interval",
		description = "Seconds between checks for remote tag changes.",
		section = BankTagsConfig.SYNC_SECTION,
		position = 15
	)
	default int pollIntervalSeconds()
	{
		return 10;
	}

	@Range(min = 1, max = 30)
	@ConfigItem(
		keyName = "uploadDebounceSeconds",
		name = "Upload debounce",
		description = "Seconds to wait for a tag mutation to finish before uploading it.",
		section = BankTagsConfig.SYNC_SECTION,
		position = 16
	)
	default int uploadDebounceSeconds()
	{
		return 1;
	}

	/**
	 * A self-resetting action: the plugin clears the flag as soon as it sees {@code true}.
	 */
	@ConfigItem(
		keyName = "resetSyncCache",
		name = "Reset synchronized cache",
		description = "Deletes the local copy of synchronized tags and reloads them from the server. Your pre-sync local tags are not touched. Tick to run once.",
		warning = "This deletes the local synchronized cache. Unsynced local changes to synchronized tags are lost.",
		section = BankTagsConfig.SYNC_SECTION,
		position = 17
	)
	default boolean resetSyncCache()
	{
		return false;
	}
}
