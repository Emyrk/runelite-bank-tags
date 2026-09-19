package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.FakeScheduler;
import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CombatAchievementSyncCoordinatorTest
{
	private CombatAchievementSyncClient syncClient;
	private CombatAchievementSnapshotService snapshots;
	private BankTagsConfig config;
	private Client client;
	private ClientThread clientThread;
	private FakeScheduler executor;
	private CombatAchievementSyncCoordinator coordinator;

	@Before
	public void setUp()
	{
		syncClient = mock(CombatAchievementSyncClient.class);
		snapshots = mock(CombatAchievementSnapshotService.class);
		config = mock(BankTagsConfig.class);
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		executor = new FakeScheduler();
		when(config.enabled()).thenReturn(true);
		when(config.groupName()).thenReturn("group");
		when(config.groupToken()).thenReturn("token");
		when(config.uploadDebounceSeconds()).thenReturn(1);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return true;
		}).when(clientThread).invokeLater(any(Runnable.class));
		coordinator = new CombatAchievementSyncCoordinator(syncClient, snapshots, config, client,
			executor, clientThread);
	}

	@Test
	public void startupUploadsFullSnapshotWhenAlreadyLoggedIn()
	{
		CombatAchievementProgress progress = progress("first");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(snapshots.snapshot()).thenReturn(progress);

		coordinator.start();

		verify(syncClient).putProgress(any(CombatAchievementProgress.class), any());
	}

	@Test
	public void loggedInEventUploadsFullSnapshot()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(snapshots.snapshot()).thenReturn(progress("login"));
		coordinator.start();

		coordinator.onGameStateChanged(GameState.LOGGED_IN);

		verify(syncClient).putProgress(any(CombatAchievementProgress.class), any());
	}

	@Test
	public void loggedInSnapshotRetriesWhenLocalPlayerIsInitiallyUnavailable()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(snapshots.snapshot()).thenReturn(null, progress("retry"));
		coordinator.start();

		coordinator.onGameStateChanged(GameState.LOGGED_IN);

		assertEquals(1, executor.pendingCount());
		assertEquals(CombatAchievementSyncCoordinator.SNAPSHOT_RETRY_DELAY_SECONDS, executor.nextDelaySeconds());
		verify(syncClient, never()).putProgress(any(), any());

		executor.runDue(CombatAchievementSyncCoordinator.SNAPSHOT_RETRY_DELAY_SECONDS);

		verify(syncClient).putProgress(any(CombatAchievementProgress.class), any());
		assertEquals(0, executor.pendingCount());
	}

	@Test
	public void loggedInSnapshotRetryIsBounded()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(snapshots.snapshot()).thenReturn(null);

		coordinator.start();
		for (int attempt = 0; attempt < CombatAchievementSyncCoordinator.MAX_SNAPSHOT_RETRIES; attempt++)
		{
			assertEquals(1, executor.pendingCount());
			executor.runDue(CombatAchievementSyncCoordinator.SNAPSHOT_RETRY_DELAY_SECONDS);
		}

		assertEquals(0, executor.pendingCount());
		verify(snapshots, times(CombatAchievementSyncCoordinator.MAX_SNAPSHOT_RETRIES + 1)).snapshot();
		verify(syncClient, never()).putProgress(any(), any());
	}

	@Test
	public void logoutCancelsPendingSnapshotRetry()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(snapshots.snapshot()).thenReturn(null);
		coordinator.start();
		assertEquals(1, executor.pendingCount());

		coordinator.onGameStateChanged(GameState.LOGIN_SCREEN);
		executor.runDue(CombatAchievementSyncCoordinator.SNAPSHOT_RETRY_DELAY_SECONDS);

		assertEquals(0, executor.pendingCount());
		verify(snapshots, times(1)).snapshot();
		verify(syncClient, never()).putProgress(any(), any());
	}

	@Test
	public void relevantVarpDebouncesAndLatestSnapshotFollowsInflightUpload()
	{
		CombatAchievementProgress first = progress("first");
		CombatAchievementProgress latest = progress("latest");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(snapshots.snapshot()).thenReturn(first, latest);
		coordinator.start();

		VarbitChanged changed = new VarbitChanged();
		changed.setVarpId(VarPlayerID.CA_TASK_COMPLETED_0);
		coordinator.onVarbitChanged(changed);
		coordinator.onVarbitChanged(changed);
		assertEquals(1, executor.pendingCount());
		executor.runDue(1);

		ArgumentCaptor<CombatAchievementSyncClient.Callback> callbacks =
			ArgumentCaptor.forClass(CombatAchievementSyncClient.Callback.class);
		verify(syncClient).putProgress(any(CombatAchievementProgress.class), callbacks.capture());
		callbacks.getValue().onSuccess();

		ArgumentCaptor<CombatAchievementProgress> uploads = ArgumentCaptor.forClass(CombatAchievementProgress.class);
		verify(syncClient, times(2)).putProgress(uploads.capture(), any());
		assertEquals(Arrays.asList(first, latest), uploads.getAllValues());
	}

	@Test
	public void achievementPointChangeSchedulesUpload()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(snapshots.snapshot()).thenReturn(null, progress("points"));
		coordinator.start();
		coordinator.onGameStateChanged(GameState.LOGGED_IN);
		assertEquals(1, executor.pendingCount());

		VarbitChanged changed = new VarbitChanged();
		changed.setVarbitId(VarbitID.CA_POINTS);
		coordinator.onVarbitChanged(changed);

		assertEquals(2, executor.pendingCount());
		executor.runDue(1);
		verify(syncClient).putProgress(any(CombatAchievementProgress.class), any());
	}

	@Test
	public void unrelatedVarpDoesNotScheduleUpload()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(snapshots.snapshot()).thenReturn(progress("login"));
		coordinator.start();
		coordinator.onGameStateChanged(GameState.LOGGED_IN);

		VarbitChanged changed = new VarbitChanged();
		changed.setVarpId(-1);
		changed.setVarbitId(-1);
		coordinator.onVarbitChanged(changed);

		assertEquals(0, executor.pendingCount());
	}

	@Test
	public void leavingLoggedInStateCancelsAndIgnoresOldCompletion()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(snapshots.snapshot()).thenReturn(progress("first"), progress("should-not-upload"));
		coordinator.start();
		ArgumentCaptor<CombatAchievementSyncClient.Callback> callback =
			ArgumentCaptor.forClass(CombatAchievementSyncClient.Callback.class);
		verify(syncClient).putProgress(any(), callback.capture());

		coordinator.forceResync();
		coordinator.onGameStateChanged(GameState.LOGIN_SCREEN);
		callback.getValue().onSuccess();

		verify(syncClient).cancelAll();
		verify(syncClient, times(1)).putProgress(any(), any());
	}

	@Test
	public void disabledOrIncompleteCredentialsNeverActivate()
	{
		when(config.enabled()).thenReturn(false);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		coordinator.start();
		coordinator.onGameStateChanged(GameState.LOGGED_IN);
		coordinator.forceResync();

		verify(syncClient, never()).putProgress(any(), any());
	}

	private static CombatAchievementProgress progress(String id)
	{
		return new CombatAchievementProgress("Display Name", 123, 456, Collections.singletonList(id));
	}
}
