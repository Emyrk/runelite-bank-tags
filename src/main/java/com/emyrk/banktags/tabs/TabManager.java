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
package com.emyrk.banktags.tabs;

import com.google.common.base.MoreObjects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.gameval.ItemID;
import com.emyrk.banktags.BankTagsStorage;
import static com.emyrk.banktags.BankTagsPlugin.TAG_ICON_PREFIX;
import static com.emyrk.banktags.BankTagsPlugin.TAG_TABS_CONFIG;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.math.NumberUtils;

@Singleton
public class TabManager
{
	@Getter
	private final List<TagTab> tabs = new ArrayList<>();
	private final BankTagsStorage storage;

	@Inject
	private TabManager(BankTagsStorage storage)
	{
		this.storage = storage;
	}

	public void add(TagTab tagTab)
	{
		if (!contains(tagTab.getTag()))
		{
			tabs.add(tagTab);
		}
	}

	void clear()
	{
		tabs.clear();
	}

	public TagTab find(String tag)
	{
		Optional<TagTab> first = tabs.stream().filter(t -> t.getTag().equals(Text.standardize(tag))).findAny();
		return first.orElse(null);
	}

	List<String> loadAllTabNames()
	{
		return Text.fromCSV(MoreObjects.firstNonNull(storage.getConfiguration(TAG_TABS_CONFIG), ""));
	}

	TagTab load(String tag)
	{
		TagTab tagTab = find(tag);

		if (tagTab == null)
		{
			tag = Text.standardize(tag);
			String item = storage.getConfiguration(TAG_ICON_PREFIX + tag);
			int itemid = NumberUtils.toInt(item, ItemID.SPADE);
			tagTab = new TagTab(itemid, tag);
		}

		return tagTab;
	}

	public void reload()
	{
		clear();
		loadAllTabNames().forEach(tag -> add(load(tag)));
	}

	public List<String> getPersistedTabNames()
	{
		return new ArrayList<>(loadAllTabNames());
	}

	/**
	 * Current in-memory tab order.
	 */
	public List<String> tabNames()
	{
		return tabs.stream().map(TagTab::getTag).collect(Collectors.toList());
	}

	/**
	 * Reorders the tabs so those named in {@code orderedTags} come first, in that order, followed by
	 * every remaining tab in its current relative order, then persists.
	 */
	public void reorder(List<String> orderedTags)
	{
		List<TagTab> reordered = new ArrayList<>(tabs.size());
		for (String tag : orderedTags)
		{
			TagTab tab = find(tag);
			if (tab != null && !reordered.contains(tab))
			{
				reordered.add(tab);
			}
		}
		for (TagTab tab : tabs)
		{
			if (!reordered.contains(tab))
			{
				reordered.add(tab);
			}
		}
		tabs.clear();
		tabs.addAll(reordered);
		save();
	}

	public TagTab get(String tag)
	{
		return load(tag);
	}

	public void upsert(String tag, int iconItemId)
	{
		tag = Text.standardize(tag);
		reload();
		TagTab tab = find(tag);
		if (tab == null)
		{
			tab = new TagTab(iconItemId, tag);
			add(tab);
		}
		else
		{
			tab.setIconItemId(iconItemId);
		}
		save();
	}

	public void rename(String oldTag, String newTag)
	{
		oldTag = Text.standardize(oldTag);
		newTag = Text.standardize(newTag);
		reload();
		TagTab old = find(oldTag);
		if (old == null || oldTag.equals(newTag))
		{
			return;
		}
		if (find(newTag) != null)
		{
			throw new IllegalArgumentException("tag already exists: " + newTag);
		}

		removeIcon(oldTag);
		old.setTag(newTag);
		save();
	}

	public void removePersisted(String tag)
	{
		reload();
		remove(tag);
		save();
	}

	private void save(TagTab tab)
	{
		setIcon(tab.getTag(), tab.getIconItemId());
	}

	void swap(String tagToMove, String tagDestination)
	{
		tagToMove = Text.standardize(tagToMove);
		tagDestination = Text.standardize(tagDestination);

		if (contains(tagToMove) && contains(tagDestination))
		{
			Collections.swap(tabs, indexOf(tagToMove), indexOf(tagDestination));
		}
	}

	void insert(String tagToMove, String tagDestination)
	{
		tagToMove = Text.standardize(tagToMove);
		tagDestination = Text.standardize(tagDestination);

		if (contains(tagToMove) && contains(tagDestination))
		{
			tabs.add(indexOf(tagDestination), tabs.remove(indexOf(tagToMove)));
		}
	}

	public void remove(String tag)
	{
		TagTab tagTab = find(tag);

		if (tagTab != null)
		{
			tabs.remove(tagTab);
			removeIcon(tag);
		}
	}

	public void save()
	{
		String tags = Text.toCSV(tabs.stream().map(TagTab::getTag).collect(Collectors.toList()));
		storage.setConfiguration(TAG_TABS_CONFIG, tags);

		for (TagTab tab : tabs)
		{
			save(tab);
		}
	}

	private void removeIcon(final String tag)
	{
		storage.unsetConfiguration(TAG_ICON_PREFIX + Text.standardize(tag));
	}

	private void setIcon(final String tag, int itemId)
	{
		storage.setConfiguration(TAG_ICON_PREFIX + Text.standardize(tag), itemId);
	}

	int size()
	{
		return tabs.size();
	}

	private boolean contains(String tag)
	{
		return tabs.stream().anyMatch(t -> t.getTag().equals(tag));
	}

	private int indexOf(TagTab tagTab)
	{
		return tabs.indexOf(tagTab);
	}

	private int indexOf(String tag)
	{
		return indexOf(find(tag));
	}
}
