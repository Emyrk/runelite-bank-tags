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

		VarbitChanged changed = new VarbitChanged();
		changed.setVarbitId(VarbitID.CA_POINTS);
		coordinator.onVarbitChanged(changed);

		assertEquals(1, executor.pendingCount());
		executor.runDue(1);
		verify(syncClient).putProgress(any(CombatAchievementProgress.class), any());
	}

	@Test
	public void unrelatedVarpDoesNotScheduleUpload()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
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
