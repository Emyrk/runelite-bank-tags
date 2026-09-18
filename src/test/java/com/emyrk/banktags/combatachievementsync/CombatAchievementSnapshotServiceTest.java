package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CombatAchievementSnapshotServiceTest
{
	@Test
	public void snapshotUsesNormalizedUsernameRevisionAndExactCompletedIds()
	{
		Client client = mock(Client.class);
		when(client.getUsername()).thenReturn(" <col=ff0000>Display\u00a0 Name</col> ");
		when(client.getRevision()).thenReturn(123);
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(456);
		when(client.getVarbitValue(VarbitID.CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.CA_TASK_LIZARDMAN_SHAMAN_PERFECTION_1_COMPLETED)).thenReturn(1);

		CombatAchievementProgress progress = new CombatAchievementSnapshotService(client).snapshot();

		assertEquals("Display Name", progress.getPlayerName());
		assertEquals(123, progress.getClientRevision());
		assertEquals(456, progress.getAchievementPoints());
		assertEquals(Arrays.asList(
			"CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED",
			"CA_TASK_LIZARDMAN_SHAMAN_PERFECTION_1_COMPLETED"), progress.getCompletedTaskIds());
	}

	@Test
	public void negativeAchievementPointsAreClampedToZero()
	{
		Client client = mock(Client.class);
		when(client.getUsername()).thenReturn("Display Name");
		when(client.getVarbitValue(VarbitID.CA_POINTS)).thenReturn(-1);

		CombatAchievementProgress progress = new CombatAchievementSnapshotService(client).snapshot();

		assertEquals(0, progress.getAchievementPoints());
	}

	@Test
	public void blankUsernameHasNoUploadableSnapshot()
	{
		Client client = mock(Client.class);
		when(client.getUsername()).thenReturn("  ");
		assertNull(new CombatAchievementSnapshotService(client).snapshot());
	}
}
