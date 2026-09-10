package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.BankTagsPlugin;
import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.FakeConfigManager;
import com.emyrk.banktags.FakeScheduler;
import com.emyrk.banktags.TagManager;
import com.emyrk.banktags.sync.BankTagSyncMetadata.Conflict;
import com.emyrk.banktags.sync.BankTagSyncMetadata.TagMeta;
import com.emyrk.banktags.sync.BankTagSyncStatus.GlobalState;
import com.emyrk.banktags.sync.BankTagSyncStatus.TagState;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.tabs.LayoutManager;
import com.emyrk.banktags.tabs.TabInterface;
import com.emyrk.banktags.tabs.TabManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.bank.BankSearch;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BankTagSyncCoordinatorTest
{
	private static final String LOCAL = BankTagsPlugin.CONFIG_GROUP;
	private static final String SYNC = BankTagsStorage.SYNC_DATA_GROUP;
	private static final String BASE = "/api/group/gim";
	private static final String HERB_ID = "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721";
	private static final String SLAYER_ID = "9c1b6f2e-2b1d-4d4e-8a5b-0f6a7c9e1d23";
	private static final String THIRD_ID = "1d2c3b4a-5f6e-4a7b-8c9d-0e1f2a3b4c5d";
	private static final int DEBOUNCE = 1;
	private static final int POLL = 10;

	private final Gson gson = new Gson();
	private final Map<String, String> values = FakeConfigManager.newValues();
	private final Map<String, Deque<MockResponse>> routes = new ConcurrentHashMap<>();
	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
	private final AtomicLong nextRevision = new AtomicLong(1);

	private MockWebServer server;
	private ConfigManager configManager;
	private BankTagsConfig config;
	private FakeScheduler scheduler;
	private ClientThread clientThread;
	private TabInterface tabInterface;
	private BankTagsPlugin plugin;
	private TagManager tagManager;
	private TabManager tabManager;
	private LayoutManager layoutManager;
	private BankTagSnapshotService snapshots;
	private BankTagSyncMetadata metadata;
	private BankTagsStorage storage;
	private BankTagSyncCoordinator coordinator;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				requests.add(request);
				Deque<MockResponse> queue = routes.get(request.getMethod() + " " + request.getPath());
				MockResponse queued = queue == null ? null : queue.pollFirst();
				if (queued != null)
				{
					return queued;
				}
				if ("GET".equals(request.getMethod()) && (BASE + "/bank-tags").equals(request.getPath())
					&& request.getHeader("If-None-Match") != null)
				{
					return new MockResponse().setResponseCode(304);
				}
					if ("GET".equals(request.getMethod()) && (BASE + "/bank-tag-folders").equals(request.getPath()))
					{
						if (request.getHeader("If-None-Match") != null)
						{
							return new MockResponse().setResponseCode(304);
						}
						return json(200, "{\"schemaVersion\":1,\"groupRevision\":0,\"orderRevision\":0,\"orderedFolderIds\":[],\"folders\":[]}");
					}
				if ("PUT".equals(request.getMethod()) && request.getPath().startsWith(BASE + "/bank-tags/"))
				{
					// default: accept the write and echo it back as the stored document
					String tagId = request.getPath().substring((BASE + "/bank-tags/").length());
					JsonObject body = gson.fromJson(request.getBody().clone().readUtf8(), JsonObject.class);
					String ifMatch = request.getHeader("If-Match");
					long revision = ifMatch == null
						? nextRevision.getAndIncrement()
						: Long.parseLong(ifMatch.replace("\"", "")) + 1;
					body.addProperty("tagId", tagId);
					body.addProperty("revision", revision);
					body.addProperty("deleted", false);
					return json(ifMatch == null ? 201 : 200, gson.toJson(body));
				}
				return json(404, "{\"error\":\"tag_not_found\",\"message\":\"no route\"}");
			}
		});
		server.start();

		configManager = FakeConfigManager.create(values);
		config = mock(BankTagsConfig.class);
		when(config.enabled()).thenReturn(true);
		when(config.groupName()).thenReturn("gim");
		when(config.groupToken()).thenReturn("tok-123");
		when(config.serverBaseUrl()).thenReturn(server.url("/").toString());
		when(config.pollIntervalSeconds()).thenReturn(POLL);
		when(config.uploadDebounceSeconds()).thenReturn(DEBOUNCE);

		scheduler = new FakeScheduler();
		clientThread = mock(ClientThread.class);
		// Runnables run inline on the calling thread, but serialized: the real client thread is single
		// threaded, so two OkHttp callbacks must never interleave inside coordinator code.
		final Object clientThreadLock = new Object();
		doAnswer(invocation ->
		{
			synchronized (clientThreadLock)
			{
				((Runnable) invocation.getArgument(0)).run();
			}
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		doAnswer(invocation ->
		{
			synchronized (clientThreadLock)
			{
				((Runnable) invocation.getArgument(0)).run();
			}
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		tabInterface = mock(TabInterface.class);
		plugin = mock(BankTagsPlugin.class);

		Injector injector;
		try
		{
			injector = createInjector();
		}
		catch (com.google.inject.CreationException ex)
		{
			StringBuilder sb = new StringBuilder();
			for (com.google.inject.spi.Message message : ex.getErrorMessages())
			{
				sb.append(message.getMessage()).append('\n');
			}
			throw new IllegalStateException(sb.toString(), ex.getErrorMessages().iterator().next().getCause());
		}
		tagManager = injector.getInstance(TagManager.class);
		tabManager = injector.getInstance(TabManager.class);
		layoutManager = injector.getInstance(LayoutManager.class);
		snapshots = injector.getInstance(BankTagSnapshotService.class);
		metadata = injector.getInstance(BankTagSyncMetadata.class);
		storage = injector.getInstance(BankTagsStorage.class);
		coordinator = injector.getInstance(BankTagSyncCoordinator.class);
	}

	private Injector createInjector()
	{
		return Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(ConfigManager.class).toInstance(configManager);
				bind(BankTagsConfig.class).toInstance(config);
				bind(Client.class).toInstance(mock(Client.class));
				ItemManager itemManager = mock(ItemManager.class);
				when(itemManager.canonicalize(anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
				bind(ItemManager.class).toInstance(itemManager);
				bind(ChatboxPanelManager.class).toInstance(mock(ChatboxPanelManager.class));
				bind(BankSearch.class).toInstance(mock(BankSearch.class));
				bind(ChatMessageManager.class).toInstance(mock(ChatMessageManager.class));
				bind(EventBus.class).toInstance(mock(EventBus.class));
				bind(BankTagsPlugin.class).toInstance(plugin);
				bind(TabInterface.class).toInstance(tabInterface);
				bind(ClientThread.class).toInstance(clientThread);
				bind(ScheduledExecutorService.class).toInstance(scheduler);
				bind(OkHttpClient.class).toInstance(new OkHttpClient());
				bind(Gson.class).toInstance(gson);
				bind(RuneLiteConfig.class).toInstance(mock(RuneLiteConfig.class));
				bindConstant().annotatedWith(Names.named("developerMode")).to(false);
			}

			@Provides
			@Singleton
			BankTagSnapshotService snapshotService(TagManager tagManager, TabManager tabManager, LayoutManager layoutManager)
			{
				return spy(new BankTagSnapshotService(tagManager, tabManager, layoutManager));
			}
		});
	}

	@After
	public void tearDown() throws IOException
	{
		coordinator.stop();
		server.shutdown();
	}

	// ------------------------------------------------------------------ first enable

	@Test
	public void firstEnableWithEmptyRemoteSeedsFromLocalAndUploadsEachTab() throws Exception
	{
		seedLocalTab(LOCAL, "herblore", 952, Arrays.asList(199, 201), new int[]{199, 201});
		seedLocalTab(LOCAL, "slayer", 4151, Collections.singletonList(4151), null);
		Map<String, String> localBefore = FakeConfigManager.group(values, LOCAL);
		route("GET", "/bank-tags", json(200, manifest(1, 0, Collections.emptyList())));

		coordinator.start();
		// marker + two minted tag IDs + poll and two debounced uploads scheduled
		await(() -> storage.isSyncStorageActive() && metadata.allTags().size() == 2 && scheduler.pendingCount() == 3);
		assertEquals(0, requests("PUT").size());

		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 2 && allRevisionsPositive());

		List<String> names = new ArrayList<>();
		for (RecordedRequest put : requests("PUT"))
		{
			assertEquals("*", put.getHeader("If-None-Match"));
			assertNull(put.getHeader("If-Match"));
			names.add(gson.fromJson(put.getBody().readUtf8(), JsonObject.class).get("name").getAsString());
		}
		Collections.sort(names);
		assertEquals(Arrays.asList("herblore", "slayer"), names);
		assertEquals(localBefore, FakeConfigManager.group(values, LOCAL));
		assertEquals("true", values.get(FakeConfigManager.key(SYNC, BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY)));
		assertEquals("herblore,slayer", values.get(FakeConfigManager.key(SYNC, "tagtabs")));
		verify(plugin).reinitBank();
	}

	@Test
	public void firstEnableWithRemoteTagsAppliesRemoteAndKeepsLocalUntouched() throws Exception
	{
		seedLocalTab(LOCAL, "local only", 1, Collections.singletonList(1), null);
		Map<String, String> localBefore = FakeConfigManager.group(values, LOCAL);
		route("GET", "/bank-tags", json(200, manifest(42, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 7, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 952, Arrays.asList(199, 201, -203), new int[]{199, 201, -1, 203}, 7, false)));
		route("GET", "/bank-tags/" + SLAYER_ID, json(200, tagDoc(SLAYER_ID, "slayer", 4151, Collections.singletonList(4151), null, 1, false)));

		coordinator.start();
		await(() -> metadata.tag(SLAYER_ID) != null && metadata.tag(HERB_ID) != null);

		assertEquals(localBefore, FakeConfigManager.group(values, LOCAL));
		assertEquals("herblore,slayer", values.get(FakeConfigManager.key(SYNC, "tagtabs")));
		assertEquals("952", values.get(FakeConfigManager.key(SYNC, "icon_herblore")));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_199")));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_-203")));
		assertEquals("199,201,-1,203", values.get(FakeConfigManager.key(SYNC, "layout_herblore")));
		assertEquals(7, metadata.tag(HERB_ID).revision);
		assertEquals(42, metadata.groupRevision());
		assertEquals(3, metadata.orderRevision());
		assertEquals(Arrays.asList("herblore", "slayer"), tabManager.tabNames());
		verify(plugin).reinitBank();

		// nothing to upload: the applied state is the base state
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(0, requests("PUT").size());
	}

	@Test
	public void firstEnableUnauthorizedDoesNothing() throws Exception
	{
		seedLocalTab(LOCAL, "herblore", 952, Arrays.asList(199, 201), null);
		Map<String, String> before = new java.util.LinkedHashMap<>(values);
		route("GET", "/bank-tags", json(401, "{\"error\":\"unauthorized\",\"message\":\"nope\"}"));

		coordinator.start();
		await(() -> requests.size() == 1);
		Thread.sleep(200);
		scheduler.runDue(POLL);
		Thread.sleep(200);

		assertEquals(before, new java.util.LinkedHashMap<>(values));
		assertFalse(storage.isSyncStorageActive());
		assertEquals(1, requests.size());
		assertEquals(0, scheduler.pendingCount());
	}

	// ------------------------------------------------------------------ uploads

	@Test
	public void localMutationDebouncesIntoOneUpload() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);

		coordinator.onTagMutated("herblore");
		coordinator.onTagMutated("herblore");
		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 1 && metadata.tag(HERB_ID).revision == 8);

		RecordedRequest put = requests("PUT").get(0);
		assertEquals(BASE + "/bank-tags/" + HERB_ID, put.getPath());
		assertEquals("\"7\"", put.getHeader("If-Match"));
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(1, requests("PUT").size());
	}

	@Test
	public void mutationsToTwoTagsUploadIndependently() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		tagManager.addTag(301, "slayer", false);

		coordinator.onTagMutated("herblore");
		coordinator.onTagMutated("slayer");
		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 2 && metadata.tag(HERB_ID).revision > 7 && metadata.tag(SLAYER_ID).revision > 1);

		List<String> paths = new ArrayList<>();
		for (RecordedRequest put : requests("PUT"))
		{
			paths.add(put.getPath());
		}
		Collections.sort(paths);
		List<String> expected = new ArrayList<>(Arrays.asList(BASE + "/bank-tags/" + HERB_ID, BASE + "/bank-tags/" + SLAYER_ID));
		Collections.sort(expected);
		assertEquals(expected, paths);
	}

	@Test
	public void uploadSuccessStoresRevisionAndHashOfSentSnapshot() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		String sentHash = snapshots.snapshot("herblore").contentHash();
		// the server answers with a document that differs from what was sent
		route("PUT", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 1, Collections.singletonList(1), null, 11, false)));

		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.tag(HERB_ID).revision == 11);

		assertEquals(sentHash, metadata.tag(HERB_ID).baseHash);
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_300")));
	}

	@Test
	public void staleRevisionConflictFetchesRemoteAndRecordsConflict() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		route("PUT", "/bank-tags/" + HERB_ID, json(409, "{\"error\":\"stale_revision\",\"message\":\"stale\",\"current\":"
			+ "{\"tagId\":\"" + HERB_ID + "\",\"name\":\"herblore\",\"revision\":9,\"deleted\":false}}"));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 952, Arrays.asList(500, 501), null, 9, false)));

		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.conflict(HERB_ID) != null);

		Conflict conflict = metadata.conflict(HERB_ID);
		assertEquals("stale_revision", conflict.reason);
		assertEquals(9, conflict.remote.getRevision());
		assertEquals(Arrays.asList(500, 501), conflict.remote.getItemIds());
		assertEquals(7, metadata.tag(HERB_ID).revision);
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_300")));
		assertEquals(1, requests("GET", "/bank-tags/" + HERB_ID).size());

		// a conflicted tag is not uploaded again until the conflict is resolved
		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(1, requests("PUT").size());
	}

	// ------------------------------------------------------------------ polling

	@Test
	public void cleanRemoteUpdateAutoApplies() throws Exception
	{
		startSynced();
		route("GET", "/bank-tags", json(200, manifest(43, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 8, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 5678, Arrays.asList(1, 2, -3), new int[]{1, -1, 2}, 8, false)));

		scheduler.runDue(0);
		await(() -> metadata.tag(HERB_ID).revision == 8 && !coordinator.isPollInFlight());

		assertEquals("\"42\"", requests("GET", "/bank-tags").get(0).getHeader("If-None-Match"));
		assertEquals("5678", values.get(FakeConfigManager.key(SYNC, "icon_herblore")));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_1")));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_-3")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "item_199")));
		assertEquals("1,-1,2", values.get(FakeConfigManager.key(SYNC, "layout_herblore")));
		assertEquals(snapshots.snapshot("herblore").contentHash(), metadata.tag(HERB_ID).baseHash);
		assertEquals(43, metadata.groupRevision());
		verify(tabInterface, times(1)).refreshTabs();
		assertNull(metadata.conflict(HERB_ID));
	}

	@Test
	public void dirtyRemoteUpdateRecordsConflictAndLeavesLocalUntouched() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		Map<String, String> syncDataBefore = syncDataKeys();
		route("GET", "/bank-tags", json(200, manifest(43, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 8, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 5678, Arrays.asList(1, 2), null, 8, false)));

		scheduler.runDue(0);
		await(() -> metadata.conflict(HERB_ID) != null && !coordinator.isPollInFlight());

		Conflict conflict = metadata.conflict(HERB_ID);
		assertEquals("remote_changed", conflict.reason);
		assertEquals(8, conflict.remote.getRevision());
		assertEquals(7, metadata.tag(HERB_ID).revision);
		assertEquals(syncDataBefore, syncDataKeys());
	}

	@Test
	public void remoteTombstoneDeletesCleanLocalTag() throws Exception
	{
		startSynced();
		route("GET", "/bank-tags", json(200, manifest(43, 4, Collections.singletonList(SLAYER_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 8, true), entry(SLAYER_ID, "slayer", 1, false)))));

		scheduler.runDue(0);
		await(() -> metadata.tag(HERB_ID) == null && !coordinator.isPollInFlight());

		assertNull(tabManager.find("herblore"));
		assertEquals("slayer", values.get(FakeConfigManager.key(SYNC, "tagtabs")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "icon_herblore")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "item_199")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "layout_herblore")));
		assertEquals(0, requests("GET", "/bank-tags/" + HERB_ID).size());
		assertEquals(4, metadata.orderRevision());
	}

	@Test
	public void unknownRemoteTagCreatesLocalTab() throws Exception
	{
		startSynced();
		route("GET", "/bank-tags", json(200, manifest(43, 4, Arrays.asList(HERB_ID, SLAYER_ID, THIRD_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 7, false), entry(SLAYER_ID, "slayer", 1, false), entry(THIRD_ID, "runecraft", 1, false)))));
		route("GET", "/bank-tags/" + THIRD_ID, json(200, tagDoc(THIRD_ID, "runecraft", 1436, Arrays.asList(556, 557), null, 1, false)));

		scheduler.runDue(0);
		await(() -> metadata.tag(THIRD_ID) != null && !coordinator.isPollInFlight());

		assertNotNull(tabManager.find("runecraft"));
		assertEquals(Arrays.asList("herblore", "slayer", "runecraft"), tabManager.tabNames());
		assertEquals("1436", values.get(FakeConfigManager.key(SYNC, "icon_runecraft")));
		assertEquals("runecraft", values.get(FakeConfigManager.key(SYNC, "item_556")));
		assertEquals("runecraft", metadata.tag(THIRD_ID).name);
		assertEquals(0, requests("GET", "/bank-tags/" + HERB_ID).size());
	}

	@Test
	public void remoteApplyDoesNotTriggerUpload() throws Exception
	{
		startSynced();
		AtomicBoolean applyingDuringApply = new AtomicBoolean();
		AtomicBoolean applySeen = new AtomicBoolean();
		doAnswer(invocation ->
		{
			applySeen.set(true);
			applyingDuringApply.set(coordinator.isApplyingRemote());
			return invocation.callRealMethod();
		}).when(snapshots).apply(any(), any());
		route("GET", "/bank-tags", json(200, manifest(43, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 8, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 5678, Arrays.asList(1, 2), null, 8, false)));

		scheduler.runDue(0);
		await(() -> metadata.tag(HERB_ID).revision == 8 && !coordinator.isPollInFlight());

		assertTrue(applySeen.get());
		assertTrue("applyingRemote must be true while applying", applyingDuringApply.get());
		assertFalse(coordinator.isApplyingRemote());

		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(0, requests("PUT").size());
	}

	@Test
	public void onlyOnePollInFlight() throws Exception
	{
		startSynced();
		route("GET", "/bank-tags", new MockResponse().setResponseCode(304).setHeadersDelay(500, TimeUnit.MILLISECONDS));
		route("GET", "/bank-tags", new MockResponse().setResponseCode(304));

		scheduler.runDue(0);
		await(() -> requests("GET", "/bank-tags").size() == 1);
		assertTrue(coordinator.isPollInFlight());
		scheduler.runDue(POLL);
		Thread.sleep(100);
		assertEquals(1, requests("GET", "/bank-tags").size());

		await(() -> !coordinator.isPollInFlight());
		assertEquals(1, requests("GET", "/bank-tags").size());

		scheduler.runDue(POLL);
		await(() -> requests("GET", "/bank-tags").size() == 2);
	}

	@Test
	public void invalidJsonLeavesLocalUnchanged() throws Exception
	{
		startSynced();
		Map<String, String> before = new java.util.LinkedHashMap<>(values);
		route("GET", "/bank-tags", json(200, "{not json"));
		route("GET", "/bank-tags", json(200, manifest(43, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 8, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, "{\"schemaVersion\":1,\"tagId\":\"" + HERB_ID + "\"}"));

		scheduler.runDue(0);
		await(() -> requests("GET", "/bank-tags").size() == 1 && !coordinator.isPollInFlight());
		assertEquals(before, new java.util.LinkedHashMap<>(values));

		scheduler.runDue(POLL);
		await(() -> requests("GET", "/bank-tags/" + HERB_ID).size() == 1 && !coordinator.isPollInFlight());
		assertEquals(before, new java.util.LinkedHashMap<>(values));
		verify(tabInterface, never()).refreshTabs();
	}

	// ------------------------------------------------------------------ deletes, rename, order

	@Test
	public void deleteSendsIfMatchAndRemovesMetadata() throws Exception
	{
		startSynced();
		route("DELETE", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 0, Collections.emptyList(), null, 8, true)));

		snapshots.delete("herblore");
		coordinator.onTagDeleted("herblore");
		assertNull(metadata.tag(HERB_ID));
		assertEquals(Long.valueOf(7), metadata.pendingDelete(HERB_ID));

		scheduler.runDue(DEBOUNCE);
		await(() -> requests("DELETE").size() == 1 && metadata.pendingDelete(HERB_ID) == null);
		assertEquals("\"7\"", requests("DELETE").get(0).getHeader("If-Match"));
		assertEquals(BASE + "/bank-tags/" + HERB_ID, requests("DELETE").get(0).getPath());
		assertNull(metadata.tag(HERB_ID));
	}

	@Test
	public void deleteOfNeverUploadedTagSendsNothing() throws Exception
	{
		startSynced();
		seedLocalTab(SYNC, "fresh", 1, Collections.singletonList(1), null);
		tabManager.reload();
		coordinator.onTagMutated("fresh");
		String freshId = metadata.tagIdForName("fresh");
		assertNotNull(freshId);
		assertEquals(0, metadata.tag(freshId).revision);

		snapshots.delete("fresh");
		coordinator.onTagDeleted("fresh");
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);

		assertEquals(0, requests("PUT").size());
		assertEquals(0, requests("DELETE").size());
		assertNull(metadata.tag(freshId));
		assertNull(metadata.pendingDelete(freshId));
	}

	@Test
	public void renameKeepsTagIdAndUploads() throws Exception
	{
		startSynced();
		tagManager.renameTag("herblore", "herbs");
		tabManager.rename("herblore", "herbs");
		layoutManager.renameLayout("herblore", "herbs");

		coordinator.onTagRenamed("herblore", "herbs");
		assertEquals("herbs", metadata.tag(HERB_ID).name);
		assertNull(metadata.tagIdForName("herblore"));

		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 1 && metadata.tag(HERB_ID).revision == 8);
		RecordedRequest put = requests("PUT").get(0);
		assertEquals(BASE + "/bank-tags/" + HERB_ID, put.getPath());
		assertEquals("\"7\"", put.getHeader("If-Match"));
		assertEquals("herbs", gson.fromJson(put.getBody().readUtf8(), JsonObject.class).get("name").getAsString());
		assertEquals(HERB_ID, metadata.tagIdForName("herbs"));
	}

	@Test
	public void orderConflictReconcilesAndRetriesOnce() throws Exception
	{
		startSynced();
		tabManager.reorder(Arrays.asList("slayer", "herblore"));
		route("PUT", "/bank-tag-order", json(409, "{\"error\":\"stale_revision\",\"message\":\"stale\",\"current\":"
			+ manifest(50, 5, Arrays.asList(HERB_ID, THIRD_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 7, false), entry(SLAYER_ID, "slayer", 2, true), entry(THIRD_ID, "runecraft", 1, false))) + "}"));
		route("PUT", "/bank-tag-order", json(409, "{\"error\":\"stale_revision\",\"message\":\"stale again\",\"current\":"
			+ manifest(51, 6, Arrays.asList(THIRD_ID, HERB_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 7, false), entry(SLAYER_ID, "slayer", 2, true), entry(THIRD_ID, "runecraft", 1, false))) + "}"));

		coordinator.onTabOrderChanged();
		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT", "/bank-tag-order").size() == 2);
		Thread.sleep(200);

		List<RecordedRequest> puts = requests("PUT", "/bank-tag-order");
		assertEquals(2, puts.size());
		assertEquals("\"3\"", puts.get(0).getHeader("If-Match"));
		assertEquals(Arrays.asList(SLAYER_ID, HERB_ID), orderedIds(puts.get(0)));
		assertEquals("\"5\"", puts.get(1).getHeader("If-Match"));
		assertEquals(Arrays.asList(HERB_ID, THIRD_ID), orderedIds(puts.get(1)));
		assertEquals(3, metadata.orderRevision());
	}

	@Test
	public void orderUploadSuccessStoresOrderRevision() throws Exception
	{
		startSynced();
		scheduler.runDue(0);
		await(() -> requests("GET", "/bank-tags").size() == 1 && !coordinator.isPollInFlight());
		tabManager.reorder(Arrays.asList("slayer", "herblore"));
		route("PUT", "/bank-tag-order", json(200, manifest(43, 4, Arrays.asList(SLAYER_ID, HERB_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 7, false), entry(SLAYER_ID, "slayer", 1, false)))));

		coordinator.onTabOrderChanged();
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.orderRevision() == 4 && !coordinator.isPollInFlight());

		assertEquals(Arrays.asList(SLAYER_ID, HERB_ID), orderedIds(requests("PUT", "/bank-tag-order").get(0)));
		assertEquals(43, metadata.groupRevision());
		assertEquals(Arrays.asList("slayer", "herblore"), tabManager.tabNames());
	}

	@Test
	public void stopCancelsPollAndDebounce() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		coordinator.onTagMutated("herblore");
		coordinator.onTabOrderChanged();

		coordinator.stop();
		scheduler.runDue(POLL * 3);
		Thread.sleep(200);

		assertEquals(0, requests.size());
		assertEquals(0, scheduler.pendingCount());
		coordinator.onTagMutated("herblore");
		assertEquals(0, scheduler.pendingCount());
	}

	@Test
	public void startIsNoOpWhenDisabledOrUnconfigured() throws Exception
	{
		when(config.enabled()).thenReturn(false);
		coordinator.start();
		when(config.enabled()).thenReturn(true);
		when(config.groupToken()).thenReturn("  ");
		coordinator.start();
		Thread.sleep(100);
		assertEquals(0, requests.size());
		assertEquals(0, scheduler.pendingCount());
	}

	// ------------------------------------------------------------------ status, backoff, credentials

	@Test
	public void unauthorizedStopsPollingUntilConfigChange() throws Exception
	{
		startSynced();
		assertEquals(GlobalState.INITIALIZING, coordinator.globalState());
		route("GET", "/bank-tags", json(401, "{\"error\":\"unauthorized\",\"message\":\"nope\"}"));

		scheduler.runDue(0);
		await(() -> coordinator.globalState() == GlobalState.INVALID_CREDENTIALS);
		verify(tabInterface).sendChatMessage(BankTagSyncCoordinator.MSG_INVALID_CREDENTIALS);
		assertEquals(0, scheduler.pendingCount());

		// local edits are still tracked but nothing is sent, and no poll is rescheduled
		tagManager.addTag(300, "herblore", false);
		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		scheduler.runDue(POLL * 3);
		Thread.sleep(200);
		assertEquals(1, requests.size());
		assertEquals(GlobalState.INVALID_CREDENTIALS, coordinator.globalState());

		// the settings-change path (stop + start) is the only way back
		coordinator.stop();
		assertEquals(GlobalState.DISABLED, coordinator.globalState());
		coordinator.start();
		assertEquals(1, scheduler.pendingCount());
		scheduler.runDue(0);
		await(() -> coordinator.globalState() == GlobalState.ONLINE && !coordinator.isPollInFlight());
		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 1);
		verify(tabInterface, times(1)).sendChatMessage(BankTagSyncCoordinator.MSG_INVALID_CREDENTIALS);
	}

	@Test
	public void networkFailureBacksOffExponentiallyAndCaps() throws Exception
	{
		startSynced();
		long[] expected = {10, 20, 40, 80, 160, 300, 300};
		for (int i = 0; i < expected.length; i++)
		{
			route("GET", "/bank-tags", json(500, "{\"error\":\"internal\",\"message\":\"boom\"}"));
		}

		long advance = 0;
		for (int i = 0; i < expected.length; i++)
		{
			final int polls = i + 1;
			scheduler.runDue(advance);
			await(() -> requests("GET", "/bank-tags").size() == polls && !coordinator.isPollInFlight());
			assertEquals(GlobalState.OFFLINE, coordinator.globalState());
			assertEquals(1, scheduler.pendingCount());
			assertEquals("delay after failure " + polls, expected[i], scheduler.nextDelaySeconds());
			advance = expected[i];
		}
		verify(tabInterface, times(1)).sendChatMessage(BankTagSyncCoordinator.MSG_OFFLINE);

		// success resets the backoff to the configured interval
		scheduler.runDue(advance);
		await(() -> coordinator.globalState() == GlobalState.ONLINE && !coordinator.isPollInFlight());
		assertEquals(POLL, scheduler.nextDelaySeconds());
		verify(tabInterface, times(1)).sendChatMessage(BankTagSyncCoordinator.MSG_RECONNECTED);
		assertEquals(8, requests("GET", "/bank-tags").size());
	}

	@Test
	public void reconnectResetsBackoffAndResendsDirtyTags() throws Exception
	{
		startSynced();
		route("GET", "/bank-tags", json(500, "{\"error\":\"internal\",\"message\":\"boom\"}"));
		scheduler.runDue(0);
		await(() -> coordinator.globalState() == GlobalState.OFFLINE && !coordinator.isPollInFlight());
		verify(tabInterface, atLeastOnce()).refreshTabs();

		// offline: the debounce fires but nothing is sent
		tagManager.addTag(300, "herblore", false);
		coordinator.onTagMutated("herblore");
		assertEquals(TagState.PENDING, coordinator.tagState("herblore"));
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(0, requests("PUT").size());
		assertEquals(TagState.PENDING, coordinator.tagState("herblore"));

		// the backed-off poll (10s after the failure) succeeds and the sweep re-schedules the dirty tag
		scheduler.runDue(POLL - DEBOUNCE);
		await(() -> coordinator.globalState() == GlobalState.ONLINE && !coordinator.isPollInFlight());
		verify(tabInterface).sendChatMessage(BankTagSyncCoordinator.MSG_RECONNECTED);
		assertEquals(2, scheduler.pendingCount()); // next poll + debounced upload
		scheduler.runDue(DEBOUNCE);
		await(() -> requests("PUT").size() == 1 && metadata.tag(HERB_ID).revision == 8);
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
		assertEquals(POLL - DEBOUNCE, scheduler.nextDelaySeconds());
	}

	// ------------------------------------------------------------------ recovery actions

	@Test
	public void useRemoteVersionAppliesAndClearsConflict() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		route("GET", "/bank-tags", json(200, manifest(43, 3, Arrays.asList(
			entry(HERB_ID, "herblore", 8, false), entry(SLAYER_ID, "slayer", 1, false)))));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 5678, Arrays.asList(1, 2), null, 8, false)));

		scheduler.runDue(0);
		await(() -> metadata.conflict(HERB_ID) != null && !coordinator.isPollInFlight());
		assertEquals(TagState.CONFLICTED, coordinator.tagState("herblore"));
		verify(tabInterface).sendChatMessage("Bank tag sync: 'herblore' changed on the server and locally. Right-click the tab to resolve.");

		coordinator.useRemoteVersion("herblore");

		assertNull(metadata.conflict(HERB_ID));
		assertEquals(8, metadata.tag(HERB_ID).revision);
		assertEquals("5678", values.get(FakeConfigManager.key(SYNC, "icon_herblore")));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_1")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "item_300")));
		assertEquals(snapshots.snapshot("herblore").contentHash(), metadata.tag(HERB_ID).baseHash);
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
		verify(tabInterface, times(2)).refreshTabs();

		// the applied remote state is not uploaded back
		scheduler.runDue(POLL);
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(0, requests("PUT").size());
	}

	@Test
	public void useRemoteVersionOnTombstoneDeletesLocally() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		route("GET", "/bank-tags", json(200, manifest(43, 4, Collections.singletonList(SLAYER_ID), Arrays.asList(
			entry(HERB_ID, "herblore", 8, true), entry(SLAYER_ID, "slayer", 1, false)))));

		scheduler.runDue(0);
		await(() -> metadata.conflict(HERB_ID) != null && !coordinator.isPollInFlight());
		assertTrue(metadata.conflict(HERB_ID).remote.isDeleted());
		assertNotNull(tabManager.find("herblore"));

		coordinator.useRemoteVersion("herblore");

		assertNull(metadata.conflict(HERB_ID));
		assertNull(metadata.tag(HERB_ID));
		assertNull(tabManager.find("herblore"));
		assertEquals("slayer", values.get(FakeConfigManager.key(SYNC, "tagtabs")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "icon_herblore")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "item_300")));
		assertEquals(TagState.LOCAL_ONLY, coordinator.tagState("herblore"));

		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(0, requests("DELETE").size());
		assertEquals(0, requests("PUT").size());
	}

	@Test
	public void overwriteRemoteVersionUsesConflictRevision() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		String staleRevision = new String(Files.readAllBytes(
			Paths.get("src", "test", "resources", "fixtures", "sync", "v1", "error-stale-revision.json")), StandardCharsets.UTF_8);
		route("PUT", "/bank-tags/" + HERB_ID, json(409, staleRevision));
		route("GET", "/bank-tags/" + HERB_ID, json(200, tagDoc(HERB_ID, "herblore", 952, Arrays.asList(500, 501), null, 8, false)));

		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.conflict(HERB_ID) != null);
		assertEquals(8, metadata.conflict(HERB_ID).remote.getRevision());
		assertEquals(TagState.CONFLICTED, coordinator.tagState("herblore"));

		coordinator.overwriteRemoteVersion("herblore");
		await(() -> requests("PUT").size() == 2 && metadata.tag(HERB_ID).revision == 9);

		RecordedRequest overwrite = requests("PUT").get(1);
		assertEquals(BASE + "/bank-tags/" + HERB_ID, overwrite.getPath());
		assertEquals("\"8\"", overwrite.getHeader("If-Match"));
		assertNull(overwrite.getHeader("If-None-Match"));
		assertNull(metadata.conflict(HERB_ID));
		assertEquals("herblore", values.get(FakeConfigManager.key(SYNC, "item_300")));
		assertEquals(snapshots.snapshot("herblore").contentHash(), metadata.tag(HERB_ID).baseHash);
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
	}

	@Test
	public void overwriteRemoteOnTombstoneMintsNewId() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		route("PUT", "/bank-tags/" + HERB_ID, json(404, "{\"error\":\"tag_not_found\",\"message\":\"gone\"}"));

		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.conflict(HERB_ID) != null);
		assertTrue(metadata.conflict(HERB_ID).remote.isDeleted());

		coordinator.overwriteRemoteVersion("herblore");
		await(() -> tagRequests("PUT").size() == 2 && metadata.tag(metadata.tagIdForName("herblore")).revision > 0);

		String newId = metadata.tagIdForName("herblore");
		assertNotEquals(HERB_ID, newId);
		assertNull(metadata.tag(HERB_ID));
		assertNull(metadata.conflict(HERB_ID));
		RecordedRequest create = tagRequests("PUT").get(1);
		assertEquals(BASE + "/bank-tags/" + newId, create.getPath());
		assertEquals("*", create.getHeader("If-None-Match"));
		assertNull(create.getHeader("If-Match"));
		assertEquals("herblore", gson.fromJson(create.getBody().readUtf8(), JsonObject.class).get("name").getAsString());
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
	}

	@Test
	public void retryClearsRejection() throws Exception
	{
		startSynced();
		tagManager.addTag(300, "herblore", false);
		route("PUT", "/bank-tags/" + HERB_ID, json(400, "{\"error\":\"invalid_tag\",\"message\":\"bad\"}"));

		coordinator.onTagMutated("herblore");
		scheduler.runDue(DEBOUNCE);
		await(() -> coordinator.tagState("herblore") == TagState.REJECTED);
		verify(tabInterface).sendChatMessage("Bank tag sync: 'herblore' was rejected by the server (invalid_tag).");
		assertEquals(7, metadata.tag(HERB_ID).revision);

		// the poll sweep leaves a rejected tag alone
		scheduler.runDue(POLL);
		await(() -> !coordinator.isPollInFlight());
		scheduler.runDue(DEBOUNCE);
		Thread.sleep(200);
		assertEquals(1, requests("PUT").size());
		assertEquals(TagState.REJECTED, coordinator.tagState("herblore"));

		coordinator.retry("herblore");
		await(() -> requests("PUT").size() == 2 && metadata.tag(HERB_ID).revision == 8);
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
		assertEquals(0, scheduler.nextDelaySeconds()); // an immediate poll was scheduled
		scheduler.runDue(0);
		await(() -> requests("GET", "/bank-tags").size() >= 2 && !coordinator.isPollInFlight());
	}

	@Test
	public void tagStateTransitions() throws Exception
	{
		startSynced();
		assertEquals(TagState.SYNCED, coordinator.tagState("herblore"));
		assertEquals(TagState.LOCAL_ONLY, coordinator.tagState("nope"));

		seedLocalTab(SYNC, "fresh", 1, Collections.singletonList(1), null);
		tabManager.reload();
		assertEquals(TagState.LOCAL_ONLY, coordinator.tagState("fresh"));

		coordinator.onTagMutated("fresh");
		assertEquals(TagState.PENDING, coordinator.tagState("fresh"));
		String freshId = metadata.tagIdForName("fresh");
		scheduler.runDue(DEBOUNCE);
		await(() -> metadata.tag(freshId).revision > 0);
		assertEquals(TagState.SYNCED, coordinator.tagState("fresh"));

		tagManager.addTag(2, "fresh", false);
		assertEquals(TagState.PENDING, coordinator.tagState("fresh"));
		route("PUT", "/bank-tags/" + freshId, json(409, "{\"error\":\"stale_revision\",\"message\":\"stale\",\"current\":"
			+ "{\"tagId\":\"" + freshId + "\",\"name\":\"fresh\",\"revision\":5,\"deleted\":false}}"));
		route("GET", "/bank-tags/" + freshId, json(200, tagDoc(freshId, "fresh", 1, Arrays.asList(1, 3), null, 5, false)));
		coordinator.onTagMutated("fresh");
		scheduler.runDue(DEBOUNCE);
		await(() -> coordinator.tagState("fresh") == TagState.CONFLICTED);

		coordinator.useRemoteVersion("fresh");
		assertEquals(TagState.SYNCED, coordinator.tagState("fresh"));
		assertEquals(5, metadata.tag(freshId).revision);
		assertEquals("fresh", values.get(FakeConfigManager.key(SYNC, "item_3")));
		assertNull(values.get(FakeConfigManager.key(SYNC, "item_2")));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Sync namespace already initialized with two synced tabs: herblore (rev 7) and slayer (rev 1),
	 * both clean, group revision 42, order revision 3. Polls answer 304 unless a route overrides it.
	 */
	private void startSynced()
	{
		values.put(FakeConfigManager.key(SYNC, BankTagsStorage.SYNC_STORAGE_INITIALIZED_KEY), "true");
		seedLocalTab(SYNC, "herblore", 952, Arrays.asList(199, 201, -203), new int[]{199, 201, -1, 203});
		seedLocalTab(SYNC, "slayer", 4151, Collections.singletonList(4151), null);
		tabManager.reload();
		metadata.putTag(HERB_ID, new TagMeta("herblore", 7, snapshots.snapshot("herblore").contentHash()));
		metadata.putTag(SLAYER_ID, new TagMeta("slayer", 1, snapshots.snapshot("slayer").contentHash()));
		metadata.setGroupRevision(42);
		metadata.setOrderRevision(3);
		requests.clear();

		coordinator.start();
		assertEquals(1, scheduler.pendingCount());
	}

	private void seedLocalTab(String group, String name, int icon, List<Integer> items, int[] layout)
	{
		String tabs = values.get(FakeConfigManager.key(group, "tagtabs"));
		values.put(FakeConfigManager.key(group, "tagtabs"), tabs == null || tabs.isEmpty() ? name : tabs + "," + name);
		values.put(FakeConfigManager.key(group, "icon_" + name), Integer.toString(icon));
		for (int item : items)
		{
			String key = FakeConfigManager.key(group, "item_" + item);
			String existing = values.get(key);
			values.put(key, existing == null ? name : existing + "," + name);
		}
		if (layout != null)
		{
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < layout.length; i++)
			{
				sb.append(i > 0 ? "," : "").append(layout[i]);
			}
			values.put(FakeConfigManager.key(group, "layout_" + name), sb.toString());
		}
	}

	private Map<String, String> syncDataKeys()
	{
		Map<String, String> result = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String> entry : FakeConfigManager.group(values, SYNC).entrySet())
		{
			if (!entry.getKey().startsWith(SYNC + ".sync"))
			{
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	private boolean allRevisionsPositive()
	{
		for (TagMeta meta : metadata.allTags().values())
		{
			if (meta.revision <= 0)
			{
				return false;
			}
		}
		return true;
	}

	private void route(String method, String path, MockResponse response)
	{
		routes.computeIfAbsent(method + " " + BASE + path, k -> new ArrayDeque<>()).addLast(response);
	}

	private List<RecordedRequest> requests(String method)
	{
		List<RecordedRequest> result = new ArrayList<>();
		for (RecordedRequest request : requests)
		{
			if (method.equals(request.getMethod()))
			{
				result.add(request);
			}
		}
		return result;
	}

	/**
	 * Requests of {@code method} against a tag document route (excludes the order route).
	 */
	private List<RecordedRequest> tagRequests(String method)
	{
		List<RecordedRequest> result = new ArrayList<>();
		for (RecordedRequest request : requests(method))
		{
			if (request.getPath().startsWith(BASE + "/bank-tags/"))
			{
				result.add(request);
			}
		}
		return result;
	}

	private List<RecordedRequest> requests(String method, String path)
	{
		List<RecordedRequest> result = new ArrayList<>();
		for (RecordedRequest request : requests(method))
		{
			if ((BASE + path).equals(request.getPath()))
			{
				result.add(request);
			}
		}
		return result;
	}

	private List<String> orderedIds(RecordedRequest request)
	{
		JsonObject body = gson.fromJson(request.getBody().clone().readUtf8(), JsonObject.class);
		List<String> ids = new ArrayList<>();
		for (com.google.gson.JsonElement element : body.getAsJsonArray("orderedTagIds"))
		{
			ids.add(element.getAsString());
		}
		return ids;
	}

	private static MockResponse json(int status, String body)
	{
		return new MockResponse().setResponseCode(status)
			.setHeader("Content-Type", "application/json")
			.setBody(body);
	}

	private static String entry(String tagId, String name, long revision, boolean deleted)
	{
		JsonObject object = new JsonObject();
		object.addProperty("tagId", tagId);
		object.addProperty("name", name);
		object.addProperty("revision", revision);
		object.addProperty("deleted", deleted);
		return object.toString();
	}

	private String manifest(long groupRevision, long orderRevision, List<String> entries)
	{
		List<String> ids = new ArrayList<>();
		for (String entry : entries)
		{
			JsonObject object = gson.fromJson(entry, JsonObject.class);
			if (!object.get("deleted").getAsBoolean())
			{
				ids.add(object.get("tagId").getAsString());
			}
		}
		return manifest(groupRevision, orderRevision, ids, entries);
	}

	private String manifest(long groupRevision, long orderRevision, List<String> orderedTagIds, List<String> entries)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", 1);
		object.addProperty("groupRevision", groupRevision);
		object.addProperty("orderRevision", orderRevision);
		JsonArray ids = new JsonArray();
		for (String id : orderedTagIds)
		{
			ids.add(id);
		}
		object.add("orderedTagIds", ids);
		JsonArray tags = new JsonArray();
		for (String entry : entries)
		{
			tags.add(gson.fromJson(entry, JsonObject.class));
		}
		object.add("tags", tags);
		return gson.toJson(object);
	}

	private String tagDoc(String tagId, String name, int icon, List<Integer> items, int[] layout, long revision, boolean deleted)
	{
		return new BankTagSyncJson(gson).tagDocument(new SharedBankTag(tagId, name, icon, items, layout, revision, deleted));
	}

	private static void await(BooleanSupplier condition) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline)
		{
			if (condition.getAsBoolean())
			{
				return;
			}
			Thread.sleep(10);
		}
		fail("condition not met within 3s");
	}
}
