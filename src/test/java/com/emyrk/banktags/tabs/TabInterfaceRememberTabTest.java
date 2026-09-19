package com.emyrk.banktags.tabs;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.BankTagsPlugin;
import com.emyrk.banktags.BankTagsService;
import com.emyrk.banktags.TagManager;
import com.emyrk.banktags.sync.BankTagSyncCoordinator;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.bank.BankSearch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TabInterfaceRememberTabTest
{
	@Mock private Client client;
	@Mock private ClientThread clientThread;
	@Mock private BankTagsPlugin plugin;
	@Mock private ItemManager itemManager;
	@Mock private TagManager tagManager;
	@Mock private TabManager tabManager;
	@Mock private LayoutManager layoutManager;
	@Mock private ChatboxPanelManager chatboxPanelManager;
	@Mock private BankTagsConfig config;
	@Mock private BankSearch bankSearch;
	@Mock private ChatboxItemSearch searchProvider;
	@Mock private ChatMessageManager chatMessageManager;
	@Mock private BankTagSyncCoordinator syncCoordinator;
	@Mock private Widget bankItemsContainer;

	private TabInterface tabs;

	@Before
	public void setUp()
	{
		tabs = new TabInterface(client, clientThread, plugin, itemManager, tagManager, tabManager,
			layoutManager, chatboxPanelManager, config, bankSearch, searchProvider, chatMessageManager,
			syncCoordinator);
	}

	@Test
	public void restoresRememberedTagAfterBankInitialization()
	{
		when(config.rememberTab()).thenReturn(true);
		when(config.tab()).thenReturn("herbs");
		when(client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER)).thenReturn(bankItemsContainer);
		when(tabManager.find("herbs")).thenReturn(new TagTab(1, "herbs"));
		Layout layout = new Layout("herbs");
		when(layoutManager.loadLayout("herbs")).thenReturn(layout);
		AtomicReference<Runnable> deferred = new AtomicReference<>();
		doAnswer(invocation ->
		{
			deferred.set(invocation.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		tabs.restoreRememberedTagOnBankOpen();

		verify(plugin, never()).openTag(any(), any());
		deferred.get().run();
		verify(client).setVarbit(VarbitID.BANK_CURRENTTAB, 0);
		verify(plugin).openTag("herbs", layout);
	}

	@Test
	public void closingTagKeepsLastVisitedTagForNextBankOpen()
	{
		tabs.openTag("herbs", null, BankTagsService.OPTION_ALLOW_MODIFICATIONS, false);
		tabs.closeTag(false);

		verify(config).tab("herbs");
		verify(config, never()).tab("");
	}

	@Test
	public void disabledRememberSettingDoesNotScheduleRestore()
	{
		when(config.rememberTab()).thenReturn(false);

		tabs.restoreRememberedTagOnBankOpen();

		verify(clientThread, never()).invokeLater(any(Runnable.class));
	}
}
