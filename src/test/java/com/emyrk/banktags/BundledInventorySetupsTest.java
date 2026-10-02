package com.emyrk.banktags;

import com.emyrk.banktags.inventorysync.InventorySetupSyncCoordinator;
import com.emyrk.banktags.tabs.LayoutManager;
import com.google.inject.Guice;
import com.google.inject.Injector;
import inventorysetups.InventorySetupsPlugin;
import java.lang.reflect.Field;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

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
	public void bankTagsExtendedExposesDependenciesToCompanionPlugins() throws Exception
	{
		BankTagsPlugin plugin = new BankTagsPlugin();
		InventorySetupSyncCoordinator syncCoordinator = mock(InventorySetupSyncCoordinator.class);
		LayoutManager layoutManager = mock(LayoutManager.class);
		TagManager tagManager = mock(TagManager.class);
		setField(plugin, "inventorySetupSyncCoordinator", syncCoordinator);
		setField(plugin, "layoutManager", layoutManager);
		setField(plugin, "tagManager", tagManager);

		Injector injector = Guice.createInjector(plugin.getPublicModule());

		assertSame(plugin, injector.getInstance(BankTagsPlugin.class));
		assertSame(plugin, injector.getInstance(BankTagsService.class));
		assertSame(syncCoordinator, injector.getInstance(InventorySetupSyncCoordinator.class));
		assertSame(layoutManager, injector.getInstance(LayoutManager.class));
		assertSame(tagManager, injector.getInstance(TagManager.class));
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	public void companionRetainsExistingInventorySetupConfiguration()
	{
		assertEquals("inventorysetups", InventorySetupsPlugin.CONFIG_GROUP);
	}
}
