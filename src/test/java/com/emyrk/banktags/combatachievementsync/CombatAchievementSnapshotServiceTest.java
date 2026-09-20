package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CombatAchievementSnapshotServiceTest
{
	@Test
	public void snapshotUsesNormalizedLocalPlayerDisplayNameRevisionAndExactCompletedIds()
	{
		Client client = mock(Client.class);
		Player localPlayer = mock(Player.class);
		when(client.getUsername()).thenReturn("login-identity@example.com");
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getName()).thenReturn(" <col=ff0000>Display\u00a0 Name</col> ");
		when(client.getRevision()).thenReturn(123);
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(456);
		when(client.getVarpValue(VarPlayerID.CA_TASK_COMPLETED_0)).thenReturn((1 << 0) | (1 << 31));
		when(client.getVarpValue(VarPlayerID.CA_TASK_COMPLETED_16)).thenReturn(1 << 11);
		when(client.getVarpValue(VarPlayerID.CA_TASK_COMPLETED_20)).thenReturn(1 << 31);

		CombatAchievementProgress progress = new CombatAchievementSnapshotService(client).snapshot();

		assertEquals("Display Name", progress.getPlayerName());
		assertEquals(123, progress.getClientRevision());
		assertEquals(456, progress.getAchievementPoints());
		assertEquals(Arrays.asList(0, 31, 523, 671), progress.getCompletedTaskIds());
	}

	@Test
	public void negativeAchievementPointsAreClampedToZero()
	{
		Client client = mock(Client.class);
		Player localPlayer = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getName()).thenReturn("Display Name");
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(-1);

		CombatAchievementProgress progress = new CombatAchievementSnapshotService(client).snapshot();

		assertEquals(0, progress.getAchievementPoints());
	}

	@Test
	public void nullLocalPlayerHasNoUploadableSnapshot()
	{
		Client client = mock(Client.class);
		when(client.getUsername()).thenReturn("login-identity@example.com");
		assertNull(new CombatAchievementSnapshotService(client).snapshot());
	}

	@Test
	public void blankLocalPlayerNameHasNoUploadableSnapshot()
	{
		Client client = mock(Client.class);
		Player localPlayer = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getName()).thenReturn("  ");
		assertNull(new CombatAchievementSnapshotService(client).snapshot());
	}
}
