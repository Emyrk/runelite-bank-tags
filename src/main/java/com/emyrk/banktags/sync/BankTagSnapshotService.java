package com.emyrk.banktags.sync;

import com.emyrk.banktags.TagManager;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.tabs.Layout;
import com.emyrk.banktags.tabs.LayoutManager;
import com.emyrk.banktags.tabs.TabManager;
import com.emyrk.banktags.tabs.TagTab;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.util.Text;

@Singleton
public class BankTagSnapshotService
{
	private final TagManager tagManager;
	private final TabManager tabManager;
	private final LayoutManager layoutManager;

	@Inject
	BankTagSnapshotService(TagManager tagManager, TabManager tabManager, LayoutManager layoutManager)
	{
		this.tagManager = tagManager;
		this.tabManager = tabManager;
		this.layoutManager = layoutManager;
	}

	public SharedBankTag snapshot(String tag)
	{
		String name = Text.standardize(tag);
		if (!tabManager.getPersistedTabNames().contains(name))
		{
			throw new IllegalArgumentException("tag does not exist: " + name);
		}

		TagTab tab = tabManager.get(name);
		Layout layout = layoutManager.loadLayout(name);
		List<Integer> itemIds = new ArrayList<>(tagManager.getItemsForTag(name));
		return new SharedBankTag(null, name, tab.getIconItemId(), itemIds,
			layout == null ? null : layout.getLayout(), 0, false);
	}

	public void apply(String previousName, SharedBankTag tag)
	{
		String name = tag.getName();
		String oldName = previousName == null ? name : Text.standardize(previousName);
		if (tag.isDeleted())
		{
			delete(oldName);
			return;
		}

		if (!oldName.equals(name))
		{
			tagManager.renameTag(oldName, name);
			tabManager.rename(oldName, name);
			layoutManager.renameLayout(oldName, name);
		}

		tagManager.replaceItemsForTag(name, tag.getItemIds());
		tabManager.upsert(name, tag.getIconItemId());
		layoutManager.replaceLayout(name, tag.getLayout());
	}

	public void delete(String tag)
	{
		String name = Text.standardize(tag);
		tagManager.removeTag(name);
		tabManager.removePersisted(name);
		layoutManager.removeLayout(name);
	}
}
