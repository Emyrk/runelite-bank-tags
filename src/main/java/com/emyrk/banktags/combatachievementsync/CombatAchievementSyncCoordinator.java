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
	static final int SNAPSHOT_RETRY_DELAY_SECONDS = 1;
	static final int MAX_SNAPSHOT_RETRIES = 5;

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
	private int snapshotRetryCount;
	private ScheduledFuture<?> debounceFuture;
	private ScheduledFuture<?> snapshotRetryFuture;

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
			enterLoggedIn();
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
			enterLoggedIn();
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

	private void enterLoggedIn()
	{
		boolean newSession = !loggedIn;
		loggedIn = true;
		if (newSession)
		{
			cancel(snapshotRetryFuture);
			snapshotRetryFuture = null;
			snapshotRetryCount = 0;
		}
		uploadLatest(newSession);
	}

	private void uploadLatest()
	{
		uploadLatest(false);
	}

	private void uploadLatest(boolean retryUnavailableSnapshot)
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
			if (retryUnavailableSnapshot)
			{
				scheduleSnapshotRetry();
			}
			return;
		}
		cancel(snapshotRetryFuture);
		snapshotRetryFuture = null;
		snapshotRetryCount = 0;
		uploadInFlight = true;
		int expectedGeneration = generation;
		client.putProgress(progress, new CombatAchievementSyncClient.Callback()
		{
			@Override
			public void onSuccess()
			{
				onComplete(expectedGeneration, progress, null);
			}

			@Override
			public void onFailure(SyncFailure failure)
			{
				onComplete(expectedGeneration, progress, failure);
			}
		});
	}

	private void scheduleSnapshotRetry()
	{
		if (snapshotRetryFuture != null || snapshotRetryCount >= MAX_SNAPSHOT_RETRIES)
		{
			return;
		}
		snapshotRetryCount++;
		int expectedGeneration = generation;
		snapshotRetryFuture = executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (isCurrent(expectedGeneration))
			{
				snapshotRetryFuture = null;
				uploadLatest(true);
			}
		}), SNAPSHOT_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	private void onComplete(int expectedGeneration, CombatAchievementProgress progress,
		@Nullable SyncFailure failure)
	{
		clientThread.invokeLater(() ->
		{
			if (!isCurrent(expectedGeneration))
			{
				return;
			}
			uploadInFlight = false;
			if (failure == null)
			{
				log.debug("combat achievement progress uploaded: completedTasks={}, achievementPoints={}",
					progress.getCompletedTaskIds().size(), progress.getAchievementPoints());
			}
			else
			{
				log.warn("combat achievement progress upload failed: kind={}, httpStatus={}, errorCode={}",
					failure.getKind(), failure.getHttpStatus(), safeErrorCode(failure.getErrorCode()));
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
		snapshotRetryCount = 0;
		cancel(debounceFuture);
		debounceFuture = null;
		cancel(snapshotRetryFuture);
		snapshotRetryFuture = null;
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

	private static String safeErrorCode(@Nullable String errorCode)
	{
		if (errorCode == null || !errorCode.matches("[A-Za-z0-9_.-]{1,64}"))
		{
			return "unavailable";
		}
		return errorCode;
	}

	private static boolean isBlank(@Nullable String value)
	{
		return value == null || value.trim().isEmpty();
	}
}
