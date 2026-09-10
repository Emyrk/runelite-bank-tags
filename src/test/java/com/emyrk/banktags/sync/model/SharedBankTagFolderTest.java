package com.emyrk.banktags.sync.model;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SharedBankTagFolderTest
{
	@Test
	public void membershipIsCanonicalAndHashIgnoresInputOrder()
	{
		SharedBankTagFolder first = new SharedBankTagFolder("id", " Gathering ", 952, Arrays.asList("b", "a", "b"), 1, false, null);
		SharedBankTagFolder second = new SharedBankTagFolder("id", "Gathering", 952, Arrays.asList("b", "a"), 9, false, null);
		assertEquals(Arrays.asList("b", "a"), first.getOrderedTagIds());
		assertEquals(first.contentHash(), second.contentHash());
	}
}
