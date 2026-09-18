package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;

/** JSON encoder for the Combat Achievement progress v1 request. */
@Singleton
public class CombatAchievementSyncJson
{
	private final Gson gson;

	@Inject
	public CombatAchievementSyncJson(Gson gson)
	{
		this.gson = gson;
	}

	public String progressRequest(CombatAchievementProgress progress)
	{
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", CombatAchievementProgress.SCHEMA_VERSION);
		object.addProperty("playerName", progress.getPlayerName());
		object.addProperty("clientRevision", progress.getClientRevision());
		object.addProperty("achievementPoints", progress.getAchievementPoints());
		JsonArray completed = new JsonArray();
		for (String taskId : progress.getCompletedTaskIds())
		{
			completed.add(taskId);
		}
		object.add("completedTaskIds", completed);
		return gson.toJson(object);
	}
}
