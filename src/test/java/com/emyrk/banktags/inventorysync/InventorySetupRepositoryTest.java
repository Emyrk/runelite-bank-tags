package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.FakeConfigManager;
import com.emyrk.banktags.inventorysync.InventorySetupRepository.Snapshot;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import inventorysetups.InventorySetup;
import inventorysetups.InventorySetupsItem;
import inventorysetups.InventorySetupsPersistentDataManager;
import inventorysetups.InventorySetupsPlugin;
import inventorysetups.InventorySetupsSection;
import inventorysetups.InventorySetupsStackCompareID;
import inventorysetups.serialization.InventorySetupSerializable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class InventorySetupRepositoryTest
{
	private static final String SETUP_ID = "11111111-1111-4111-8111-111111111111";
	private static final String OTHER_ID = "22222222-2222-4222-8222-222222222222";
	private static final String SECTION_ID = "33333333-3333-4333-8333-333333333333";
	private final Gson gson = new Gson();

	@Test
	public void stableIdMigrationSurvivesRenameAndSerialization()
	{
		InventorySetup setup = setup("Old name", "notes", null);
		assertTrue(InventorySetupIds.ensureStableIds(Collections.singletonList(setup), Collections.emptyList()));
		String id = setup.getSyncId();
		assertEquals(4, java.util.UUID.fromString(id).version());
		setup.setName("New name");

		String stored = gson.toJson(InventorySetupSerializable.convertFromInventorySetup(setup));
		InventorySetup loaded = InventorySetupSerializable.convertToInventorySetup(
			gson.fromJson(stored, InventorySetupSerializable.class));
		assertEquals(id, loaded.getSyncId());
		assertEquals("New name", loaded.getName());
		assertFalse(InventorySetupIds.ensureStableIds(Collections.singletonList(loaded), Collections.emptyList()));
	}

	@Test
	public void duplicateOrInvalidIdsAreReplacedWithUniqueUuidV4()
	{
		InventorySetup first = setup("one", "", SETUP_ID);
		InventorySetup second = setup("two", "", SETUP_ID);
		InventorySetupsSection section = new InventorySetupsSection("section");
		section.setSyncId("not-a-uuid");
		assertTrue(InventorySetupIds.ensureStableIds(Arrays.asList(first, second), Collections.singletonList(section)));
		assertNotEquals(first.getSyncId(), second.getSyncId());
		assertNotEquals(first.getSyncId(), section.getSyncId());
		assertEquals(4, java.util.UUID.fromString(second.getSyncId()).version());
		assertEquals(4, java.util.UUID.fromString(section.getSyncId()).version());
	}

	@Test
	public void snapshotSharesNotesFieldsGlobalOrderAndMultiSectionMembership()
	{
		InventorySetup first = setup("Zulrah", "Bring antivenom", SETUP_ID);
		InventorySetup second = setup("Barrows", "", OTHER_ID);
		InventorySetupsSection bossing = section("Bossing", SECTION_ID, true, "Barrows", "Zulrah");
		InventorySetupsSection favorites = section("Favorites", "44444444-4444-4444-8444-444444444444", false, "Zulrah");
		InventorySetupsPlugin plugin = plugin(Arrays.asList(first, second), Arrays.asList(bossing, favorites));

		Snapshot snapshot = new InventorySetupRepository(mock(ConfigManager.class), gson).snapshot(plugin);
		assertEquals(Arrays.asList(SETUP_ID, OTHER_ID), setupIds(snapshot.getSetups()));
		assertEquals("Bring antivenom", snapshot.getSetups().get(0).getNotes());
		assertFalse(snapshot.getSetups().get(0).getPayload().has("name"));
		assertFalse(snapshot.getSetups().get(0).getPayload().has("notes"));
		assertFalse(snapshot.getSetups().get(0).getPayload().has("sid"));
		assertEquals(Arrays.asList(OTHER_ID, SETUP_ID), snapshot.getSections().get(0).getOrderedSetupIds());
		assertEquals(Collections.singletonList(SETUP_ID), snapshot.getSections().get(1).getOrderedSetupIds());
	}

	@Test
	public void applyIsAtomicFromPluginViewPreservesMaximizedAndDetachesDeletedSetups()
	{
		Map<String, String> values = FakeConfigManager.newValues();
		ConfigManager config = FakeConfigManager.create(values);
		InventorySetupsSection existing = section("Old", SECTION_ID, true, "Zulrah");
		InventorySetupsPlugin plugin = plugin(new ArrayList<>(), Collections.singletonList(existing));
		InventorySetupRepository repository = new InventorySetupRepository(config, gson);
		JsonObject payload = gson.toJsonTree(InventorySetupSerializable.convertFromInventorySetup(
			setup("Zulrah", "remote", SETUP_ID))).getAsJsonObject();
		payload.remove("name"); payload.remove("notes"); payload.remove("sid");
		SharedInventorySetup remote = new SharedInventorySetup(SETUP_ID, "Zulrah", "remote", payload, 2, false);
		SharedInventorySetupSection section = new SharedInventorySetupSection(SECTION_ID, "Bossing", null,
			Arrays.asList(SETUP_ID, OTHER_ID), 3, false);

		repository.apply(plugin, Collections.singletonList(remote), Collections.singletonList(section));
		verify(plugin).reloadInventorySetupSyncState();
		String sectionsJson = values.get(FakeConfigManager.key(InventorySetupsPlugin.CONFIG_GROUP,
			InventorySetupsPersistentDataManager.CONFIG_KEY_SECTIONS));
		JsonObject storedSection = gson.fromJson(sectionsJson, JsonArray.class).get(0).getAsJsonObject();
		assertTrue(storedSection.get("isMaximized").getAsBoolean());
		assertEquals(Collections.singletonList("Zulrah"), gson.fromJson(storedSection.get("setups"), List.class));
	}

	private static InventorySetup setup(String name, String notes, String id)
	{
		List<InventorySetupsItem> inventory = Collections.singletonList(
			new InventorySetupsItem(12913, "Toxic blowpipe", 1, false, InventorySetupsStackCompareID.None));
		InventorySetup setup = new InventorySetup(inventory, new ArrayList<>(), null, null, null,
			new HashMap<>(), name, notes, Color.GREEN, true, null, true, false, 1, true, 12913, "Accurate");
		setup.setSyncId(id);
		return setup;
	}

	private static InventorySetupsSection section(String name, String id, boolean maximized, String... setups)
	{
		InventorySetupsSection section = new InventorySetupsSection(name);
		section.setSyncId(id);
		section.setMaximized(maximized);
		section.setSetups(new ArrayList<>(Arrays.asList(setups)));
		return section;
	}

	private static InventorySetupsPlugin plugin(List<InventorySetup> setups, List<InventorySetupsSection> sections)
	{
		InventorySetupsPlugin plugin = mock(InventorySetupsPlugin.class);
		when(plugin.getInventorySetups()).thenReturn(setups);
		when(plugin.getSections()).thenReturn(sections);
		return plugin;
	}

	private static List<String> setupIds(List<SharedInventorySetup> setups)
	{
		List<String> ids = new ArrayList<>();
		for (SharedInventorySetup setup : setups) ids.add(setup.getSetupId());
		return ids;
	}
}
