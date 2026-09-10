package com.emyrk.banktags.tabs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LayoutManagerSyncSafetyTest
{
	private final Set<Integer> taggedItems = new HashSet<>(Arrays.asList(100, 200, -300));

	@Test
	public void unrelatedBankItemIsNotPersistedIntoLayout()
	{
		assertFalse(LayoutManager.belongsToTag(taggedItems, 999, 999, 999));
	}

	@Test
	public void exactCanonicalAndVariationItemsRemainEligible()
	{
		assertTrue(LayoutManager.belongsToTag(taggedItems, 100, 100, 100));
		assertTrue(LayoutManager.belongsToTag(taggedItems, 201, 200, 201));
		assertTrue(LayoutManager.belongsToTag(taggedItems, 301, 301, 300));
	}
}
