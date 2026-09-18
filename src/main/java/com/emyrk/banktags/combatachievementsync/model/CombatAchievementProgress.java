package com.emyrk.banktags.combatachievementsync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable exact Combat Achievement completion snapshot for one player. */
public final class CombatAchievementProgress
{
	public static final int SCHEMA_VERSION = 1;

	private final String playerName;
	private final int clientRevision;
	private final List<String> completedTaskIds;

	public CombatAchievementProgress(String playerName, int clientRevision, List<String> completedTaskIds)
	{
		this.playerName = Objects.requireNonNull(playerName, "playerName");
		this.clientRevision = clientRevision;
		this.completedTaskIds = Collections.unmodifiableList(
			new ArrayList<>(new LinkedHashSet<>(Objects.requireNonNull(completedTaskIds, "completedTaskIds"))));
	}

	public String getPlayerName()
	{
		return playerName;
	}

	public int getClientRevision()
	{
		return clientRevision;
	}

	public List<String> getCompletedTaskIds()
	{
		return completedTaskIds;
	}
}
