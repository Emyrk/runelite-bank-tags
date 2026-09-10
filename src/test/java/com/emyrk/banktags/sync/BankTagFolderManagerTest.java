package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.FakeConfigManager;
import com.emyrk.banktags.sync.model.SharedBankTagFolder;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BankTagFolderManagerTest
{
	private static final String TAG_A = "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721";
	private static final String TAG_B = "9c1b6f2e-2b1d-4d4e-8a5b-0f6a7c9e1d23";
	private final Map<String, String> values = FakeConfigManager.newValues();
	private BankTagFolderManager manager;

	@Before
	public void setUp()
	{
		ConfigManager configManager = FakeConfigManager.create(values);
		manager = new BankTagFolderManager(configManager, new Gson());
	}

	@Test
	public void existingTagsStartUnfiledAndStableIdsPersist()
	{
		SharedBankTagFolder folder = manager.create("Gathering");
		assertTrue(folder.getFolderId().matches("[0-9a-f-]{36}"));
		assertEquals(folder.getFolderId(), manager.folders().get(0).getFolderId());
		assertEquals(Arrays.asList(TAG_A, TAG_B), manager.unfiledTagIds(Arrays.asList(TAG_A, TAG_B)));
	}

	@Test
	public void moveIsSingleLevelAndUnfileRestoresMembership()
	{
		SharedBankTagFolder first = manager.create("Gathering");
		SharedBankTagFolder second = manager.create("Combat");
		manager.moveTag(TAG_A, first.getFolderId());
		assertEquals(first.getFolderId(), manager.folderIdForTag(TAG_A));

		manager.moveTag(TAG_A, second.getFolderId());
		assertFalse(manager.get(first.getFolderId()).getOrderedTagIds().contains(TAG_A));
		assertEquals(Collections.singletonList(TAG_A), manager.get(second.getFolderId()).getOrderedTagIds());

		manager.moveTag(TAG_A, null);
		assertNull(manager.folderIdForTag(TAG_A));
	}

	@Test
	public void deletingFolderDetachesTagsWithoutDeletingTagIdentity()
	{
		SharedBankTagFolder folder = manager.create("Gathering");
		manager.moveTag(TAG_A, folder.getFolderId());
		manager.delete(folder.getFolderId());

		assertNull(manager.get(folder.getFolderId()));
		assertEquals(Collections.singletonList(TAG_A), manager.unfiledTagIds(Collections.singletonList(TAG_A)));
		String order = values.get(FakeConfigManager.key(BankTagsStorage.SYNC_DATA_GROUP,
			BankTagFolderManager.ORDER_KEY));
		assertTrue(order == null || order.isEmpty());
	}

	@Test
	public void remoteTombstoneAlsoDetaches()
	{
		SharedBankTagFolder folder = manager.create("Gathering");
		manager.moveTag(TAG_A, folder.getFolderId());
		manager.apply(new SharedBankTagFolder(folder.getFolderId(), "Gathering", 0, Collections.emptyList(), 2, true, null));
		assertNull(manager.folderIdForTag(TAG_A));
	}
}
