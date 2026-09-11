package com.emyrk.banktags;

import inventorysetups.InventorySetupsPlugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BundledInventorySetupsTest
{
	@Test
	public void companionDependsOnBankTagsExtendedAndStartsByDefault()
	{
		PluginDependency dependency = InventorySetupsPlugin.class.getAnnotation(PluginDependency.class);
		assertEquals(BankTagsPlugin.class, dependency.value());

		PluginDescriptor descriptor = InventorySetupsPlugin.class.getAnnotation(PluginDescriptor.class);
		assertEquals("Inventory Setups", descriptor.name());
		assertEquals("", descriptor.configName());
		assertTrue(descriptor.enabledByDefault());
	}

	@Test
	public void companionRetainsExistingInventorySetupConfiguration()
	{
		assertEquals("inventorysetups", InventorySetupsPlugin.CONFIG_GROUP);
	}
}
