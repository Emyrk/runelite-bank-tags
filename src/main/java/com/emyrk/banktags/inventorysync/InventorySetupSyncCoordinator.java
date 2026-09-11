package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.inventorysync.InventorySetupRepository.Snapshot;
import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.InventorySetupSyncFailure;
import com.emyrk.banktags.inventorysync.model.ManifestResponse;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.emyrk.banktags.sync.model.SyncFailure;
import inventorysetups.InventorySetupsPlugin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

/** Coordinates combined-manifest Inventory Setups synchronization without blocking the client thread. */
@Slf4j
@Singleton
public class InventorySetupSyncCoordinator
{
	private final InventorySetupSyncClient client;
	private final InventorySetupRepository repository;
	private final InventorySetupSyncMetadata metadata;
	private final BankTagsConfig config;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;
	private final AtomicBoolean pollInFlight = new AtomicBoolean();
	private final AtomicBoolean uploadInFlight = new AtomicBoolean();

	private InventorySetupsPlugin plugin;
	private volatile boolean active;
	private boolean applyingRemote;
	private boolean uploadAfterPoll;
	private int generation;
	private ScheduledFuture<?> pollFuture;
	private ScheduledFuture<?> debounceFuture;

	@Inject
	public InventorySetupSyncCoordinator(InventorySetupSyncClient client, InventorySetupRepository repository,
		InventorySetupSyncMetadata metadata, BankTagsConfig config, ScheduledExecutorService executor,
		ClientThread clientThread)
	{
		this.client = client;
		this.repository = repository;
		this.metadata = metadata;
		this.config = config;
		this.executor = executor;
		this.clientThread = clientThread;
	}

	public void start(InventorySetupsPlugin plugin)
	{
		stop();
		this.plugin = plugin;
		if (!config.inventorySetupsEnabled() || blank(config.groupName()) || blank(config.groupToken()))
		{
			return;
		}

		// Selecting a new identity only selects another metadata namespace. The currently rendered local
		// setups stay untouched until that identity returns a valid manifest.
		metadata.activateIdentity(config.serverBaseUrl(), config.groupName());
		active = true;
		poll();
	}

	public void stop()
	{
		active = false;
		generation++;
		cancel(pollFuture);
		cancel(debounceFuture);
		pollFuture = null;
		debounceFuture = null;
		pollInFlight.set(false);
		uploadInFlight.set(false);
		uploadAfterPoll = false;
		client.cancelAll();
		plugin = null;
	}

	public boolean isApplyingRemote()
	{
		return applyingRemote;
	}

