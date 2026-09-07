/*
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2018, Ron Young <https://github.com/raiyni>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.emyrk.banktags;

import com.google.common.base.Strings;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import static com.emyrk.banktags.BankTagsPlugin.ITEM_KEY_PREFIX;
import static com.emyrk.banktags.BankTagsPlugin.TAG_HIDDEN_PREFIX;
import net.runelite.client.util.Text;

@Singleton
public class TagManager
{
	private final BankTagsStorage storage;
	private final ItemManager itemManager;
	private final Map<String, BankTag> customTags = new HashMap<>();

	@Inject
	private TagManager(
		final ItemManager itemManager,
		final BankTagsStorage storage)
	{
		this.itemManager = itemManager;
		this.storage = storage;
	}

	String getTagString(int itemId, boolean variation)
	{
		return getStoredTagString(getItemId(itemId, variation));
	}

	private String getStoredTagString(int itemId)
	{
		String config = storage.getConfiguration(ITEM_KEY_PREFIX + itemId);
		return config == null ? "" : config;
	}

	Collection<String> getTags(int itemId, boolean variation)
	{
		return getStoredTags(getItemId(itemId, variation));
	}

	private Collection<String> getStoredTags(int itemId)
	{
		return new LinkedHashSet<>(Text.fromCSV(getStoredTagString(itemId).toLowerCase()));
	}

	void setTagString(int itemId, String tags, boolean variation)
	{
		setStoredTagString(getItemId(itemId, variation), tags);
	}

	private void setStoredTagString(int itemId, String tags)
	{
		if (Strings.isNullOrEmpty(tags))
		{
			storage.unsetConfiguration(ITEM_KEY_PREFIX + itemId);
		}
		else
		{
			storage.setConfiguration(ITEM_KEY_PREFIX + itemId, tags);
		}
	}

	public void addTags(int itemId, final Collection<String> t, boolean variation)
	{
		final Collection<String> tags = getTags(itemId, variation);
		if (tags.addAll(t))
		{
			setTags(itemId, tags, variation);
		}
	}

	public void addTag(int itemId, String tag, boolean variation)
	{
		final Collection<String> tags = getTags(itemId, variation);
		if (tags.add(Text.standardize(tag)))
		{
			setTags(itemId, tags, variation);
		}
	}

	private void setTags(int itemId, Collection<String> tags, boolean variation)
	{
		setTagString(itemId, Text.toCSV(tags), variation);
	}

	private void addStoredTag(int itemId, String tag)
	{
		Collection<String> tags = getStoredTags(itemId);
		if (tags.add(Text.standardize(tag)))
		{
			setStoredTagString(itemId, Text.toCSV(tags));
		}
	}

	private void removeStoredTag(int itemId, String tag)
	{
		Collection<String> tags = getStoredTags(itemId);
		if (tags.remove(Text.standardize(tag)))
		{
			setStoredTagString(itemId, Text.toCSV(tags));
		}
	}

	boolean findTag(int itemId, String search)
	{
		Collection<String> tags = getTags(itemId, false);
		tags.addAll(getTags(itemId, true));
		return tags.stream().anyMatch(tag -> tag.startsWith(Text.standardize(search)));
	}

	public List<Integer> getItemsForTag(String tag)
	{
		final String standardizedTag = Text.standardize(tag);
		final String prefix = storage.getActiveGroup() + "." + ITEM_KEY_PREFIX;
		return storage.getConfigurationKeys(ITEM_KEY_PREFIX).stream()
			.map(item -> Integer.parseInt(item.replace(prefix, "")))
			.filter(item -> getStoredTags(item).contains(standardizedTag))
			.collect(Collectors.toList());
	}

	public void replaceItemsForTag(String tag, Collection<Integer> itemIds)
	{
		final String standardizedTag = Text.standardize(tag);
		final Set<Integer> desiredItems = new HashSet<>(itemIds);
		final Set<Integer> currentItems = new HashSet<>(getItemsForTag(standardizedTag));

		currentItems.stream()
			.filter(itemId -> !desiredItems.contains(itemId))
			.forEach(itemId -> removeStoredTag(itemId, standardizedTag));

		desiredItems.stream()
			.filter(itemId -> !currentItems.contains(itemId))
			.forEach(itemId -> addStoredTag(itemId, standardizedTag));
	}

	public void removeTag(String tag)
	{
		final String prefix = storage.getActiveGroup() + "." + ITEM_KEY_PREFIX;
		storage.getConfigurationKeys(ITEM_KEY_PREFIX).forEach(item ->
		{
			int id = Integer.parseInt(item.replace(prefix, ""));
			removeStoredTag(id, tag);
		});

		setHidden(tag, false);
	}

	public void removeTag(int itemId, String tag)
	{
		removeTag(itemId, tag, false);
		removeTag(itemId, tag, true);
	}

	public void removeTag(int itemId, String tag, boolean variation)
	{
		Collection<String> tags = getTags(itemId, variation);
		if (tags.remove(Text.standardize(tag)))
		{
			setTags(itemId, tags, variation);
		}
	}

	public void renameTag(String oldTag, String newTag)
	{
		List<Integer> items = getItemsForTag(Text.standardize(oldTag));
		items.forEach(id ->
		{
			Collection<String> tags = getStoredTags(id);

			tags.remove(Text.standardize(oldTag));
			tags.add(Text.standardize(newTag));

			setStoredTagString(id, Text.toCSV(tags));
		});
	}

	public boolean isHidden(String tag)
	{
		return Boolean.TRUE.equals(storage.getConfiguration(TAG_HIDDEN_PREFIX + Text.standardize(tag), Boolean.class));
	}

	public void setHidden(String tag, boolean hidden)
	{
		if (hidden)
		{
			storage.setConfiguration(TAG_HIDDEN_PREFIX + Text.standardize(tag), true);
		}
		else
		{
			storage.unsetConfiguration(TAG_HIDDEN_PREFIX + Text.standardize(tag));
		}
	}

	private int getItemId(int itemId, boolean variation)
	{
		itemId = Math.abs(itemId);
		itemId = itemManager.canonicalize(itemId);

		if (variation)
		{
			itemId = ItemVariationMapping.map(itemId) * -1;
		}

		return itemId;
	}

	public void registerTag(String name, BankTag tag)
	{
		customTags.put(name, tag);
	}

	public void unregisterTag(String name)
	{
		customTags.remove(name);
	}

	BankTag findTag(String name)
	{
		return customTags.get(name);
	}
}
