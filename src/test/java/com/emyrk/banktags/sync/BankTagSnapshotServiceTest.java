package com.emyrk.banktags.sync;

import com.emyrk.banktags.TagManager;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.tabs.Layout;
import com.emyrk.banktags.tabs.LayoutManager;
import com.emyrk.banktags.tabs.TabManager;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BankTagSnapshotServiceTest
{
	private TagManager tagManager;
	private TabManager tabManager;
	private LayoutManager layoutManager;
	private BankTagSnapshotService service;

	@Before
	public void before()
	{
		tagManager = mock(TagManager.class);
		tabManager = mock(TabManager.class);
		layoutManager = mock(LayoutManager.class);
		service = new BankTagSnapshotService(tagManager, tabManager, layoutManager);
	}

	@Test
	public void testSnapshotContainsOnlyOneTag()
	{
		when(tabManager.getPersistedTabNames()).thenReturn(Collections.singletonList("herbs"));
		when(tabManager.get("herbs")).thenReturn(new com.emyrk.banktags.tabs.TagTab());
		tabManager.get("herbs").setTag("herbs");
		tabManager.get("herbs").setIconItemId(952);
		when(tagManager.getItemsForTag("herbs")).thenReturn(Arrays.asList(300, -200, 100));
		when(layoutManager.loadLayout("herbs")).thenReturn(new Layout("herbs", new int[]{300, -1, 100}));

		SharedBankTag snapshot = service.snapshot("Herbs");

		assertEquals("herbs", snapshot.getName());
		assertEquals(952, snapshot.getIconItemId());
		assertEquals(Arrays.asList(-200, 100, 300), snapshot.getItemIds());
	}

	@Test
	public void testApplyRenamesAndReplacesWholeTag()
	{
		SharedBankTag tag = new SharedBankTag("id", "new tag", 4151,
			Arrays.asList(-200, 100), new int[]{100, -1, 200}, 2, false);

		service.apply("old tag", tag);

		verify(tagManager).renameTag("old tag", "new tag");
		verify(tabManager).rename("old tag", "new tag");
		verify(layoutManager).renameLayout("old tag", "new tag");
		verify(tagManager).replaceItemsForTag("new tag", Arrays.asList(-200, 100));
		verify(tabManager).upsert("new tag", 4151);
		verify(layoutManager).replaceLayout(org.mockito.ArgumentMatchers.eq("new tag"), aryEq(new int[]{100, -1, 200}));
	}

	@Test
	public void testDeleteRemovesAllTagState()
	{
		service.delete("old tag");

		verify(tagManager).removeTag("old tag");
		verify(tabManager).removePersisted("old tag");
		verify(layoutManager).removeLayout("old tag");
	}
}
