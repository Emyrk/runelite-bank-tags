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
		CombatAchievementProgress progress = new CombatAchievementProgress("Display Name", 123, 456,
			Arrays.asList(0, 523));
		JsonElement actual = gson.fromJson(new CombatAchievementSyncJson(gson).progressRequest(progress), JsonElement.class);
		JsonElement expected = gson.fromJson(new InputStreamReader(getClass().getResourceAsStream(
			"/fixtures/sync/combat-achievements/v2/progress-request.json"), StandardCharsets.UTF_8), JsonElement.class);
		assertEquals(expected, actual);
	}

	@Test(expected = IllegalArgumentException.class)
	public void achievementPointsMustBeNonnegative()
	{
		new CombatAchievementProgress("Display Name", 123, -1, Arrays.asList(1));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void completedTaskIdsAreImmutable()
	{
		new CombatAchievementProgress("Display Name", 123, 456, Arrays.asList(1))
			.getCompletedTaskIds().add(2);
	}
}
