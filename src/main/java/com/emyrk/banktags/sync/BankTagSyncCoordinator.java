package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsPlugin;
import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.sync.BankTagFolderSyncMetadata.FolderMeta;
import com.emyrk.banktags.sync.BankTagSyncMetadata.Conflict;
import com.emyrk.banktags.sync.BankTagSyncMetadata.TagMeta;
import com.emyrk.banktags.sync.BankTagSyncStatus.GlobalState;
import com.emyrk.banktags.sync.BankTagSyncStatus.TagState;
import com.emyrk.banktags.sync.model.BankTagFolderManifest;
import com.emyrk.banktags.sync.model.BankTagManifest;
import com.emyrk.banktags.sync.model.FolderManifestResult;
import com.emyrk.banktags.sync.model.ManifestResult;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.sync.model.SharedBankTagFolder;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.emyrk.banktags.tabs.TabInterface;
import com.emyrk.banktags.tabs.TabManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

/**
 * Automatic two-way per-tag synchronization (docs/remote-sync-protocol.md, decision tables).
 * <p>
 * Threading model:
 * <ul>
 * <li>The public API is called on the client thread. Every {@code on*} method is a no-op until the
 * coordinator is active and while a remote update is being applied ({@link #isApplyingRemote()}),
 * which is what prevents a remote change from being uploaded again as a local change.</li>
 * <li>Timers run on RuneLite's shared {@link ScheduledExecutorService}; the coordinator owns no
 * threads. Every scheduled future is tracked so {@link #stop()} can cancel it.</li>
 * <li>HTTP callbacks arrive on OkHttp threads and are hopped onto the client thread before any
 * manager, snapshot, or metadata access. Callbacks created before the last {@link #stop()} are
 * ignored (generation counter), so a stop/start cycle cannot be corrupted by in-flight replies.</li>
 * <li>Nothing blocks: no {@code Future.get()}, no synchronous HTTP.</li>
 * </ul>
 * <p>
 * Observable state ({@link GlobalState}, {@link TagState}) is derived from metadata and the
 * in-memory bookkeeping; every global transition goes through {@link #setGlobalState(GlobalState)},
 * which is the only place that emits connection chat messages.
 */
@Slf4j
@Singleton
public class BankTagSyncCoordinator
{
	/** Upper bound of the exponential poll backoff. */
	static final long MAX_BACKOFF_SECONDS = 300;

	static final String MSG_INVALID_CREDENTIALS = "Bank tag sync: invalid group name or token. Check the Bank Tags Extended settings.";
	static final String MSG_OFFLINE = "Bank tag sync: server unreachable, retrying in the background. Your tags still work.";
	static final String MSG_RECONNECTED = "Bank tag sync: reconnected.";

	private final BankTagSyncClient client;
	private final BankTagSyncMetadata metadata;
	private final BankTagFolderSyncMetadata folderMetadata;
	private final BankTagFolderManager folderManager;
	private final BankTagSnapshotService snapshots;
	private final BankTagsStorage storage;
	private final BankTagsConfig config;
	private final TabManager tabManager;
	// Provider: TabInterface -> coordinator -> TabInterface would otherwise be a constructor cycle.
	private final Provider<TabInterface> tabInterface;
	private final BankTagsPlugin plugin;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	/** {@link #start()} accepted the configuration; cleared by {@link #stop()}. */
	private volatile boolean started;
	/** The synchronized namespace is initialized: mutations are tracked and polling runs. */
	private volatile boolean active;
	/** Bumped by {@link #stop()}; callbacks from an older generation are dropped. */
	private final AtomicInteger epoch = new AtomicInteger();
	/** Client thread only. */
	private boolean applyingRemote;

	private volatile GlobalState globalState = GlobalState.DISABLED;
	/** Consecutive failed polls (or first-enable attempts); drives the backoff delay. */
	private int consecutiveFailures;
	/** Tags whose last upload the server refused with a non-retryable error. */
	private final Set<String> rejectedTagIds = ConcurrentHashMap.newKeySet();

	private final AtomicBoolean pollInFlight = new AtomicBoolean();
	/** The next scheduled probe: a poll, or a first-enable retry while backing off. */
	private volatile ScheduledFuture<?> pollFuture;
	private ScheduledFuture<?> orderFuture;
	private final Map<String, ScheduledFuture<?>> debounceFutures = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> deleteFutures = new ConcurrentHashMap<>();
	private final Set<String> uploadsInFlight = ConcurrentHashMap.newKeySet();
	private final Set<String> deletesInFlight = ConcurrentHashMap.newKeySet();
	private final Map<String, ScheduledFuture<?>> folderDebounceFutures = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> folderDeleteFutures = new ConcurrentHashMap<>();
	private final Set<String> folderUploadsInFlight = ConcurrentHashMap.newKeySet();
	private final Set<String> folderDeletesInFlight = ConcurrentHashMap.newKeySet();
	private ScheduledFuture<?> folderOrderFuture;
	private boolean folderOrderInFlight;
	private boolean folderOrderPending;
	private boolean orderInFlight;
	private boolean orderPending;

