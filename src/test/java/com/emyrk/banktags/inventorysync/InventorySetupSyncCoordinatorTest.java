package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import inventorysetups.InventorySetupsPlugin;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class InventorySetupSyncCoordinatorTest
{
	@Test
	public void remoteApplyFeedbackIsSuppressed() throws Exception
	{
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		InventorySetupSyncCoordinator coordinator = new InventorySetupSyncCoordinator(
			mock(InventorySetupSyncClient.class), mock(InventorySetupRepository.class),
			mock(InventorySetupSyncMetadata.class), mock(BankTagsConfig.class), executor,
			mock(ClientThread.class));
		set(coordinator, "active", true);
		set(coordinator, "applyingRemote", true);
		coordinator.onLocalMutation(true, true);
		verifyNoInteractions(executor);
	}

	@Test
	public void forceResyncStartsManifestPollImmediately() throws Exception
	{
		InventorySetupSyncClient client = mock(InventorySetupSyncClient.class);
		InventorySetupSyncMetadata metadata = mock(InventorySetupSyncMetadata.class);
		InventorySetupSyncCoordinator coordinator = new InventorySetupSyncCoordinator(
			client, mock(InventorySetupRepository.class), metadata, mock(BankTagsConfig.class),
			mock(ScheduledExecutorService.class), mock(ClientThread.class));
		set(coordinator, "active", true);

		coordinator.forceResync();

		verify(client).getManifest(any(), any());
	}

	@Test
	public void globalSetupAndSectionOrdersAreAppliedExactly()
	{
		SharedInventorySetup a = new SharedInventorySetup("11111111-1111-4111-8111-111111111111", "a", "",
			new com.google.gson.JsonObject(), 1, false);
		SharedInventorySetup b = new SharedInventorySetup("22222222-2222-4222-8222-222222222222", "b", "",
			new com.google.gson.JsonObject(), 1, false);
		Map<String, SharedInventorySetup> setups = new LinkedHashMap<>();
		setups.put(a.getSetupId(), a); setups.put(b.getSetupId(), b);
		List<SharedInventorySetup> ordered = InventorySetupSyncCoordinator.orderedSetups(setups,
			Arrays.asList(b.getSetupId(), a.getSetupId()));
		assertEquals(Arrays.asList(b, a), ordered);

		SharedInventorySetupSection x = new SharedInventorySetupSection("33333333-3333-4333-8333-333333333333",
			"x", null, Arrays.asList(a.getSetupId()), 1, false);
		SharedInventorySetupSection y = new SharedInventorySetupSection("44444444-4444-4444-8444-444444444444",
			"y", null, Arrays.asList(a.getSetupId(), b.getSetupId()), 1, false);
		Map<String, SharedInventorySetupSection> sections = new LinkedHashMap<>();
		sections.put(x.getSectionId(), x); sections.put(y.getSectionId(), y);
		assertEquals(Arrays.asList(y, x), InventorySetupSyncCoordinator.orderedSections(sections,
			Arrays.asList(y.getSectionId(), x.getSectionId())));
	}

	private static void set(Object target, String name, boolean value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.setBoolean(target, value);
	}
}