	public void onLocalMutation(boolean setupsChanged, boolean sectionsChanged)
	{
		if (!active || applyingRemote || (!setupsChanged && !sectionsChanged) || plugin == null)
		{
			return;
		}

		// Capture deletes immediately after Inventory Setups persists the mutation. This closes the
		// restart/poll window where a remote document could otherwise resurrect a local deletion.
		capturePendingDeletes(repository.snapshot(plugin));
		cancel(debounceFuture);
		int expectedGeneration = generation;
		debounceFuture = executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (isCurrent(expectedGeneration))
			{
				uploadOne(expectedGeneration);
			}
		}), Math.max(1, config.uploadDebounceSeconds()), TimeUnit.SECONDS);
	}

	/** Resolves a stored setup conflict by applying the retained remote document. */
	public boolean useRemoteSetup(String localId)
	{
		if (!active || plugin == null)
		{
			return false;
		}
		SharedInventorySetup remote = metadata.setupConflict(localId);
		if (remote == null)
		{
			return false;
		}
		Snapshot snapshot = repository.snapshot(plugin);
		Map<String, SharedInventorySetup> setups = setupsById(snapshot.getSetups());
		Map<String, SharedInventorySetupSection> sections = sectionsById(snapshot.getSections());
		setups.remove(localId);
		if (!remote.isDeleted())
		{
			setups.put(remote.getSetupId(), remote);
		}
		pruneSectionMemberships(setups, sections);
		applyLocal(setups, sections, metadata.setupOrder(), metadata.sectionOrder());
		metadata.putSetup(remote, remote.isDeleted() ? "" : remote.contentHash());
		metadata.clearSetupConflict(localId);
		metadata.clearPendingSetupDelete(localId);
		return true;
	}

	/** Resolves a stored section conflict by applying the retained remote document. */
	public boolean useRemoteSection(String localId)
	{
		if (!active || plugin == null)
		{
			return false;
		}
		SharedInventorySetupSection remote = metadata.sectionConflict(localId);
		if (remote == null)
		{
			return false;
		}
		Snapshot snapshot = repository.snapshot(plugin);
		Map<String, SharedInventorySetup> setups = setupsById(snapshot.getSetups());
		Map<String, SharedInventorySetupSection> sections = sectionsById(snapshot.getSections());
		sections.remove(localId);
		if (!remote.isDeleted())
		{
			sections.put(remote.getSectionId(), remote);
		}
		applyLocal(setups, sections, metadata.setupOrder(), metadata.sectionOrder());
		metadata.putSection(remote, remote.isDeleted() ? "" : remote.contentKey());
		metadata.clearSectionConflict(localId);
		metadata.clearPendingSectionDelete(localId);
		return true;
	}

	/** Resolves a retained order conflict using both remote global orders. */
	public boolean useRemoteOrders()
	{
		if (!active || plugin == null)
		{
			return false;
		}
		InventorySetupManifest manifest = metadata.orderConflict();
		if (manifest == null)
		{
			return false;
		}
		Snapshot snapshot = repository.snapshot(plugin);
		applyLocal(setupsById(snapshot.getSetups()), sectionsById(snapshot.getSections()),
			manifest.getOrderedSetupIds(), manifest.getOrderedSectionIds());
		metadata.acceptManifest(manifest);
		metadata.clearOrderConflict();
		return true;
	}

	private void poll()
	{
		if (!active || !pollInFlight.compareAndSet(false, true))
		{
			return;
		}
		int expectedGeneration = generation;
		Long revision = metadata.initialized() ? metadata.groupRevision() : null;
		client.getManifest(revision, onThread(expectedGeneration, response ->
		{
			if (response.isNotModified())
			{
				recoverStoredRemoteWins(expectedGeneration);
				return;
			}
			reconcile(expectedGeneration, response.getManifest());
		}, failure -> pollFailed(expectedGeneration, failure)));
	}

	private void reconcile(int expectedGeneration, InventorySetupManifest manifest)
	{
		if (!isCurrent(expectedGeneration) || plugin == null)
		{
			return;
		}
		Snapshot snapshot = repository.snapshot(plugin);
		capturePendingDeletes(snapshot);

		if (!metadata.initialized() && manifest.getSetups().isEmpty() && manifest.getSections().isEmpty())
		{
			metadata.acceptManifest(manifest);
			uploadAfterPoll = true;
			finishPoll(expectedGeneration);
			return;
		}

		ReconcileStage stage = new ReconcileStage(manifest, snapshot);
		int detailCount = manifest.getSetups().size() + manifest.getSections().size();
		if (detailCount == 0)
		{
			applyReconcileStage(expectedGeneration, stage);
			return;
		}

		AtomicInteger pending = new AtomicInteger(detailCount);
		AtomicBoolean failed = new AtomicBoolean();
		Runnable complete = () ->
		{
			if (pending.decrementAndGet() != 0)
			{
				return;
			}
			if (failed.get())
			{
				finishPoll(expectedGeneration);
			}
			else
			{
				applyReconcileStage(expectedGeneration, stage);
			}
		};

		for (InventorySetupManifest.SetupEntry entry : manifest.getSetups())
		{
			client.getSetup(entry.getSetupId(), onThread(expectedGeneration, document ->
			{
				if (!matches(entry, document))
				{
					failed.set(true);
				}
				else
				{
					stage.remoteSetups.put(document.getSetupId(), document);
				}
				complete.run();
			}, failure ->
			{
				failed.set(true);
				log.debug("inventory setup detail fetch failed: {}", failure.getKind());
				complete.run();
			}));
		}
		for (InventorySetupManifest.SectionEntry entry : manifest.getSections())
		{
			client.getSection(entry.getSectionId(), onThread(expectedGeneration, document ->
			{
				if (!matches(entry, document))
				{
					failed.set(true);
				}
				else
				{
					stage.remoteSections.put(document.getSectionId(), document);
				}
				complete.run();
			}, failure ->
			{
				failed.set(true);
				log.debug("inventory setup section detail fetch failed: {}", failure.getKind());
				complete.run();
			}));
		}
	}

	private void applyReconcileStage(int expectedGeneration, ReconcileStage stage)
	{
		if (!isCurrent(expectedGeneration))
		{
			return;
		}

		Map<String, SharedInventorySetup> resolvedSetups = setupsById(stage.snapshot.getSetups());
		Map<String, SharedInventorySetupSection> resolvedSections = sectionsById(stage.snapshot.getSections());
		boolean firstManifest = !metadata.initialized();

		for (SharedInventorySetup remote : stage.remoteSetups.values())
		{
			String id = remote.getSetupId();
			SharedInventorySetup local = resolvedSetups.get(id);
			SharedInventorySetup cached = metadata.setup(id);
			Long deleteBase = metadata.pendingSetupDeletes().get(id);
			if (deleteBase != null)
			{
				resolvedSetups.remove(id);
				stage.setupPuts.put(id, remote);
				if (remote.isDeleted())
				{
					stage.clearPendingSetupDeletes.add(id);
					stage.clearSetupConflicts.add(id);
				}
				else if (remote.getRevision() > deleteBase)
				{
					stage.setupConflicts.put(id, remote);
				}
				continue;
			}

			SharedInventorySetup priorConflict = metadata.setupConflict(id);
			boolean dirty = local != null && !local.contentHash().equals(metadata.setupHash(id));
			boolean differs = local == null || !local.contentHash().equals(remote.contentHash())
				|| local.isDeleted() != remote.isDeleted();
			if (!firstManifest && priorConflict == null && dirty && differs)
			{
				stage.setupConflicts.put(id, remote);
				stage.setupPuts.put(id, remote);
				continue;
			}

			resolvedSetups.remove(id);
			if (!remote.isDeleted())
			{
				resolvedSetups.put(id, remote);
			}
			stage.setupPuts.put(id, remote);
			stage.clearSetupConflicts.add(id);
		}

		for (SharedInventorySetupSection remote : stage.remoteSections.values())
		{
			String id = remote.getSectionId();
			SharedInventorySetupSection local = resolvedSections.get(id);
			Long deleteBase = metadata.pendingSectionDeletes().get(id);
			if (deleteBase != null)
			{
				resolvedSections.remove(id);
				stage.sectionPuts.put(id, remote);
				if (remote.isDeleted())
				{
					stage.clearPendingSectionDeletes.add(id);
					stage.clearSectionConflicts.add(id);
				}
				else if (remote.getRevision() > deleteBase)
				{
					stage.sectionConflicts.put(id, remote);
				}
				continue;
			}

			SharedInventorySetupSection priorConflict = metadata.sectionConflict(id);
			boolean dirty = local != null && !local.contentKey().equals(metadata.sectionHash(id));
			boolean differs = local == null || !local.contentKey().equals(remote.contentKey())
				|| local.isDeleted() != remote.isDeleted();
			if (!firstManifest && priorConflict == null && dirty && differs)
			{
				stage.sectionConflicts.put(id, remote);
				stage.sectionPuts.put(id, remote);
				continue;
			}

			resolvedSections.remove(id);
			if (!remote.isDeleted())
			{
				resolvedSections.put(id, remote);
			}
			stage.sectionPuts.put(id, remote);
			stage.clearSectionConflicts.add(id);
		}

		pruneSectionMemberships(resolvedSetups, resolvedSections);
		List<String> setupOrder = resolveOrder(metadata.setupOrder(), setupIds(stage.snapshot.getSetups()),
			stage.manifest.getOrderedSetupIds(), firstManifest, metadata.orderConflict() != null, stage);
		List<String> sectionOrder = resolveOrder(metadata.sectionOrder(), sectionIds(stage.snapshot.getSections()),
			stage.manifest.getOrderedSectionIds(), firstManifest, metadata.orderConflict() != null, stage);

		try
		{
			applyLocal(resolvedSetups, resolvedSections, setupOrder, sectionOrder);
			stage.commit(metadata);
		}
		catch (RuntimeException ex)
		{
			log.debug("inventory setup staged apply failed", ex);
			finishPoll(expectedGeneration);
			return;
		}

		uploadAfterPoll = true;
		finishPoll(expectedGeneration);
	}

	private static List<String> resolveOrder(List<String> base, List<String> local, List<String> remote,
		boolean firstManifest, boolean recoveringConflict, ReconcileStage stage)
	{
		if (firstManifest || recoveringConflict)
		{
			stage.clearOrderConflict = recoveringConflict;
			return remote;
		}
		boolean remoteChanged = !remote.equals(base);
		boolean localChanged = !local.equals(base);
		if (remoteChanged && localChanged && !local.equals(remote))
		{
			stage.orderConflict = true;
			return local;
		}
		return remoteChanged ? remote : local;
	}

	/**
	 * A conflict is retained for one complete poll. On the next successful 304, non-delete conflicts
	 * deterministically use the retained remote document. Pending local deletes remain absent until
	 * the user calls useRemoteSetup/useRemoteSection or the original DELETE succeeds.
	 */
	private void recoverStoredRemoteWins(int expectedGeneration)
	{
		if (!isCurrent(expectedGeneration) || plugin == null)
		{
			return;
		}
		Snapshot snapshot = repository.snapshot(plugin);
		Map<String, SharedInventorySetup> setups = setupsById(snapshot.getSetups());
		Map<String, SharedInventorySetupSection> sections = sectionsById(snapshot.getSections());
		Map<String, SharedInventorySetup> setupConflicts = metadata.setupConflicts();
		Map<String, SharedInventorySetupSection> sectionConflicts = metadata.sectionConflicts();
		InventorySetupManifest orderConflict = metadata.orderConflict();
		boolean changed = false;

		for (Map.Entry<String, SharedInventorySetup> entry : setupConflicts.entrySet())
		{
			if (metadata.isSetupDeletePending(entry.getKey()))
			{
				continue;
			}
			setups.remove(entry.getKey());
			SharedInventorySetup remote = entry.getValue();
			if (!remote.isDeleted())
			{
				setups.put(remote.getSetupId(), remote);
			}
			metadata.putSetup(remote, remote.isDeleted() ? "" : remote.contentHash());
			metadata.clearSetupConflict(entry.getKey());
			changed = true;
		}
		for (Map.Entry<String, SharedInventorySetupSection> entry : sectionConflicts.entrySet())
		{
			if (metadata.isSectionDeletePending(entry.getKey()))
			{
				continue;
			}
			sections.remove(entry.getKey());
			SharedInventorySetupSection remote = entry.getValue();
			if (!remote.isDeleted())
			{
				sections.put(remote.getSectionId(), remote);
			}
			metadata.putSection(remote, remote.isDeleted() ? "" : remote.contentKey());
			metadata.clearSectionConflict(entry.getKey());
			changed = true;
		}
		if (orderConflict != null)
		{
			metadata.acceptManifest(orderConflict);
			metadata.clearOrderConflict();
			changed = true;
		}

		if (changed)
		{
			pruneSectionMemberships(setups, sections);
			applyLocal(setups, sections,
				orderConflict == null ? metadata.setupOrder() : orderConflict.getOrderedSetupIds(),
				orderConflict == null ? metadata.sectionOrder() : orderConflict.getOrderedSectionIds());
		}
		uploadAfterPoll = true;
		finishPoll(expectedGeneration);
	}

	private void capturePendingDeletes(Snapshot snapshot)
	{
		Set<String> localSetupIds = new HashSet<>(setupIds(snapshot.getSetups()));
		for (SharedInventorySetup cached : metadata.setups().values())
		{
			if (!cached.isDeleted() && !localSetupIds.contains(cached.getSetupId())
				&& !metadata.isSetupDeletePending(cached.getSetupId()))
			{
				metadata.pendingSetupDelete(cached.getSetupId(), cached.getRevision());
			}
		}
		Set<String> localSectionIds = new HashSet<>(sectionIds(snapshot.getSections()));
		for (SharedInventorySetupSection cached : metadata.sections().values())
		{
			if (!cached.isDeleted() && !localSectionIds.contains(cached.getSectionId())
				&& !metadata.isSectionDeletePending(cached.getSectionId()))
			{
				metadata.pendingSectionDelete(cached.getSectionId(), cached.getRevision());
			}
		}
	}

	private void uploadOne(int expectedGeneration)
	{
		if (!isCurrent(expectedGeneration) || plugin == null || !metadata.initialized())
		{
			return;
		}
		if (!uploadInFlight.compareAndSet(false, true))
		{
			uploadAfterPoll = true;
			return;
		}

		Snapshot snapshot = repository.snapshot(plugin);
		capturePendingDeletes(snapshot);
		Map<String, SharedInventorySetup> localSetups = setupsById(snapshot.getSetups());
		Map<String, SharedInventorySetupSection> localSections = sectionsById(snapshot.getSections());

		for (Map.Entry<String, Long> pending : metadata.pendingSetupDeletes().entrySet())
		{
			if (metadata.setupConflict(pending.getKey()) == null)
			{
				uploadSetupDelete(expectedGeneration, pending.getKey(), pending.getValue());
				return;
			}
		}

		for (SharedInventorySetup local : localSetups.values())
		{
			if (metadata.setupConflict(local.getSetupId()) != null)
			{
				continue;
			}
			SharedInventorySetup cached = metadata.setup(local.getSetupId());
			if (cached == null)
			{
				uploadSetupCreate(expectedGeneration, local);
				return;
			}
			if (cached.isDeleted() || !local.contentHash().equals(metadata.setupHash(local.getSetupId())))
			{
				uploadSetupUpdate(expectedGeneration, local, cached.getRevision());
				return;
			}
		}

		for (Map.Entry<String, Long> pending : metadata.pendingSectionDeletes().entrySet())
		{
			if (metadata.sectionConflict(pending.getKey()) == null)
			{
				uploadSectionDelete(expectedGeneration, pending.getKey(), pending.getValue());
				return;
			}
		}

		for (SharedInventorySetupSection local : localSections.values())
		{
			if (metadata.sectionConflict(local.getSectionId()) != null)
			{
				continue;
			}
			SharedInventorySetupSection cached = metadata.section(local.getSectionId());
			if (cached == null)
			{
				uploadSectionCreate(expectedGeneration, local);
				return;
			}
			if (cached.isDeleted() || !local.contentKey().equals(metadata.sectionHash(local.getSectionId())))
			{
				uploadSectionUpdate(expectedGeneration, local, cached.getRevision());
				return;
			}
		}

		if (metadata.orderConflict() == null && !setupIds(snapshot.getSetups()).equals(metadata.setupOrder()))
		{
			client.putSetupOrder(metadata.setupOrderRevision(), setupIds(snapshot.getSetups()),
				onThread(expectedGeneration, this::orderResponse, this::orderFailure));
			return;
		}
		if (metadata.orderConflict() == null && !sectionIds(snapshot.getSections()).equals(metadata.sectionOrder()))
		{
			client.putSectionOrder(metadata.sectionOrderRevision(), sectionIds(snapshot.getSections()),
				onThread(expectedGeneration, this::orderResponse, this::orderFailure));
			return;
		}

		uploadInFlight.set(false);
		schedulePoll();
	}

	private void uploadSetupCreate(int expectedGeneration, SharedInventorySetup local)
	{
		client.createSetup(local, onThread(expectedGeneration, stored ->
		{
			metadata.putSetup(stored, local.contentHash());
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, local.getSetupId(), true, failure)));
	}

	private void uploadSetupUpdate(int expectedGeneration, SharedInventorySetup local, long revision)
	{
		client.updateSetup(local, revision, onThread(expectedGeneration, stored ->
		{
			metadata.putSetup(stored, local.contentHash());
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, local.getSetupId(), true, failure)));
	}

	private void uploadSetupDelete(int expectedGeneration, String id, long revision)
	{
		client.deleteSetup(id, revision, onThread(expectedGeneration, tombstone ->
		{
			metadata.putSetup(tombstone, "");
			metadata.clearPendingSetupDelete(id);
			metadata.clearSetupConflict(id);
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, id, true, failure)));
	}

	private void uploadSectionCreate(int expectedGeneration, SharedInventorySetupSection local)
	{
		client.createSection(local, onThread(expectedGeneration, stored ->
		{
			metadata.putSection(stored, local.contentKey());
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, local.getSectionId(), false, failure)));
	}

	private void uploadSectionUpdate(int expectedGeneration, SharedInventorySetupSection local, long revision)
	{
		client.updateSection(local, revision, onThread(expectedGeneration, stored ->
		{
			metadata.putSection(stored, local.contentKey());
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, local.getSectionId(), false, failure)));
	}

	private void uploadSectionDelete(int expectedGeneration, String id, long revision)
	{
		client.deleteSection(id, revision, onThread(expectedGeneration, tombstone ->
		{
			metadata.putSection(tombstone, "");
			metadata.clearPendingSectionDelete(id);
			metadata.clearSectionConflict(id);
			uploadSucceeded();
		}, failure -> entityFailure(expectedGeneration, id, false, failure)));
	}

	private void entityFailure(int expectedGeneration, String localId, boolean setup,
		InventorySetupSyncFailure failure)
	{
		if (failure.getKind() != SyncFailure.Kind.CONFLICT)
		{
			uploadFailed(failure);
			return;
		}
		String remoteId = setup && failure.getCurrentSetup() != null
			? failure.getCurrentSetup().getSetupId()
			: !setup && failure.getCurrentSection() != null
				? failure.getCurrentSection().getSectionId() : null;
		if (remoteId == null)
		{
			uploadFailed(failure);
			return;
		}
		if (setup)
		{
			client.getSetup(remoteId, onThread(expectedGeneration, remote ->
			{
				metadata.setupConflict(localId, reason(failure), remote);
				uploadFailed(failure);
			}, ignored -> uploadFailed(failure)));
		}
		else
		{
			client.getSection(remoteId, onThread(expectedGeneration, remote ->
			{
				metadata.sectionConflict(localId, reason(failure), remote);
				uploadFailed(failure);
			}, ignored -> uploadFailed(failure)));
		}
	}

	private void orderResponse(InventorySetupManifest manifest)
	{
		metadata.orderConflict(manifest);
		uploadSucceeded();
	}

	private void orderFailure(InventorySetupSyncFailure failure)
	{
		if (failure.getKind() == SyncFailure.Kind.CONFLICT && failure.getCurrentManifest() != null)
		{
			metadata.orderConflict(failure.getCurrentManifest());
			uploadSucceeded();
			return;
		}
		uploadFailed(failure);
	}

	private void uploadSucceeded()
	{
		uploadInFlight.set(false);
		uploadAfterPoll = true;
		poll();
	}

	private void uploadFailed(InventorySetupSyncFailure failure)
	{
		log.debug("inventory setup upload failed: {} ({})", failure.getKind(), failure.getErrorCode());
		uploadInFlight.set(false);
		schedulePoll();
	}

	private void applyLocal(Map<String, SharedInventorySetup> setups,
		Map<String, SharedInventorySetupSection> sections, List<String> setupOrder, List<String> sectionOrder)
	{
		applyingRemote = true;
		try
		{
			repository.apply(plugin, orderedSetups(setups, setupOrder), orderedSections(sections, sectionOrder));
		}
		finally
		{
			applyingRemote = false;
		}
	}

	private void finishPoll(int expectedGeneration)
	{
		if (!isCurrent(expectedGeneration))
		{
			return;
		}
		pollInFlight.set(false);
		if (uploadAfterPoll)
		{
			uploadAfterPoll = false;
			requestUploadNow();
		}
		else
		{
			schedulePoll();
		}
	}

	private void pollFailed(int expectedGeneration, InventorySetupSyncFailure failure)
	{
		if (!isCurrent(expectedGeneration))
		{
			return;
		}
		log.debug("inventory setup manifest failed: {} ({})", failure.getKind(), failure.getErrorCode());
		finishPoll(expectedGeneration);
	}

	private void requestUploadNow()
	{
		if (!active)
		{
			return;
		}
		int expectedGeneration = generation;
		clientThread.invokeLater(() ->
		{
			if (isCurrent(expectedGeneration))
			{
				uploadOne(expectedGeneration);
			}
		});
	}

	private void schedulePoll()
	{
		if (!active || pollInFlight.get())
		{
			return;
		}
		cancel(pollFuture);
		int expectedGeneration = generation;
		pollFuture = executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (isCurrent(expectedGeneration))
			{
				poll();
			}
		}), Math.max(5, config.pollIntervalSeconds()), TimeUnit.SECONDS);
	}

	private boolean isCurrent(int expectedGeneration)
	{
		return active && expectedGeneration == generation;
	}

	private <T> InventorySetupSyncClient.Callback<T> onThread(int expectedGeneration,
		Consumer<T> success, Consumer<InventorySetupSyncFailure> failure)
	{
		return new InventorySetupSyncClient.Callback<T>()
		{
			@Override
			public void onSuccess(T value)
			{
				clientThread.invokeLater(() ->
				{
					if (isCurrent(expectedGeneration))
					{
						success.accept(value);
					}
				});
			}

			@Override
			public void onFailure(InventorySetupSyncFailure value)
			{
				clientThread.invokeLater(() ->
				{
					if (isCurrent(expectedGeneration))
					{
						failure.accept(value);
					}
				});
			}
		};
	}

	private static boolean matches(InventorySetupManifest.SetupEntry entry, SharedInventorySetup document)
	{
		return entry.getSetupId().equals(document.getSetupId())
			&& entry.getRevision() == document.getRevision() && entry.isDeleted() == document.isDeleted();
	}

	private static boolean matches(InventorySetupManifest.SectionEntry entry,
		SharedInventorySetupSection document)
	{
		return entry.getSectionId().equals(document.getSectionId())
			&& entry.getRevision() == document.getRevision() && entry.isDeleted() == document.isDeleted();
	}

	private static void pruneSectionMemberships(Map<String, SharedInventorySetup> setups,
		Map<String, SharedInventorySetupSection> sections)
	{
		for (Map.Entry<String, SharedInventorySetupSection> entry : new ArrayList<>(sections.entrySet()))
		{
			SharedInventorySetupSection section = entry.getValue();
			List<String> attached = new ArrayList<>();
			for (String setupId : section.getOrderedSetupIds())
			{
				if (setups.containsKey(setupId) && !attached.contains(setupId))
				{
					attached.add(setupId);
				}
			}
			if (!attached.equals(section.getOrderedSetupIds()))
			{
				sections.put(entry.getKey(), new SharedInventorySetupSection(section.getSectionId(),
					section.getName(), section.getDisplayColor(), attached, section.getRevision(), false));
			}
		}
	}

	private static Map<String, SharedInventorySetup> setupsById(List<SharedInventorySetup> setups)
	{
		Map<String, SharedInventorySetup> result = new LinkedHashMap<>();
		for (SharedInventorySetup setup : setups)
		{
			result.put(setup.getSetupId(), setup);
		}
		return result;
	}

	private static Map<String, SharedInventorySetupSection> sectionsById(
		List<SharedInventorySetupSection> sections)
	{
		Map<String, SharedInventorySetupSection> result = new LinkedHashMap<>();
		for (SharedInventorySetupSection section : sections)
		{
			result.put(section.getSectionId(), section);
		}
		return result;
	}

	private static List<String> setupIds(List<SharedInventorySetup> setups)
	{
		List<String> result = new ArrayList<>();
		for (SharedInventorySetup setup : setups)
		{
			result.add(setup.getSetupId());
		}
		return result;
	}

	private static List<String> sectionIds(List<SharedInventorySetupSection> sections)
	{
		List<String> result = new ArrayList<>();
		for (SharedInventorySetupSection section : sections)
		{
			result.add(section.getSectionId());
		}
		return result;
	}

	static List<SharedInventorySetup> orderedSetups(Map<String, SharedInventorySetup> source,
		List<String> order)
	{
		Map<String, SharedInventorySetup> remaining = new LinkedHashMap<>(source);
		List<SharedInventorySetup> result = new ArrayList<>();
		for (String id : order)
		{
			SharedInventorySetup setup = remaining.remove(id);
			if (setup != null)
			{
				result.add(setup);
			}
		}
		result.addAll(remaining.values());
		return result;
	}

	static List<SharedInventorySetupSection> orderedSections(
		Map<String, SharedInventorySetupSection> source, List<String> order)
	{
		Map<String, SharedInventorySetupSection> remaining = new LinkedHashMap<>(source);
		List<SharedInventorySetupSection> result = new ArrayList<>();
		for (String id : order)
		{
			SharedInventorySetupSection section = remaining.remove(id);
			if (section != null)
			{
				result.add(section);
			}
		}
		result.addAll(remaining.values());
		return result;
	}

	private static String reason(InventorySetupSyncFailure failure)
	{
		return failure.getErrorCode() == null ? "conflict" : failure.getErrorCode();
	}

	private static boolean blank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static void cancel(ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static final class ReconcileStage
	{
		private final InventorySetupManifest manifest;
		private final Snapshot snapshot;
		private final Map<String, SharedInventorySetup> remoteSetups = new LinkedHashMap<>();
		private final Map<String, SharedInventorySetupSection> remoteSections = new LinkedHashMap<>();
		private final Map<String, SharedInventorySetup> setupPuts = new LinkedHashMap<>();
		private final Map<String, SharedInventorySetupSection> sectionPuts = new LinkedHashMap<>();
		private final Map<String, SharedInventorySetup> setupConflicts = new LinkedHashMap<>();
		private final Map<String, SharedInventorySetupSection> sectionConflicts = new LinkedHashMap<>();
		private final Set<String> clearSetupConflicts = new HashSet<>();
		private final Set<String> clearSectionConflicts = new HashSet<>();
		private final Set<String> clearPendingSetupDeletes = new HashSet<>();
		private final Set<String> clearPendingSectionDeletes = new HashSet<>();
		private boolean orderConflict;
		private boolean clearOrderConflict;

		private ReconcileStage(InventorySetupManifest manifest, Snapshot snapshot)
		{
			this.manifest = manifest;
			this.snapshot = snapshot;
		}

		private void commit(InventorySetupSyncMetadata metadata)
		{
			for (SharedInventorySetup setup : setupPuts.values())
			{
				metadata.putSetup(setup, setup.isDeleted() ? "" : setup.contentHash());
			}
			for (SharedInventorySetupSection section : sectionPuts.values())
			{
				metadata.putSection(section, section.isDeleted() ? "" : section.contentKey());
			}
			for (Map.Entry<String, SharedInventorySetup> conflict : setupConflicts.entrySet())
			{
				metadata.setupConflict(conflict.getKey(), "remote_changed", conflict.getValue());
			}
			for (Map.Entry<String, SharedInventorySetupSection> conflict : sectionConflicts.entrySet())
			{
				metadata.sectionConflict(conflict.getKey(), "remote_changed", conflict.getValue());
			}
			for (String id : clearSetupConflicts) metadata.clearSetupConflict(id);
			for (String id : clearSectionConflicts) metadata.clearSectionConflict(id);
			for (String id : clearPendingSetupDeletes) metadata.clearPendingSetupDelete(id);
			for (String id : clearPendingSectionDeletes) metadata.clearPendingSectionDelete(id);
			metadata.acceptManifest(manifest);
			if (orderConflict) metadata.orderConflict(manifest);
			if (clearOrderConflict) metadata.clearOrderConflict();
		}
	}
}
