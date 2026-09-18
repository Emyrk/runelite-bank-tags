package com.emyrk.banktags.combatachievementsync;

import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatAchievementCatalogTest
{
	@Test
	public void catalogContainsAllExactTaskConstants()
	{
		assertEquals(399, CombatAchievementCatalog.tasks().size());
		Set<String> names = CombatAchievementCatalog.tasks().stream()
			.map(CombatAchievementCatalog.Task::getId)
			.collect(Collectors.toSet());
		Set<Integer> varbits = CombatAchievementCatalog.tasks().stream()
			.map(CombatAchievementCatalog.Task::getVarbitId)
			.collect(Collectors.toSet());
		assertEquals(399, names.size());
		assertEquals(399, varbits.size());
		assertTrue(names.contains("CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED"));
		assertTrue(names.contains("CA_TASK_LIZARDMAN_SHAMAN_PERFECTION_1_COMPLETED"));
	}

	@Test
	public void completionVarpsAreExplicitlyRecognized()
	{
		assertTrue(CombatAchievementCatalog.isCompletionVarp(VarPlayerID.CA_TASK_COMPLETED_0));
		assertTrue(CombatAchievementCatalog.isCompletionVarp(VarPlayerID.CA_TASK_COMPLETED_19));
		assertFalse(CombatAchievementCatalog.isCompletionVarp(-1));
	}
}
