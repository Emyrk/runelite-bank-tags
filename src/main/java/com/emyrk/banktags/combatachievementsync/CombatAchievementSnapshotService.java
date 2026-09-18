package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/** Reads an exact Combat Achievement snapshot from RuneLite client state. Client thread only. */
@Singleton
public class CombatAchievementSnapshotService
{
	private final Client client;

	@Inject
	public CombatAchievementSnapshotService(Client client)
	{
		this.client = client;
	}

	@Nullable
	public CombatAchievementProgress snapshot()
	{
		String playerName = normalizePlayerName(client.getUsername());
		if (playerName.isEmpty())
		{
			return null;
		}

		List<String> completed = new ArrayList<>();
		for (CombatAchievementCatalog.Task task : CombatAchievementCatalog.tasks())
		{
			if (client.getVarbitValue(task.getVarbitId()) != 0)
			{
				completed.add(task.getId());
			}
		}
		int achievementPoints = Math.max(0, client.getVarbitValue(VarbitID.CA_POINTS));
		return new CombatAchievementProgress(playerName, client.getRevision(), achievementPoints, completed);
	}

	static String normalizePlayerName(@Nullable String username)
	{
		if (username == null)
		{
			return "";
		}
		return Text.removeTags(username).replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
	}
}
