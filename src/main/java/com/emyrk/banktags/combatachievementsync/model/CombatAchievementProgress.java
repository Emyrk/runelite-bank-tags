package com.emyrk.banktags.combatachievementsync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable packed Combat Achievement completion snapshot for one player. */
public final class CombatAchievementProgress
{
	public static final int SCHEMA_VERSION = 2;

	private final String playerName;
	private final int clientRevision;
	private final int achievementPoints;
	private final List<Integer> completedTaskIds;

	public CombatAchievementProgress(String playerName, int clientRevision, int achievementPoints,
		List<Integer> completedTaskIds)
	{
		if (achievementPoints < 0)
		{
			throw new IllegalArgumentException("achievementPoints must be nonnegative");
		}
		this.playerName = Objects.requireNonNull(playerName, "playerName");
		this.clientRevision = clientRevision;
		this.achievementPoints = achievementPoints;
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

	public int getAchievementPoints()
	{
		return achievementPoints;
	}

	public List<Integer> getCompletedTaskIds()
	{
		return completedTaskIds;
	}
}
