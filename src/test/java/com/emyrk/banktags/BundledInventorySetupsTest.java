package com.emyrk.banktags;

import com.google.inject.Guice;
import com.google.inject.Injector;
import inventorysetups.InventorySetupsPlugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BundledInventorySetupsTest
{
	@Test
	public void companionDependsOnBankTagsExtendedAndStartsByDefault()
	{
		PluginDependency dependency = InventorySetupsPlugin.class.getAnnotation(PluginDependency.class);
		assertEquals(BankTagsPlugin.class, dependency.value());

		PluginDescriptor descriptor = InventorySetupsPlugin.class.getAnnotation(PluginDescriptor.class);
		assertEquals("Inventory Setups Extended", descriptor.name());
		assertEquals("inventorySetupsExtended", descriptor.configName());
		assertEquals(1, descriptor.conflicts().length);
		assertEquals("Inventory Setups", descriptor.conflicts()[0]);
		assertTrue(descriptor.enabledByDefault());
	}

	@Test
	public void bankTagsExtendedExposesPluginInstanceToCompanionPlugins()
	{
		BankTagsPlugin plugin = new BankTagsPlugin();
		Injector injector = Guice.createInjector(plugin.getPublicModule());

		assertSame(plugin, injector.getInstance(BankTagsPlugin.class));
		assertSame(plugin, injector.getInstance(BankTagsService.class));
	}

	@Test
	public void companionRetainsExistingInventorySetupConfiguration()
	{
		assertEquals("inventorysetups", InventorySetupsPlugin.CONFIG_GROUP);
	}
}
