package com.emyrk.banktags.sync.model;

import java.util.Arrays;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

public class SharedBankTagTest
{
	@Test
	public void testNormalizesItemsAndProducesDeterministicHash()
	{
		SharedBankTag first = new SharedBankTag("id-a", "Herb Tag", 952,
			Arrays.asList(300, -200, 100, 300), new int[]{300, -1, 100}, 1, false);
		SharedBankTag second = new SharedBankTag("id-b", "herb tag", 952,
			Arrays.asList(100, 300, -200), new int[]{300, -1, 100}, 99, false);

		assertEquals(Arrays.asList(-200, 100, 300), first.getItemIds());
		assertEquals(first.contentHash(), second.contentHash());
	}

	@Test
	public void testHashIncludesLayoutAndReturnsDefensiveCopy()
	{
		int[] layout = {100, -1, 200};
		SharedBankTag tag = new SharedBankTag(null, "test", 952,
			Arrays.asList(100, 200), layout, 0, false);
		layout[0] = 999;

		assertArrayEquals(new int[]{100, -1, 200}, tag.getLayout());
		assertNotEquals(tag.contentHash(), new SharedBankTag(null, "test", 952,
			Arrays.asList(100, 200), new int[]{200, -1, 100}, 0, false).contentHash());
	}
}
