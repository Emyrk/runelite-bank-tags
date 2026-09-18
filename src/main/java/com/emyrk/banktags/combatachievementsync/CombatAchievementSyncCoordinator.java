package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.emyrk.banktags.sync.model.SyncFailure;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

/** Coordinates latest-only exact Combat Achievement progress uploads. */
@Slf4j
@Singleton
public class CombatAchievementSyncCoordinator
{
	private final CombatAchievementSyncClient client;
	private final CombatAchievementSnapshotService snapshots;
	private final BankTagsConfig config;
	private final Client runeLiteClient;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private boolean active;
	private boolean loggedIn;
	private boolean uploadInFlight;
	private boolean uploadPending;
	private int generation;
	private ScheduledFuture<?> debounceFuture;

	@Inject
	public CombatAchievementSyncCoordinator(CombatAchievementSyncClient client,
		CombatAchievementSnapshotService snapshots, BankTagsConfig config, Client runeLiteClient,
		ScheduledExecutorService executor, ClientThread clientThread)
	{
		this.client = client;
		this.snapshots = snapshots;
		this.config = config;
		this.runeLiteClient = runeLiteClient;
		this.executor = executor;
		this.clientThread = clientThread;
	}

	public void start()
	{
		if (active || !config.enabled() || isBlank(config.groupName()) || isBlank(config.groupToken()))
		{
			return;
		}
		active = true;
		if (runeLiteClient.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
			uploadLatest();
		}
	}

	public void stop()
	{
		active = false;
		clearSession();
	}

	public void onGameStateChanged(GameState gameState)
	{
		if (!active)
		{
			return;
		}
		if (gameState == GameState.LOGGED_IN)
		{
			loggedIn = true;
			uploadLatest();
		}
		else
		{
			clearSession();
		}
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		if (!active || !loggedIn || (!CombatAchievementCatalog.isCompletionVarp(event.getVarpId())
			&& !CombatAchievementCatalog.isTaskVarbit(event.getVarbitId())
			&& event.getVarbitId() != VarbitID.CA_POINTS))
		{
			return;
		}
		cancel(debounceFuture);
		int expectedGeneration = generation;
		debounceFuture = executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (isCurrent(expectedGeneration))
			{
				debounceFuture = null;
				uploadLatest();
			}
		}), Math.max(1, config.uploadDebounceSeconds()), TimeUnit.SECONDS);
	}

	public void forceResync()
	{
		if (!active || !loggedIn)
		{
			return;
		}
		cancel(debounceFuture);
		debounceFuture = null;
		uploadLatest();
	}

	private void uploadLatest()
	{
		if (!active || !loggedIn)
		{
			return;
		}
		if (uploadInFlight)
		{
			uploadPending = true;
			return;
		}
		CombatAchievementProgress progress = snapshots.snapshot();
		if (progress == null)
		{
			return;
		}
		uploadInFlight = true;
		int expectedGeneration = generation;
		client.putProgress(progress, new CombatAchievementSyncClient.Callback()
		{
			@Override
			public void onSuccess()
			{
				onComplete(expectedGeneration, null);
			}

			@Override
			public void onFailure(SyncFailure failure)
			{
				onComplete(expectedGeneration, failure);
			}
		});
	}

	private void onComplete(int expectedGeneration, @Nullable SyncFailure failure)
	{
		clientThread.invokeLater(() ->
		{
			if (!isCurrent(expectedGeneration))
			{
				return;
			}
			uploadInFlight = false;
			if (failure != null)
			{
				log.debug("combat achievement progress upload failed: {}", failure.getKind());
			}
			if (uploadPending)
			{
				uploadPending = false;
				uploadLatest();
			}
		});
	}

	private void clearSession()
	{
		generation++;
		loggedIn = false;
		uploadInFlight = false;
		uploadPending = false;
		cancel(debounceFuture);
		debounceFuture = null;
		client.cancelAll();
	}

	private boolean isCurrent(int expectedGeneration)
	{
		return active && loggedIn && generation == expectedGeneration;
	}

	private static void cancel(@Nullable ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static boolean isBlank(@Nullable String value)
	{
		return value == null || value.trim().isEmpty();
	}
}