	@Inject
	public BankTagSyncCoordinator(BankTagSyncClient client, BankTagSyncMetadata metadata,
		BankTagFolderSyncMetadata folderMetadata, BankTagFolderManager folderManager,
		BankTagSnapshotService snapshots, BankTagsStorage storage, BankTagsConfig config,
		TabManager tabManager, Provider<TabInterface> tabInterface, BankTagsPlugin plugin,
		ScheduledExecutorService executor, ClientThread clientThread)
	{
		this.client = client;
		this.metadata = metadata;
		this.folderMetadata = folderMetadata;
		this.folderManager = folderManager;
		this.snapshots = snapshots;
		this.storage = storage;
		this.config = config;
		this.tabManager = tabManager;
		this.tabInterface = tabInterface;
		this.plugin = plugin;
		this.executor = executor;
		this.clientThread = clientThread;
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Starts synchronization when it is enabled and configured. Idempotent.
	 */
	public void start()
	{
		if (started)
		{
			return;
		}
		if (!config.enabled() || isBlank(config.groupName()) || isBlank(config.groupToken()))
		{
			setGlobalState(GlobalState.DISABLED);
			return;
		}
		started = true;
		consecutiveFailures = 0;
		setGlobalState(GlobalState.INITIALIZING);

		if (storage.isSyncStorageActive())
		{
			activate();
			return;
		}
		firstEnable(epoch.get());
	}

	/**
	 * Cancels polling, every pending debounce, and every in-flight request. Idempotent.
	 */
	public void stop()
	{
		epoch.incrementAndGet();
		started = false;
		active = false;

		cancelScheduledWork();
		uploadsInFlight.clear();
		deletesInFlight.clear();
		folderUploadsInFlight.clear();
		folderDeletesInFlight.clear();
		rejectedTagIds.clear();
		folderOrderInFlight = false;
		folderOrderPending = false;
		orderInFlight = false;
		orderPending = false;
		pollInFlight.set(false);
		consecutiveFailures = 0;
		client.cancelAll();
		setGlobalState(GlobalState.DISABLED);
	}

	private void cancelScheduledWork()
	{
		cancel(pollFuture);
		pollFuture = null;
		cancel(orderFuture);
		orderFuture = null;
		for (ScheduledFuture<?> future : debounceFutures.values())
		{
			future.cancel(false);
		}
		debounceFutures.clear();
		for (ScheduledFuture<?> future : deleteFutures.values())
		{
			future.cancel(false);
		}
		deleteFutures.clear();
		for (ScheduledFuture<?> future : folderDebounceFutures.values())
		{
			future.cancel(false);
		}
		folderDebounceFutures.clear();
		for (ScheduledFuture<?> future : folderDeleteFutures.values())
		{
			future.cancel(false);
		}
		folderDeleteFutures.clear();
		cancel(folderOrderFuture);
		folderOrderFuture = null;
	}

	public boolean isApplyingRemote()
	{
		return applyingRemote;
	}

	/**
	 * Test hook: whether a manifest poll is currently outstanding.
	 */
	boolean isPollInFlight()
	{
		return pollInFlight.get();
	}

	private void activate()
	{
		active = true;
		schedulePoll(0);
	}

	// ---------------------------------------------------------------- status

	public GlobalState globalState()
	{
		return globalState;
	}

	/**
	 * Client thread. Derives the state of one tab from metadata and the in-memory bookkeeping.
	 */
	public TagState tagState(String tag)
	{
		if (!active || tag == null)
		{
			return TagState.LOCAL_ONLY;
		}
		String name = Text.standardize(tag);
		String id = metadata.tagIdForName(name);
		if (conflictIdForName(name, id) != null)
		{
			return TagState.CONFLICTED;
		}
		if (id == null)
		{
			return TagState.LOCAL_ONLY;
		}
		if (rejectedTagIds.contains(id))
		{
			return TagState.REJECTED;
		}
		TagMeta meta = metadata.tag(id);
		if (debounceFutures.containsKey(id) || uploadsInFlight.contains(id) || (meta != null && isDirty(meta)))
		{
			return TagState.PENDING;
		}
		return TagState.SYNCED;
	}

	/**
	 * The id of the conflict record that concerns the tab called {@code name}: the tab's own id, or a
	 * remote tag of the same name that is waiting behind a {@code duplicate_name} conflict.
	 */
	private String conflictIdForName(String name, String localId)
	{
		if (localId != null && metadata.conflict(localId) != null)
		{
			return localId;
		}
		for (Map.Entry<String, Conflict> entry : metadata.allConflicts().entrySet())
		{
			if (name.equals(entry.getValue().remote.getName()))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * The single transition point for {@link GlobalState}: emits the connection chat messages once per
	 * transition and refreshes the tab menu when the {@code OFFLINE} retry entry may have changed.
	 */
	private void setGlobalState(GlobalState next)
	{
		GlobalState previous = globalState;
		if (previous == next)
		{
			return;
		}
		log.debug("bank tag sync state {} -> {}", previous, next);
		// message first, state second: an observer that sees the new state can rely on the message being queued
		switch (next)
		{
			case INVALID_CREDENTIALS:
				chat(MSG_INVALID_CREDENTIALS);
				break;
			case OFFLINE:
				chat(MSG_OFFLINE);
				break;
			case ONLINE:
				if (previous == GlobalState.OFFLINE)
				{
					chat(MSG_RECONNECTED);
				}
				break;
			default:
				break;
		}
		globalState = next;
		if (previous == GlobalState.OFFLINE || next == GlobalState.OFFLINE)
		{
			refreshTabs();
		}
	}

	/**
	 * Any request completed: the connection is healthy and the backoff is reset.
	 */
	private void onRequestSucceeded()
	{
		consecutiveFailures = 0;
		setGlobalState(GlobalState.ONLINE);
	}

	/**
	 * Applies the connection-level consequences of a failure. Returns {@code true} when the failure
	 * was a credentials or transport problem (the caller keeps the tag dirty), {@code false} when the
	 * failure is specific to the request.
	 */
	private boolean handleConnectionFailure(SyncFailure failure)
	{
		switch (failure.getKind())
		{
			case UNAUTHORIZED:
				onUnauthorized();
				return true;
			case NETWORK:
			case SERVER_ERROR:
				setGlobalState(GlobalState.OFFLINE);
				return true;
			default:
				return false;
		}
	}

	/**
	 * {@code 401}: stop every timer and send nothing more. {@link #stop()} followed by {@link #start()}
	 * (the settings change path) is the only way back.
	 */
	private void onUnauthorized()
	{
		cancelScheduledWork();
		setGlobalState(GlobalState.INVALID_CREDENTIALS);
	}

	/**
	 * Delay before the next probe after a transport failure: {@code interval * 2^failures}, capped.
	 */
	private long nextBackoffSeconds()
	{
		long interval = Math.max(1, config.pollIntervalSeconds());
		long delay = interval << Math.min(consecutiveFailures, 16);
		consecutiveFailures++;
		return Math.min(delay, MAX_BACKOFF_SECONDS);
	}

	private boolean sendingSuspended()
	{
		GlobalState state = globalState;
		return state == GlobalState.OFFLINE || state == GlobalState.INVALID_CREDENTIALS;
	}

	// ---------------------------------------------------------------- first enable

	private void firstEnable(int gen)
	{
		if (!started || gen != epoch.get())
		{
			return;
		}
		client.getManifest(null, onClientThread(gen, result ->
		{
			onRequestSucceeded();
			BankTagManifest manifest = result.getManifest();
			List<BankTagManifest.Entry> live = new ArrayList<>();
			if (manifest != null)
			{
				for (BankTagManifest.Entry entry : manifest.getTags())
				{
					if (!entry.isDeleted())
					{
						live.add(entry);
					}
				}
			}
			if (live.isEmpty())
			{
				seedFromLocal(manifest);
				return;
			}
			List<String> ids = new ArrayList<>(live.size());
			for (BankTagManifest.Entry entry : live)
			{
				ids.add(entry.getTagId());
			}
			fetchAll(gen, ids, fetched -> seedFromRemote(manifest, fetched), failure -> onFirstEnableFailed(gen, failure));
		}, failure -> onFirstEnableFailed(gen, failure)));
	}

	private void onFirstEnableFailed(int gen, SyncFailure failure)
	{
		log.debug("bank tag sync first enable failed: {}", failure.getKind());
		switch (failure.getKind())
		{
			case UNAUTHORIZED:
				onUnauthorized();
				break;
			case NETWORK:
			case SERVER_ERROR:
			{
				long delay = nextBackoffSeconds();
				setGlobalState(GlobalState.OFFLINE);
				scheduleProbe(() -> clientThread.invoke(() -> firstEnable(gen)), delay);
				break;
			}
			default:
				started = false;
				setGlobalState(GlobalState.DISABLED);
				chat("Bank tag sync: the server sent an unexpected response. Sync is off until the settings change.");
				break;
		}
	}

	/**
	 * The group has no tags yet: the local tags become the group's tags.
	 */
	private void seedFromLocal(BankTagManifest manifest)
	{
		storage.initializeSyncStorageFromLocal();
		tabManager.reload();

		List<String> ids = new ArrayList<>();
		for (String name : tabManager.tabNames())
		{
			ids.add(resolveTagId(name));
		}
		if (manifest != null)
		{
			metadata.setGroupRevision(manifest.getGroupRevision());
			metadata.setOrderRevision(manifest.getOrderRevision());
		}
		plugin.reinitBank();
		activate();
		for (String id : ids)
		{
			scheduleUpload(id);
		}
	}

	/**
	 * The group already has tags: they become the synchronized cache; local tags stay untouched.
	 */
	private void seedFromRemote(BankTagManifest manifest, Map<String, SharedBankTag> fetched)
	{
		int loaded = 0;
		applyingRemote = true;
		try
		{
			storage.markSyncStorageInitialized();
			tabManager.reload();

			List<String> names = new ArrayList<>();
			for (String id : orderedIds(manifest, fetched.keySet()))
			{
				SharedBankTag tag = fetched.get(id);
				if (tag == null || tag.isDeleted())
				{
					continue;
				}
				snapshots.apply(null, tag);
				metadata.putTag(id, new TagMeta(tag.getName(), tag.getRevision(), tag.contentHash()));
				names.add(tag.getName());
				loaded++;
			}
			tabManager.reorder(names);
			metadata.setGroupRevision(manifest.getGroupRevision());
			metadata.setOrderRevision(manifest.getOrderRevision());
		}
		finally
		{
			applyingRemote = false;
		}
		plugin.reinitBank();
		chat("Bank tag sync: loaded " + loaded + " tags from your group. Your previous local tags are kept and are used again if you disable sync.");
		activate();
	}

	// ---------------------------------------------------------------- polling

	/**
	 * Client thread. Cancels the next scheduled poll and checks the server immediately.
	 * An already-running poll already satisfies the request and is left alone.
	 */
	public void forceResync()
	{
		if (!active)
		{
			return;
		}
		consecutiveFailures = 0;
		cancel(pollFuture);
		pollFuture = null;
		if (!pollInFlight.get())
		{
			poll();
		}
	}

	/**
	 * Replaces the next probe with a poll {@code delaySeconds} from now.
	 */
	private void schedulePoll(long delaySeconds)
	{
		if (!active)
		{
			return;
		}
		scheduleProbe(this::poll, delaySeconds);
	}

	private void scheduleProbe(Runnable probe, long delaySeconds)
	{
		if (!started || globalState == GlobalState.INVALID_CREDENTIALS)
		{
			return;
		}
		cancel(pollFuture);
		pollFuture = executor.schedule(probe, delaySeconds, TimeUnit.SECONDS);
	}

	private void poll()
	{
		if (!active)
		{
			return;
		}
		if (pollInFlight.getAndSet(true))
		{
			schedulePoll(config.pollIntervalSeconds());
			return;
		}
		final int gen = epoch.get();
		long revision = metadata.groupRevision();
		client.getManifest(revision == 0 ? null : revision, onClientThread(gen, result ->
		{
			onRequestSucceeded();
			if (result.isNotModified())
			{
				pollFolders(gen);
				return;
			}
			planRemoteChanges(gen, result.getManifest());
		}, failure ->
		{
			pollInFlight.set(false);
			onPollFailed(failure);
		}));
	}

	/**
	 * Client thread. A poll (manifest or one of its fetches) failed: back off, stop on {@code 401},
	 * or simply try again next interval for a malformed response.
	 */
	private void onPollFailed(SyncFailure failure)
	{
		log.debug("bank tag sync poll failed: {}", failure.getKind());
		switch (failure.getKind())
		{
			case UNAUTHORIZED:
				onUnauthorized();
				break;
			case NETWORK:
			case SERVER_ERROR:
			{
				long delay = nextBackoffSeconds();
				setGlobalState(GlobalState.OFFLINE);
				schedulePoll(delay);
				break;
			}
			default:
				schedulePoll(config.pollIntervalSeconds());
				break;
		}
	}

	/**
	 * Client thread. Applies the manifest decision table, fetches what is needed, then applies.
	 */
	private void planRemoteChanges(int gen, BankTagManifest manifest)
	{
		Map<String, TagMeta> local = metadata.allTags();
		Map<String, String> nameToId = new HashMap<>();
		for (Map.Entry<String, TagMeta> entry : local.entrySet())
		{
			nameToId.put(entry.getValue().name, entry.getKey());
		}

		List<String> toFetch = new ArrayList<>();
		Map<String, String> fetchAsConflict = new HashMap<>();
		List<String> toDelete = new ArrayList<>();
		Map<String, Conflict> tombstoneConflicts = new LinkedHashMap<>();

		for (BankTagManifest.Entry entry : manifest.getTags())
		{
			String id = entry.getTagId();
			TagMeta meta = local.get(id);
			if (meta == null)
			{
				if (entry.isDeleted() || metadata.pendingDelete(id) != null)
				{
					continue;
				}
				toFetch.add(id);
				String existingId = nameToId.get(entry.getName());
				boolean localOnlyTab = tabManager.find(entry.getName()) != null
					&& (existingId == null || local.get(existingId).revision == 0);
				if (localOnlyTab)
				{
					fetchAsConflict.put(id, BankTagSyncMetadata.REASON_DUPLICATE_NAME);
				}
				continue;
			}
			if (entry.getRevision() == meta.revision)
			{
				continue;
			}
			boolean dirty = isDirty(meta);
			if (entry.isDeleted())
			{
				if (dirty)
				{
					tombstoneConflicts.put(id, new Conflict(tombstone(id, meta.name, entry.getRevision()),
						BankTagSyncMetadata.REASON_REMOTE_CHANGED));
				}
				else
				{
					toDelete.add(id);
				}
			}
			else
			{
				toFetch.add(id);
				if (dirty)
				{
					fetchAsConflict.put(id, BankTagSyncMetadata.REASON_REMOTE_CHANGED);
				}
			}
		}

		boolean orderChanged = manifest.getOrderRevision() != metadata.orderRevision();
		fetchAll(gen, toFetch,
			fetched -> applyRemote(manifest, local, fetched, fetchAsConflict, toDelete, tombstoneConflicts, orderChanged),
			failure ->
			{
				pollInFlight.set(false);
				onPollFailed(failure);
			});
	}

	/**
	 * Client thread. Every fetch succeeded; apply everything with local observation suppressed.
	 */
	private void applyRemote(BankTagManifest manifest, Map<String, TagMeta> local, Map<String, SharedBankTag> fetched,
		Map<String, String> fetchAsConflict, List<String> toDelete, Map<String, Conflict> tombstoneConflicts,
		boolean orderChanged)
	{
		applyingRemote = true;
		try
		{
			for (String id : toDelete)
			{
				deleteLocally(id, local.get(id).name);
			}
			for (String id : orderedIds(manifest, fetched.keySet()))
			{
				SharedBankTag tag = fetched.get(id);
				TagMeta meta = local.get(id);
				String localName = meta == null ? tag.getName() : meta.name;
				String reason = fetchAsConflict.get(id);
				if (reason != null)
				{
					recordConflict(id, localName, new Conflict(tag, reason));
					continue;
				}
				if (tag.isDeleted())
				{
					if (meta != null)
					{
						deleteLocally(id, meta.name);
					}
					continue;
				}
				try
				{
					snapshots.apply(meta == null ? null : meta.name, tag);
					metadata.putTag(id, new TagMeta(tag.getName(), tag.getRevision(), tag.contentHash()));
					metadata.removeConflict(id);
					rejectedTagIds.remove(id);
					cancelDebounce(id);
				}
				catch (IllegalArgumentException ex)
				{
					// a local tab already owns this name
					recordConflict(id, localName, new Conflict(tag, BankTagSyncMetadata.REASON_DUPLICATE_NAME));
				}
			}
			for (Map.Entry<String, Conflict> entry : tombstoneConflicts.entrySet())
			{
				recordConflict(entry.getKey(), local.get(entry.getKey()).name, entry.getValue());
			}
			if (orderChanged)
			{
				if (orderFuture == null && !orderInFlight && !orderPending)
				{
					List<String> names = new ArrayList<>();
					for (String id : manifest.getOrderedTagIds())
					{
						TagMeta meta = metadata.tag(id);
						if (meta != null)
						{
							names.add(meta.name);
						}
					}
					tabManager.reorder(names);
				}
				metadata.setOrderRevision(manifest.getOrderRevision());
			}
			metadata.setGroupRevision(manifest.getGroupRevision());
		}
		finally
		{
			applyingRemote = false;
		}
		tabInterface.get().refreshTabs();
		pollFolders(epoch.get());
	}

	private void deleteLocally(String id, String name)
	{
		cancelDebounce(id);
		snapshots.delete(name);
		metadata.removeTag(id);
		metadata.removePendingDelete(id);
		metadata.removeConflict(id);
		rejectedTagIds.remove(id);
	}

	private void pollFolders(int gen)
	{
		long revision = folderMetadata.groupRevision();
		client.getFolderManifest(revision == 0 ? null : revision, onClientThread(gen, result ->
		{
			onRequestSucceeded();
			if (result.isNotModified())
			{
				finishPoll();
				return;
			}
			planRemoteFolderChanges(gen, result.getManifest());
		}, failure ->
		{
			pollInFlight.set(false);
			onPollFailed(failure);
		}));
	}

	private void planRemoteFolderChanges(int gen, BankTagFolderManifest manifest)
	{
		Map<String, FolderMeta> local = folderMetadata.allFolders();
		List<String> toFetch = new ArrayList<>();
		List<String> toDelete = new ArrayList<>();
		boolean dirtyConflict = false;
		for (BankTagFolderManifest.Entry entry : manifest.getFolders())
		{
			FolderMeta meta = local.get(entry.getFolderId());
			if (meta == null)
			{
				if (!entry.isDeleted() && folderMetadata.pendingDelete(entry.getFolderId()) == null)
				{
					toFetch.add(entry.getFolderId());
				}
				continue;
			}
			if (entry.getRevision() == meta.revision)
			{
				continue;
			}
			if (isFolderDirty(entry.getFolderId(), meta))
			{
				dirtyConflict = true;
				log.debug("bank tag folder sync conflict for {}", entry.getFolderId());
				continue;
			}
			if (entry.isDeleted())
			{
				toDelete.add(entry.getFolderId());
			}
			else
			{
				toFetch.add(entry.getFolderId());
			}
		}
		final boolean retainRevision = dirtyConflict;
		final boolean orderChanged = manifest.getOrderRevision() != folderMetadata.orderRevision();
		final boolean stateChanged = !toFetch.isEmpty() || !toDelete.isEmpty() || orderChanged;
		fetchAllFolders(gen, toFetch, fetched ->
		{
			applyingRemote = true;
			try
			{
				for (String id : toDelete)
				{
					folderManager.delete(id);
					folderMetadata.removeFolder(id);
					folderMetadata.removePendingDelete(id);
				}
				for (String id : manifest.getOrderedFolderIds())
				{
					SharedBankTagFolder folder = fetched.get(id);
					if (folder != null)
					{
						folderManager.apply(folder);
						folderMetadata.putFolder(id,
							new FolderMeta(folder.getName(), folder.getRevision(), folder.contentHash()));
					}
				}
				if (orderChanged && folderOrderFuture == null && !folderOrderInFlight)
				{
					folderManager.reorder(manifest.getOrderedFolderIds());
				}
				if (stateChanged)
				{
					folderMetadata.setOrderRevision(manifest.getOrderRevision());
					if (!retainRevision)
					{
						folderMetadata.setGroupRevision(manifest.getGroupRevision());
					}
				}
			}
			finally
			{
				applyingRemote = false;
			}
			if (stateChanged)
			{
				tabInterface.get().refreshTabs();
			}
			finishPoll();
		}, failure ->
		{
			pollInFlight.set(false);
			onPollFailed(failure);
		});
	}

	private boolean isFolderDirty(String folderId, FolderMeta meta)
	{
		SharedBankTagFolder folder = folderManager.get(folderId);
		return folder != null && !folder.contentHash().equals(meta.baseHash);
	}

	private void fetchAllFolders(int gen, Collection<String> ids,
		Consumer<Map<String, SharedBankTagFolder>> onAllFetched, Consumer<SyncFailure> onFailure)
	{
		if (ids.isEmpty())
		{
			onAllFetched.accept(Collections.emptyMap());
			return;
		}
		Map<String, SharedBankTagFolder> fetched = new ConcurrentHashMap<>();
		AtomicInteger remaining = new AtomicInteger(ids.size());
		AtomicReference<SyncFailure> failed = new AtomicReference<>();
		for (String id : ids)
		{
			client.getFolder(id, new BankTagSyncClient.Callback<SharedBankTagFolder>()
			{
				@Override public void onSuccess(SharedBankTagFolder value) { fetched.put(id, value); done(); }
				@Override public void onFailure(SyncFailure failure) { failed.compareAndSet(null, failure); done(); }
				private void done()
				{
					if (remaining.decrementAndGet() != 0) return;
					clientThread.invoke(() ->
					{
						if (gen != epoch.get()) return;
						if (failed.get() != null) onFailure.accept(failed.get()); else onAllFetched.accept(fetched);
					});
				}
			});
		}
	}

	/**
	 * Client thread. Re-schedules every dirty tag, retries pending deletes, releases the poll, and
	 * arms the next one.
	 */
	private void finishPoll()
	{
		try
		{
			sweepDirty();
			retryPendingDeletes();
			sweepDirtyFolders();
			retryPendingFolderDeletes();
		}
		finally
		{
			pollInFlight.set(false);
			schedulePoll(config.pollIntervalSeconds());
		}
	}

	private void sweepDirty()
	{
		Map<String, TagMeta> all = metadata.allTags();
		Set<String> known = new LinkedHashSet<>();
		for (TagMeta meta : all.values())
		{
			known.add(meta.name);
		}
		for (String name : tabManager.tabNames())
		{
			if (!known.contains(name))
			{
				scheduleUpload(resolveTagId(name));
			}
		}
		for (Map.Entry<String, TagMeta> entry : all.entrySet())
		{
			String id = entry.getKey();
			if (debounceFutures.containsKey(id) || uploadsInFlight.contains(id) || metadata.conflict(id) != null
				|| rejectedTagIds.contains(id))
			{
				continue;
			}
			if (isDirty(entry.getValue()))
			{
				scheduleUpload(id);
			}
		}
	}

	private void retryPendingDeletes()
	{
		for (Map.Entry<String, Long> entry : metadata.allPendingDeletes().entrySet())
		{
			String id = entry.getKey();
			if (!deletesInFlight.contains(id) && !deleteFutures.containsKey(id))
			{
				sendDelete(id, entry.getValue());
			}
		}
	}

	private void sweepDirtyFolders()
	{
		for (Map.Entry<String, FolderMeta> entry : folderMetadata.allFolders().entrySet())
		{
			String id = entry.getKey();
			if (!folderDebounceFutures.containsKey(id) && !folderUploadsInFlight.contains(id)
				&& isFolderDirty(id, entry.getValue()))
			{
				scheduleFolderUpload(id);
			}
		}
	}

	private void retryPendingFolderDeletes()
	{
		for (Map.Entry<String, Long> entry : folderMetadata.allPendingDeletes().entrySet())
		{
			String id = entry.getKey();
			if (!folderDeletesInFlight.contains(id) && !folderDeleteFutures.containsKey(id))
			{
				sendFolderDelete(id, entry.getValue());
			}
		}
	}

	// ---------------------------------------------------------------- local mutations

	/**
	 * Any change to a tab's name, icon, items, or layout. Item tags without a tab are ignored.
	 */
	public void onTagMutated(String tag)
	{
		if (!active || applyingRemote || tag == null)
		{
			return;
		}
		String name = Text.standardize(tag);
		if (tabManager.find(name) == null)
		{
			return;
		}
		String id = resolveTagId(name);
		// a changed tag gets a fresh chance; the rejection concerned its previous content
		rejectedTagIds.remove(id);
		scheduleUpload(id);
	}

	public void onTagRenamed(String oldTag, String newTag)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		String id = metadata.tagIdForName(oldTag);
		if (id != null)
		{
			TagMeta meta = metadata.tag(id);
			metadata.putTag(id, new TagMeta(newTag, meta.revision, meta.baseHash));
		}
		onTagMutated(newTag);
	}

	public void onTagDeleted(String tag)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		String id = metadata.tagIdForName(tag);
		if (id == null)
		{
			return;
		}
		TagMeta meta = metadata.tag(id);
		cancelDebounce(id);
		for (String changedFolderId : folderManager.moveTag(id, null))
		{
			onFolderMutated(changedFolderId);
		}
		metadata.removeTag(id);
		metadata.removeConflict(id);
		rejectedTagIds.remove(id);
		if (meta.revision == 0)
		{
			return;
		}
		metadata.putPendingDelete(id, meta.revision);
		scheduleDelete(id, meta.revision);
	}

	public void onTabOrderChanged()
	{
		if (!active || applyingRemote)
		{
			return;
		}
		scheduleOrderUpload();
	}

	private String resolveTagId(String name)
	{
		String id = metadata.tagIdForName(name);
		if (id == null)
		{
			id = UUID.randomUUID().toString();
			metadata.putTag(id, new TagMeta(name, 0, ""));
		}
		return id;
	}

	// ---------------------------------------------------------------- folders

	/** Current persisted folders in display order. */
	public List<SharedBankTagFolder> folders()
	{
		return active ? folderManager.folders() : Collections.emptyList();
	}

	/** Creates a stable-UUID folder locally and queues its remote create. */
	@Nullable
	public SharedBankTagFolder createFolder(String name)
	{
		if (!active || applyingRemote)
		{
			return null;
		}
		SharedBankTagFolder folder = folderManager.create(name);
		folderMetadata.putFolder(folder.getFolderId(), new FolderMeta(folder.getName(), 0, ""));
		onFolderMutated(folder.getFolderId());
		onFolderOrderChanged();
		return folder;
	}

	public void setFolderIcon(String folderId, int iconItemId)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		folderManager.setIcon(folderId, iconItemId);
		onFolderMutated(folderId);
	}

