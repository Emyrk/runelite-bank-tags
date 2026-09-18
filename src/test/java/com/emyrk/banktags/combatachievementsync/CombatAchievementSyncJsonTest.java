package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CombatAchievementSyncJsonTest
{
	@Test
	public void requestMatchesProtocolFixture()
	{
		Gson gson = new Gson();
		CombatAchievementProgress progress = new CombatAchievementProgress("Display Name", 123, Arrays.asList(
			"CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED",
			"CA_TASK_LIZARDMAN_SHAMAN_PERFECTION_1_COMPLETED"));
		JsonElement actual = gson.fromJson(new CombatAchievementSyncJson(gson).progressRequest(progress), JsonElement.class);
		JsonElement expected = gson.fromJson(new InputStreamReader(getClass().getResourceAsStream(
			"/fixtures/sync/combat-achievements/v1/progress-request.json"), StandardCharsets.UTF_8), JsonElement.class);
		assertEquals(expected, actual);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void completedTaskIdsAreImmutable()
	{
		new CombatAchievementProgress("Display Name", 123, Arrays.asList("one"))
			.getCompletedTaskIds().add("two");
	}
}
