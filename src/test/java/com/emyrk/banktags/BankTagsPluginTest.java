/*
 * Copyright (c) 2020, Ron Young <https://github.com/raiyni>
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

import com.google.inject.Guice;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import javax.inject.Inject;
import javax.inject.Named;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import static com.emyrk.banktags.BankTagsPlugin.ITEM_KEY_PREFIX;
import com.emyrk.banktags.inventorysync.InventorySetupSyncCoordinator;
import com.emyrk.banktags.sync.BankTagSyncCoordinator;
import com.emyrk.banktags.tabs.TabInterface;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.cluescrolls.ClueScrollService;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import java.util.Arrays;
import java.util.Collections;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BankTagsPluginTest
{
	@Mock
	@Bind
	private Client client;

	@Mock
	@Bind
	private ItemManager itemManager;

	@Mock
	@Bind
	private BankTagsConfig bankTagsConfig;

	@Mock
	@Bind
	private RuneLiteConfig runeLiteConfig;

	@Mock
	@Bind
	private TabInterface tabInterface;

	@Mock
	@Bind
	private ClueScrollService clueScrollService;

	@Mock
	@Bind
	private ConfigManager configManager;

	@Mock
	@Bind
	private ChatMessageManager chatMessageManager;

	@Mock
	@Bind
	private ClientThread clientThread;

	@Mock
	@Bind
	private BankTagSyncCoordinator syncCoordinator;

	@Mock
	@Bind
	private InventorySetupSyncCoordinator inventorySetupSyncCoordinator;

	@Bind
	@Named("developerMode")
	boolean developerMode;

	@Inject
	private TagManager tagManager;

	@Inject
	private BankTagsPlugin bankTagsPlugin;

	private final ScriptCallbackEvent EVENT = new ScriptCallbackEvent();

	@Before
	public void before()
	{
		Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);

		EVENT.setEventName("bankSearchFilter");

		when(itemManager.canonicalize(ItemID.ABYSSAL_WHIP)).thenReturn(ItemID.ABYSSAL_WHIP);
		when(client.getIntStackSize()).thenReturn(2);
		when(client.getObjectStackSize()).thenReturn(1);
	}

	@Test
	public void testUsesIndependentToggleAndConflictsWithBuiltInBankTags()
	{
		PluginDescriptor descriptor = BankTagsPlugin.class.getAnnotation(PluginDescriptor.class);
		assertEquals("bankTagsExtended", descriptor.configName());
		assertArrayEquals(new String[]{"Bank Tags"}, descriptor.conflicts());
	}

	@Test
	public void testExplicitSearch()
	{
		when(client.getIntStack()).thenReturn(new int[]{0, ItemID.ABYSSAL_WHIP});
		when(client.getObjectStack()).thenReturn(new String[]{"tag:whip"});

		when(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP,
			ITEM_KEY_PREFIX + ItemID.ABYSSAL_WHIP)).thenReturn("herb,bossing,whip");
		bankTagsPlugin.onScriptCallbackEvent(EVENT);
		assertEquals(1, client.getIntStack()[0]);

		// Search should be found at the start of the tag
		when(client.getIntStack()).thenReturn(new int[]{0, ItemID.ABYSSAL_WHIP});
		when(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP,
			ITEM_KEY_PREFIX + ItemID.ABYSSAL_WHIP)).thenReturn("herb,bossing,whip long tag");
		bankTagsPlugin.onScriptCallbackEvent(EVENT);
		assertEquals(1, client.getIntStack()[0]);

		// Search should not be be found in the middle of the tag
		// and explicit search does not allow fall through
		when(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP,
			ITEM_KEY_PREFIX + ItemID.ABYSSAL_WHIP)).thenReturn("herb,bossing whip");
		bankTagsPlugin.onScriptCallbackEvent(EVENT);
		assertEquals(0, client.getIntStack()[0]);
	}

	@Test
	public void testFallThrough()
	{
		when(client.getIntStack()).thenReturn(new int[]{1, ItemID.ABYSSAL_WHIP});
		when(client.getObjectStack()).thenReturn(new String[]{"whip"});

		when(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP,
			ITEM_KEY_PREFIX + ItemID.ABYSSAL_WHIP)).thenReturn("herb,bossing");

		assertFalse(tagManager.findTag(ItemID.ABYSSAL_WHIP, "whip"));
		bankTagsPlugin.onScriptCallbackEvent(EVENT);
		assertEquals(1, client.getIntStack()[0]);
	}

	@Test
	public void testNonExplicitSearch()
	{
		when(client.getIntStack()).thenReturn(new int[]{0, ItemID.ABYSSAL_WHIP});
		when(client.getObjectStack()).thenReturn(new String[]{"whip"});

		when(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP,
			ITEM_KEY_PREFIX + ItemID.ABYSSAL_WHIP)).thenReturn("herb,bossing,whip long tag");

		bankTagsPlugin.onScriptCallbackEvent(EVENT);
		assertEquals(1, client.getIntStack()[0]);
	}

	@Test
	public void configChangeToggleRestartsCoordinator()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsStorage.SYNC_SETTINGS_GROUP);
		event.setKey("enabled");
		event.setNewValue("true");

		bankTagsPlugin.onConfigChanged(event);

		InOrder order = inOrder(syncCoordinator);
		order.verify(syncCoordinator).stop();
		order.verify(syncCoordinator).start();
		verify(clientThread).invokeLater(org.mockito.ArgumentMatchers.any(Runnable.class));
	}

	@Test
	public void resetSyncCacheToggleResetsAndRestarts()
	{
		String sync = BankTagsStorage.SYNC_DATA_GROUP;
		when(configManager.getConfigurationKeys(anyString())).thenAnswer(invocation ->
			invocation.getArgument(0).equals(sync + ".")
				? Arrays.asList(sync + ".tagtabs", sync + ".item_4151", sync + ".syncTag_abc", sync + "." + BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY)
				: Collections.emptyList());
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsStorage.SYNC_SETTINGS_GROUP);
		event.setKey("resetSyncCache");
		event.setNewValue("true");

		bankTagsPlugin.onConfigChanged(event);

		InOrder order = inOrder(configManager, syncCoordinator);
		order.verify(configManager).setConfiguration(BankTagsStorage.SYNC_SETTINGS_GROUP, "resetSyncCache", false);
		order.verify(syncCoordinator).stop();
		order.verify(configManager).unsetConfiguration(sync, "tagtabs");
		order.verify(configManager).unsetConfiguration(sync, "item_4151");
		order.verify(configManager).unsetConfiguration(sync, "syncTag_abc");
		order.verify(configManager).unsetConfiguration(sync, BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY);
		verify(configManager, never()).unsetConfiguration(eq(BankTagsPlugin.CONFIG_GROUP), anyString());
		verify(configManager, never()).unsetConfiguration(eq("banktags"), anyString());
		verify(syncCoordinator, never()).start();

		ArgumentCaptor<Runnable> onClientThread = ArgumentCaptor.forClass(Runnable.class);
		verify(clientThread).invokeLater(onClientThread.capture());
		onClientThread.getValue().run();
		verify(syncCoordinator).start();
	}

	@Test
	public void resetSyncCacheWriteBackDoesNotRestart()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsStorage.SYNC_SETTINGS_GROUP);
		event.setKey("resetSyncCache");
		event.setNewValue("false");

		bankTagsPlugin.onConfigChanged(event);

		verifyNoInteractions(syncCoordinator);
		verify(configManager, never()).unsetConfiguration(anyString(), anyString());
	}

	@Test
	public void forceResyncTogglePullsBothSynchronizedResourcesImmediately()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsStorage.SYNC_SETTINGS_GROUP);
		event.setKey("forceResync");
		event.setNewValue("true");

		bankTagsPlugin.onConfigChanged(event);

		verify(configManager).setConfiguration(BankTagsStorage.SYNC_SETTINGS_GROUP, "forceResync", false);
		verify(syncCoordinator).forceResync();
		verify(inventorySetupSyncCoordinator).forceResync();
	}

	@Test
	public void forceResyncWriteBackDoesNothing()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsStorage.SYNC_SETTINGS_GROUP);
		event.setKey("forceResync");
		event.setNewValue("false");

		bankTagsPlugin.onConfigChanged(event);

		verifyNoInteractions(syncCoordinator, inventorySetupSyncCoordinator);
	}

	@Test
	public void unrelatedConfigChangeLeavesCoordinatorAlone()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(BankTagsPlugin.CONFIG_GROUP);
		event.setKey("rememberTab");

		bankTagsPlugin.onConfigChanged(event);

		verifyNoInteractions(syncCoordinator);
	}
}