	@Nullable
	public String tagNameForId(String tagId)
	{
		TagMeta meta = metadata.tag(tagId);
		return meta == null ? null : meta.name;
	}

	public boolean isFolderCollapsed(String folderId)
	{
		return folderManager.isCollapsed(folderId);
	}

	public void setFolderCollapsed(String folderId, boolean collapsed)
	{
		folderManager.setCollapsed(folderId, collapsed);
	}

	public void renameFolder(String folderId, String name)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		folderManager.rename(folderId, name);
		onFolderMutated(folderId);
	}

	/** Deletes only the folder. Member tags remain present and become unfiled. */
	public void deleteFolder(String folderId)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		FolderMeta meta = folderMetadata.folder(folderId);
		cancelFolderDebounce(folderId);
		folderManager.delete(folderId);
		folderMetadata.removeFolder(folderId);
		if (meta != null && meta.revision > 0)
		{
			folderMetadata.putPendingDelete(folderId, meta.revision);
			scheduleFolderDelete(folderId, meta.revision);
		}
		onFolderOrderChanged();
	}

	/** Moves a tab to a folder. A {@code null} folder id means the unfiled section. */
	public void moveTagToFolder(String tag, @Nullable String folderId)
	{
		if (!active || applyingRemote || tag == null)
		{
			return;
		}
		String tagId = metadata.tagIdForName(Text.standardize(tag));
		if (tagId == null)
		{
			return;
		}
		for (String changedFolderId : folderManager.moveTag(tagId, folderId))
		{
			onFolderMutated(changedFolderId);
		}
	}

	/** Moves a tab to a folder and positions it relative to another child when supplied. */
	public void moveTagToFolder(String tag, @Nullable String folderId, @Nullable String destinationTag,
		boolean insertMode)
	{
		if (!active || applyingRemote || tag == null)
		{
			return;
		}
		String tagId = metadata.tagIdForName(Text.standardize(tag));
		String destinationTagId = destinationTag == null ? null
			: metadata.tagIdForName(Text.standardize(destinationTag));
		if (tagId == null)
		{
			return;
		}
		for (String changedFolderId : folderManager.moveTag(tagId, folderId, destinationTagId, insertMode))
		{
			onFolderMutated(changedFolderId);
		}
	}

	public void moveTagToUnfiled(String tag)
	{
		moveTagToFolder(tag, null);
	}

	@Nullable
	public String folderIdForTag(String tag)
	{
		String tagId = tag == null ? null : metadata.tagIdForName(Text.standardize(tag));
		return tagId == null ? null : folderManager.folderIdForTag(tagId);
	}

	/** Existing and newly synchronized tabs with no folder membership appear here automatically. */
	public List<String> unfiledTags()
	{
		if (!active)
		{
			return tabManager.tabNames();
		}
		Map<String, TagMeta> all = metadata.allTags();
		List<String> ids = new ArrayList<>();
		for (String name : tabManager.tabNames())
		{
			String id = metadata.tagIdForName(name);
			if (id != null) ids.add(id);
		}
		Set<String> unfiledIds = new LinkedHashSet<>(folderManager.unfiledTagIds(ids));
		List<String> names = new ArrayList<>();
		for (String id : ids)
		{
			TagMeta meta = all.get(id);
			if (meta != null && unfiledIds.contains(id)) names.add(meta.name);
		}
		return names;
	}

	public void reorderFolders(List<String> orderedFolderIds)
	{
		if (!active || applyingRemote)
		{
			return;
		}
		folderManager.reorder(orderedFolderIds);
		onFolderOrderChanged();
	}

	/** Notification hook for callers that complete a compound folder mutation themselves. */
	public void onFolderMutated(String folderId)
	{
		if (!active || applyingRemote || folderManager.get(folderId) == null)
		{
			return;
		}
		if (folderMetadata.folder(folderId) == null)
		{
			SharedBankTagFolder folder = folderManager.get(folderId);
			folderMetadata.putFolder(folderId, new FolderMeta(folder.getName(), 0, ""));
		}
		scheduleFolderUpload(folderId);
	}

	public void onFolderOrderChanged()
	{
		if (!active || applyingRemote)
		{
			return;
		}
		cancel(folderOrderFuture);
		folderOrderFuture = executor.schedule(() -> clientThread.invoke(this::uploadFolderOrder),
			config.uploadDebounceSeconds(), TimeUnit.SECONDS);
	}

	/**
	 * Applies one remote folder atomically from the coordinator's perspective. Observation is
	 * suppressed for the full operation, so UI callbacks cannot echo it as a local upload.
	 */
	public void applyRemoteFolder(SharedBankTagFolder remote)
	{
		applyingRemote = true;
		try
		{
			cancelFolderDebounce(remote.getFolderId());
			folderManager.apply(remote);
			if (remote.isDeleted())
			{
				folderMetadata.removeFolder(remote.getFolderId());
				folderMetadata.removePendingDelete(remote.getFolderId());
			}
			else
			{
				folderMetadata.putFolder(remote.getFolderId(),
					new FolderMeta(remote.getName(), remote.getRevision(), remote.contentHash()));
			}
		}
		finally
		{
			applyingRemote = false;
		}
	}

	private void scheduleFolderUpload(String folderId)
	{
		AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			folderDebounceFutures.remove(folderId, self.get());
			clientThread.invoke(() -> uploadFolder(folderId));
		}, config.uploadDebounceSeconds(), TimeUnit.SECONDS);
		self.set(future);
		cancel(folderDebounceFutures.put(folderId, future));
	}

	private void cancelFolderDebounce(String folderId)
	{
		cancel(folderDebounceFutures.remove(folderId));
	}

	private void uploadFolder(String folderId)
	{
		if (!active || sendingSuspended() || !folderUploadsInFlight.add(folderId))
		{
			return;
		}
		SharedBankTagFolder local = folderManager.get(folderId);
		FolderMeta meta = folderMetadata.folder(folderId);
		if (local == null || meta == null)
		{
			folderUploadsInFlight.remove(folderId);
			return;
		}
		String hash = local.contentHash();
		if (hash.equals(meta.baseHash))
		{
			folderUploadsInFlight.remove(folderId);
			return;
		}
		BankTagSyncClient.Callback<SharedBankTagFolder> callback = onClientThread(epoch.get(), remote ->
		{
			folderUploadsInFlight.remove(folderId);
			onRequestSucceeded();
			FolderMeta current = folderMetadata.folder(folderId);
			if (current != null)
			{
				folderMetadata.putFolder(folderId, new FolderMeta(current.name, remote.getRevision(), hash));
			}
		}, failure ->
		{
			folderUploadsInFlight.remove(folderId);
			handleConnectionFailure(failure);
			log.debug("bank tag folder sync upload rejected: {}", failure.getKind());
		});
		if (meta.revision == 0)
		{
			client.createFolder(folderId, local, callback);
		}
		else
		{
			client.updateFolder(folderId, meta.revision, local, callback);
		}
	}

	private void scheduleFolderDelete(String folderId, long revision)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			folderDeleteFutures.remove(folderId);
			clientThread.invoke(() -> sendFolderDelete(folderId, revision));
		}, config.uploadDebounceSeconds(), TimeUnit.SECONDS);
		cancel(folderDeleteFutures.put(folderId, future));
	}

	private void sendFolderDelete(String folderId, long revision)
	{
		if (!active || !folderDeletesInFlight.add(folderId))
		{
			return;
		}
		client.deleteFolder(folderId, revision, onClientThread(epoch.get(), remote ->
		{
			folderDeletesInFlight.remove(folderId);
			folderMetadata.removePendingDelete(folderId);
			onRequestSucceeded();
		}, failure ->
		{
			folderDeletesInFlight.remove(folderId);
			handleConnectionFailure(failure);
			log.debug("bank tag folder sync delete rejected: {}", failure.getKind());
		}));
	}

	private void uploadFolderOrder()
	{
		folderOrderFuture = null;
		if (!active)
		{
			return;
		}
		if (folderOrderInFlight)
		{
			folderOrderPending = true;
			return;
		}
		final int gen = epoch.get();
		folderOrderInFlight = true;
		client.putFolderOrder(folderMetadata.orderRevision(), folderManager.folderIds(),
			onClientThread(gen, manifest -> onFolderOrderSuccess(gen, manifest), failure ->
			{
				BankTagFolderManifest current = failure.getCurrentFolderManifest();
				if (failure.getKind() == SyncFailure.Kind.CONFLICT && current != null)
				{
					List<String> merged = reconcileOrder(folderManager.folderIds(), current.getOrderedFolderIds());
					client.putFolderOrder(current.getOrderRevision(), merged, onClientThread(gen,
						manifest -> onFolderOrderSuccess(gen, manifest),
						retryFailure -> onFolderOrderFailure(retryFailure,
							"bank tag folder sync order retry rejected")));
					return;
				}
				onFolderOrderFailure(failure, "bank tag folder sync order rejected");
			}));
	}

	private void onFolderOrderFailure(SyncFailure failure, String message)
	{
		folderOrderInFlight = false;
		handleConnectionFailure(failure);
		log.debug("{}: {}", message, failure.getKind());
		if (folderOrderPending)
		{
			folderOrderPending = false;
			onFolderOrderChanged();
		}
	}

	private void onFolderOrderSuccess(int gen, BankTagFolderManifest manifest)
	{
		folderOrderInFlight = false;
		folderMetadata.setOrderRevision(manifest.getOrderRevision());
		onRequestSucceeded();
		if (folderOrderPending)
		{
			folderOrderPending = false;
			onFolderOrderChanged();
		}
		if (pollInFlight.compareAndSet(false, true))
		{
			planRemoteFolderChanges(gen, manifest);
		}
	}

	// ---------------------------------------------------------------- recovery actions

	/**
	 * Client thread. Resolves a conflict by replacing the local tab with the server's copy (or
	 * deleting it when the server's copy is a tombstone). Applied with local observation suppressed
	 * so nothing is uploaded back.
	 */
	public void useRemoteVersion(String tag)
	{
		if (!active || tag == null)
		{
			return;
		}
		String name = Text.standardize(tag);
		String localId = metadata.tagIdForName(name);
		String conflictId = conflictIdForName(name, localId);
		if (conflictId == null)
		{
			return;
		}
		SharedBankTag remote = metadata.conflict(conflictId).remote;
		applyingRemote = true;
		try
		{
			cancelDebounce(conflictId);
			if (localId != null)
			{
				cancelDebounce(localId);
				if (!localId.equals(conflictId))
				{
					metadata.removeTag(localId);
				}
			}
			if (remote.isDeleted())
			{
				snapshots.delete(name);
				metadata.removeTag(conflictId);
			}
			else
			{
				snapshots.apply(name, remote);
				metadata.putTag(conflictId, new TagMeta(remote.getName(), remote.getRevision(), remote.contentHash()));
			}
			metadata.removeConflict(conflictId);
			metadata.removePendingDelete(conflictId);
			rejectedTagIds.remove(conflictId);
		}
		finally
		{
			applyingRemote = false;
		}
		log.debug("bank tag sync conflict resolved with the remote version");
		tabInterface.get().refreshTabs();
	}

	/**
	 * Client thread. Resolves a conflict by sending the local tab over the server's copy, using the
	 * revision recorded in the conflict. A tombstoned id cannot be re-created, so the tab is
	 * re-created under a fresh id instead. A further {@code 409} re-records the conflict.
	 */
	public void overwriteRemoteVersion(String tag)
	{
		if (!active || tag == null)
		{
			return;
		}
		String name = Text.standardize(tag);
		String localId = metadata.tagIdForName(name);
		String conflictId = conflictIdForName(name, localId);
		if (conflictId == null)
		{
			return;
		}
		SharedBankTag remote = metadata.conflict(conflictId).remote;
		SharedBankTag snapshot;
		try
		{
			snapshot = snapshots.snapshot(name);
		}
		catch (IllegalArgumentException ex)
		{
			return; // the tab no longer exists
		}
		final String hash = snapshot.contentHash();
		final int gen = epoch.get();
		cancelDebounce(conflictId);
		if (localId != null && !localId.equals(conflictId))
		{
			cancelDebounce(localId);
			metadata.removeTag(localId);
		}
		metadata.removeConflict(conflictId);
		metadata.removePendingDelete(conflictId);
		rejectedTagIds.remove(conflictId);

		if (remote.isDeleted())
		{
			metadata.removeTag(conflictId);
			final String newId = UUID.randomUUID().toString();
			final TagMeta meta = new TagMeta(name, 0, "");
			metadata.putTag(newId, meta);
			uploadsInFlight.add(newId);
			client.createTag(newId, snapshot, onClientThread(gen,
				result -> onUploadSuccess(newId, hash, result, true),
				failure -> onUploadFailure(gen, newId, meta, failure)));
		}
		else
		{
			TagMeta previous = metadata.tag(conflictId);
			final TagMeta meta = new TagMeta(name, remote.getRevision(), previous == null ? "" : previous.baseHash);
			metadata.putTag(conflictId, meta);
			uploadsInFlight.add(conflictId);
			client.updateTag(conflictId, remote.getRevision(), snapshot, onClientThread(gen,
				result -> onUploadSuccess(conflictId, hash, result, false),
				failure -> onUploadFailure(gen, conflictId, meta, failure)));
		}
		log.debug("bank tag sync conflict resolved with the local version");
		tabInterface.get().refreshTabs();
	}

	/**
	 * Client thread. Forgets a rejection or a backoff for one tab and sends it now, followed by a poll.
	 */
	public void retry(String tag)
	{
		if (!active || tag == null)
		{
			return;
		}
		String id = metadata.tagIdForName(Text.standardize(tag));
		if (id == null)
		{
			return;
		}
		boolean wasRejected = rejectedTagIds.remove(id);
		consecutiveFailures = 0;
		cancelDebounce(id);
		upload(id, true);
		schedulePoll(0);
		if (wasRejected)
		{
			tabInterface.get().refreshTabs();
		}
	}

	// ---------------------------------------------------------------- uploads

	private void scheduleUpload(String tagId)
	{
		AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			debounceFutures.remove(tagId, self.get());
			clientThread.invoke(() -> upload(tagId, false));
		}, config.uploadDebounceSeconds(), TimeUnit.SECONDS);
		self.set(future);
		cancel(debounceFutures.put(tagId, future));
	}

	private void cancelDebounce(String tagId)
	{
		cancel(debounceFutures.remove(tagId));
	}

	/**
	 * Client thread. Sends the current snapshot of one tag unless there is nothing to send. While the
	 * server is unreachable or the credentials are rejected nothing is sent; the poll sweep picks the
	 * tag up again once the connection is back, unless {@code evenIfOffline} forces a probe.
	 */
	private void upload(String tagId, boolean evenIfOffline)
	{
		if (!active || (sendingSuspended() && !evenIfOffline))
		{
			return;
		}
		final TagMeta meta = metadata.tag(tagId);
		if (meta == null || metadata.conflict(tagId) != null || uploadsInFlight.contains(tagId))
		{
			return;
		}
		SharedBankTag snapshot;
		try
		{
			snapshot = snapshots.snapshot(meta.name);
		}
		catch (IllegalArgumentException ex)
		{
			return; // the tab no longer exists
		}
		final String hash = snapshot.contentHash();
		if (hash.equals(meta.baseHash))
		{
			return;
		}

		final int gen = epoch.get();
		final boolean create = meta.revision == 0;
		uploadsInFlight.add(tagId);
		BankTagSyncClient.Callback<SharedBankTag> callback = onClientThread(gen,
			remote -> onUploadSuccess(tagId, hash, remote, create),
			failure -> onUploadFailure(gen, tagId, meta, failure));
		if (create)
		{
			client.createTag(tagId, snapshot, callback);
		}
		else
		{
			client.updateTag(tagId, meta.revision, snapshot, callback);
		}
	}

	private void onUploadSuccess(String tagId, String sentHash, SharedBankTag remote, boolean created)
	{
		uploadsInFlight.remove(tagId);
		onRequestSucceeded();
		boolean visibleChange = rejectedTagIds.remove(tagId) | metadata.conflict(tagId) != null;
		TagMeta current = metadata.tag(tagId);
		if (current == null)
		{
			return; // deleted while the upload was in flight; the pending delete takes over
		}
		TagMeta updated = new TagMeta(current.name, remote.getRevision(), sentHash);
		metadata.putTag(tagId, updated);
		metadata.removeConflict(tagId);
		metadata.removePendingDelete(tagId);
		if (created)
		{
			// the server appended the new id to the group order; publish the local order
			scheduleOrderUpload();
		}
		if (isDirty(updated))
		{
			scheduleUpload(tagId);
		}
		if (visibleChange)
		{
			refreshTabs();
		}
	}

	private void onUploadFailure(int gen, String tagId, TagMeta meta, SyncFailure failure)
	{
		uploadsInFlight.remove(tagId);
		if (handleConnectionFailure(failure))
		{
			// stays dirty; the next successful poll sweep retries
			log.debug("bank tag sync upload failed: {}", failure.getKind());
			return;
		}
		switch (failure.getKind())
		{
			case CONFLICT:
				recordWriteConflict(gen, tagId, meta, failure);
				break;
			case NOT_FOUND:
				recordConflict(tagId, meta.name, new Conflict(tombstone(tagId, meta.name, meta.revision),
					BankTagSyncMetadata.REASON_REMOTE_CHANGED));
				refreshTabs();
				break;
			default:
				recordRejection(tagId, meta.name, failure);
				break;
		}
	}

	private void recordWriteConflict(int gen, String tagId, TagMeta meta, SyncFailure failure)
	{
		String code = failure.getErrorCode();
		final String reason;
		if (BankTagSyncMetadata.REASON_STALE_REVISION.equals(code)
			|| BankTagSyncMetadata.REASON_DUPLICATE_NAME.equals(code)
			|| BankTagSyncMetadata.REASON_TAG_EXISTS.equals(code))
		{
			reason = code;
		}
		else
		{
			log.debug("bank tag sync upload conflict with unknown code");
			return;
		}
		SharedBankTag current = failure.getCurrentTag();
		String remoteId = current == null || isBlank(current.getTagId()) ? tagId : current.getTagId();
		client.getTag(remoteId, onClientThread(gen,
			remote ->
			{
				recordConflict(tagId, meta.name, new Conflict(remote, reason));
				refreshTabs();
			},
			fetchFailure ->
			{
				SharedBankTag fallback = current != null ? current : tombstone(tagId, meta.name, meta.revision);
				recordConflict(tagId, meta.name, new Conflict(fallback, reason));
				refreshTabs();
			}));
	}

	/**
	 * Client thread. Stores the conflict and announces it once; a tag that is already conflicted
	 * stays quiet when the record is refreshed.
	 */
	private void recordConflict(String tagId, String name, Conflict conflict)
	{
		if (metadata.conflict(tagId) == null)
		{
			chat("Bank tag sync: '" + name + "' changed on the server and locally. Right-click the tab to resolve.");
		}
		metadata.putConflict(tagId, conflict);
	}

	/**
	 * Client thread. The server refused the upload for a reason that will not change until the tag
	 * does ({@code 400}, {@code 413}, {@code 428}, or an unreadable response).
	 */
	private void recordRejection(String tagId, String name, SyncFailure failure)
	{
		log.debug("bank tag sync upload rejected: {}", failure.getKind());
		if (rejectedTagIds.contains(tagId))
		{
			return;
		}
		String code = isBlank(failure.getErrorCode()) ? failure.getKind().name().toLowerCase() : failure.getErrorCode();
		chat("Bank tag sync: '" + name + "' was rejected by the server (" + code + ").");
		rejectedTagIds.add(tagId);
		refreshTabs();
	}

	// ---------------------------------------------------------------- deletes

	private void scheduleDelete(String tagId, long revision)
	{
		ScheduledFuture<?> future = executor.schedule(() ->
		{
			deleteFutures.remove(tagId);
			clientThread.invoke(() -> sendDelete(tagId, revision));
		}, config.uploadDebounceSeconds(), TimeUnit.SECONDS);
		cancel(deleteFutures.put(tagId, future));
	}

	/**
	 * Client thread.
	 */
	private void sendDelete(String tagId, long revision)
	{
		if (!active || !deletesInFlight.add(tagId))
		{
			return;
		}
		client.deleteTag(tagId, revision, onClientThread(epoch.get(), remote ->
		{
			deletesInFlight.remove(tagId);
			onRequestSucceeded();
			metadata.removePendingDelete(tagId);
		}, failure ->
		{
			deletesInFlight.remove(tagId);
			if (handleConnectionFailure(failure))
			{
				log.debug("bank tag sync delete failed: {}", failure.getKind());
				return;
			}
			switch (failure.getKind())
			{
				case CONFLICT:
				case NOT_FOUND:
					// someone else changed or removed it; the next poll re-creates it locally if it still exists
					metadata.removePendingDelete(tagId);
					break;
				default:
					log.debug("bank tag sync delete failed: {}", failure.getKind());
					break;
			}
		}));
	}

	// ---------------------------------------------------------------- order

	private void scheduleOrderUpload()
	{
		if (orderFuture != null)
		{
			orderFuture.cancel(false);
		}
		orderFuture = executor.schedule(() -> clientThread.invoke(this::uploadOrder),
			config.uploadDebounceSeconds(), TimeUnit.SECONDS);
	}

	/**
	 * Client thread.
	 */
	private void uploadOrder()
	{
		orderFuture = null;
		if (!active)
		{
			return;
		}
		if (orderInFlight)
		{
			orderPending = true;
			return;
		}
		List<String> ids = localOrderIds();
		if (ids.isEmpty())
		{
			return;
		}
		final int gen = epoch.get();
		orderInFlight = true;
		client.putOrder(metadata.orderRevision(), ids, onClientThread(gen, manifest -> onOrderSuccess(gen, manifest), failure ->
		{
			BankTagManifest current = failure.getCurrentManifest();
			if (failure.getKind() == SyncFailure.Kind.CONFLICT && current != null)
			{
				List<String> merged = reconcileOrder(localOrderIds(), current.getOrderedTagIds());
				client.putOrder(current.getOrderRevision(), merged, onClientThread(gen,
					manifest -> onOrderSuccess(gen, manifest),
					retryFailure ->
					{
						orderInFlight = false;
						handleConnectionFailure(retryFailure);
						log.debug("bank tag sync order retry rejected: {}", retryFailure.getKind());
					}));
				return;
			}
			orderInFlight = false;
			handleConnectionFailure(failure);
			log.debug("bank tag sync order upload rejected: {}", failure.getKind());
		}));
	}

	private void onOrderSuccess(int gen, BankTagManifest manifest)
	{
		orderInFlight = false;
		onRequestSucceeded();
		metadata.setOrderRevision(manifest.getOrderRevision());
		if (orderPending)
		{
			orderPending = false;
			scheduleOrderUpload();
		}
		// The response is a full manifest: treat it like a poll result so the group revision advances
		// only after any concurrent remote changes it reveals have been applied.
		if (pollInFlight.compareAndSet(false, true))
		{
			planRemoteChanges(gen, manifest);
		}
	}

	private List<String> localOrderIds()
	{
		Map<String, TagMeta> all = metadata.allTags();
		Map<String, String> nameToId = new HashMap<>();
		for (Map.Entry<String, TagMeta> entry : all.entrySet())
		{
			if (entry.getValue().revision > 0)
			{
				nameToId.put(entry.getValue().name, entry.getKey());
			}
		}
		List<String> ids = new ArrayList<>();
		for (String name : tabManager.tabNames())
		{
			String id = nameToId.get(name);
			if (id != null)
			{
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Local order restricted to ids the server knows, followed by server ids missing locally in the
	 * server's order. The result is a permutation of {@code remote}.
	 */
	static List<String> reconcileOrder(List<String> local, List<String> remote)
	{
		Set<String> remoteSet = new LinkedHashSet<>(remote);
		List<String> merged = new ArrayList<>();
		for (String id : local)
		{
			if (remoteSet.contains(id) && !merged.contains(id))
			{
				merged.add(id);
			}
		}
		for (String id : remote)
		{
			if (!merged.contains(id))
			{
				merged.add(id);
			}
		}
		return merged;
	}

	// ---------------------------------------------------------------- helpers

	private boolean isDirty(TagMeta meta)
	{
		try
		{
			return !snapshots.snapshot(meta.name).contentHash().equals(meta.baseHash);
		}
		catch (IllegalArgumentException ex)
		{
			return false; // no such tab locally
		}
	}

	/**
	 * Fetches every id; when all callbacks returned, continues on the client thread with either the
	 * complete map or the first failure. Local state is never touched before every fetch succeeded.
	 */
	private void fetchAll(int gen, Collection<String> ids, Consumer<Map<String, SharedBankTag>> onAllFetched,
		Consumer<SyncFailure> onFailure)
	{
		if (ids.isEmpty())
		{
			onAllFetched.accept(Collections.emptyMap());
			return;
		}
		final Map<String, SharedBankTag> fetched = new ConcurrentHashMap<>();
		final AtomicInteger remaining = new AtomicInteger(ids.size());
		final AtomicReference<SyncFailure> failed = new AtomicReference<>();
		for (String id : ids)
		{
			client.getTag(id, new BankTagSyncClient.Callback<SharedBankTag>()
			{
				@Override
				public void onSuccess(SharedBankTag value)
				{
					fetched.put(id, value);
					done();
				}

				@Override
				public void onFailure(SyncFailure failure)
				{
					log.debug("bank tag sync fetch failed: {}", failure.getKind());
					failed.compareAndSet(null, failure);
					done();
				}

				private void done()
				{
					if (remaining.decrementAndGet() != 0)
					{
						return;
					}
					clientThread.invoke(() ->
					{
						if (gen != epoch.get())
						{
							return;
						}
						SyncFailure failure = failed.get();
						if (failure != null)
						{
							onFailure.accept(failure);
						}
						else
						{
							onAllFetched.accept(fetched);
						}
					});
				}
			});
		}
	}

	private static List<String> orderedIds(BankTagManifest manifest, Set<String> ids)
	{
		List<String> ordered = new ArrayList<>();
		for (String id : manifest.getOrderedTagIds())
		{
			if (ids.contains(id))
			{
				ordered.add(id);
			}
		}
		for (String id : ids)
		{
			if (!ordered.contains(id))
			{
				ordered.add(id);
			}
		}
		return ordered;
	}

	private static SharedBankTag tombstone(String tagId, String name, long revision)
	{
		return new SharedBankTag(tagId, name, 0, Collections.emptyList(), null, revision, true);
	}

	private <T> BankTagSyncClient.Callback<T> onClientThread(int gen, Consumer<T> onSuccess, Consumer<SyncFailure> onFailure)
	{
		return new BankTagSyncClient.Callback<T>()
		{
			@Override
			public void onSuccess(T value)
			{
				clientThread.invoke(() ->
				{
					if (gen == epoch.get())
					{
						onSuccess.accept(value);
					}
				});
			}

			@Override
			public void onFailure(SyncFailure failure)
			{
				clientThread.invoke(() ->
				{
					if (gen == epoch.get())
					{
						onFailure.accept(failure);
					}
				});
			}
		};
	}

	private static void cancel(ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	/**
	 * Rebuilds the tab strip on the client thread so the {@code Sync:} menu entries match the state.
	 */
	private void refreshTabs()
	{
		clientThread.invoke(() -> tabInterface.get().refreshTabs());
	}

	/**
	 * Console message on the client thread. Callers never include the token, the URL, or a payload.
	 */
	private void chat(String message)
	{
		clientThread.invoke(() -> tabInterface.get().sendChatMessage(message));
	}
}
