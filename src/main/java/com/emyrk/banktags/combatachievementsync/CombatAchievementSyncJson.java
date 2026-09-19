package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;
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

	public SyncFailure parseErrorBody(int status, @Nullable String body)
	{
		String errorCode = null;
		String message = null;
		if (body != null && !body.trim().isEmpty())
		{
			try
			{
				JsonElement element = gson.fromJson(body, JsonElement.class);
				if (element != null && element.isJsonObject())
				{
					JsonObject object = element.getAsJsonObject();
					errorCode = optionalString(object, "error");
					message = optionalString(object, "message");
				}
			}
			catch (RuntimeException ignored)
			{
				// Status-only diagnostics still work when an upstream response is not protocol JSON.
			}
		}
		return new SyncFailure(SyncFailure.kindForStatus(status), status, errorCode, message, null, null);
	}

	@Nullable
	private static String optionalString(JsonObject object, String field)
	{
		JsonElement element = object.get(field);
		return element != null && !element.isJsonNull() && element.isJsonPrimitive()
			&& element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
	}
}
