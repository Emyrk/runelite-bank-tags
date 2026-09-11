package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.inventorysync.InventorySetupSyncJson.CurrentKind;
import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.InventorySetupSyncFailure;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InventorySetupSyncJsonTest
{
	private static final String DIR = "src/test/resources/fixtures/sync/inventory-setups/v1/";
	private final Gson gson = new Gson();
	private final InventorySetupSyncJson json = new InventorySetupSyncJson(gson);

	@Test
	public void setupRoundTripIncludesNotesAndAllPayloadFields() throws Exception
	{
		SharedInventorySetup setup = json.parseSetup(fixture("setup.json"));
		assertEquals("Bring antivenom", setup.getNotes());
		assertEquals(4, setup.getRevision());
		assertTrue(setup.getPayload().has("inv"));
		JsonObject request = gson.fromJson(json.setupRequest(setup), JsonObject.class);
		assertEquals(Arrays.asList("schemaVersion", "name", "notes", "payload"),
			new java.util.ArrayList<>(request.keySet()));
		assertFalse(request.has("setupId"));
		assertFalse(request.has("revision"));
		assertFalse(request.has("deleted"));
	}

	@Test
	public void combinedManifestPreservesBothOrdersAndRevisions() throws Exception
	{
		InventorySetupManifest manifest = json.parseManifest(fixture("manifest.json"));
		assertEquals(42, manifest.getGroupRevision());
		assertEquals(4, manifest.getSetupOrderRevision());
		assertEquals(2, manifest.getSectionOrderRevision());
		assertEquals("5e4a8e36-e5f4-4daa-ae7a-e510f3e66721", manifest.getOrderedSetupIds().get(0));
		assertEquals("a8691e24-56a0-4fb7-bfd1-04feca5b449a", manifest.getOrderedSectionIds().get(0));
		assertEquals(1, manifest.getSetups().size());
		assertEquals(1, manifest.getSections().size());
	}

	@Test
	public void structuredConflictsRetainCurrentMetadataAndManifest()
	{
		String entity = "{\"error\":\"stale_revision\",\"message\":\"stale\",\"current\":{"
			+ "\"setupId\":\"5e4a8e36-e5f4-4daa-ae7a-e510f3e66721\",\"name\":\"Cerberus\","
			+ "\"revision\":8,\"deleted\":false}}";
		InventorySetupSyncFailure entityFailure = json.parseErrorBody(409, entity, CurrentKind.SETUP);
		assertEquals("stale_revision", entityFailure.getErrorCode());
		assertEquals(8, entityFailure.getCurrentSetup().getRevision());

		String order = "{\"error\":\"stale_revision\",\"message\":\"stale\",\"current\":"
			+ fixtureUnchecked("manifest.json") + "}";
		InventorySetupSyncFailure orderFailure = json.parseErrorBody(409, order, CurrentKind.MANIFEST);
		assertNotNull(orderFailure.getCurrentManifest());
		assertEquals(42, orderFailure.getCurrentManifest().getGroupRevision());
	}

	@Test(expected = com.emyrk.banktags.sync.BankTagSyncJson.InvalidDocumentException.class)
	public void manifestRejectsOrderThatIsNotLivePermutation() throws Exception
	{
		json.parseManifest(fixture("manifest.json").replace(
			"\"orderedSetupIds\": [\"5e4a8e36-e5f4-4daa-ae7a-e510f3e66721\"]",
			"\"orderedSetupIds\": []"));
	}

	@Test
	public void sectionSupportsOrderedMultiMembershipAndExcludesMaximized() throws Exception
	{
		SharedInventorySetupSection section = json.parseSection(fixture("section.json"));
		assertEquals(Arrays.asList("22222222-2222-4222-8222-222222222222",
			"11111111-1111-4111-8111-111111111111"), section.getOrderedSetupIds());
		JsonObject request = gson.fromJson(json.sectionRequest(section), JsonObject.class);
		assertFalse(request.has("isMaximized"));
		assertEquals(4, request.size());
	}

	@Test
	public void tombstonesParse() throws Exception
	{
		assertTrue(json.parseSetup(fixture("setup-tombstone.json")).isDeleted());
		SharedInventorySetupSection section = json.parseSection(fixture("section-tombstone.json"));
		assertTrue(section.isDeleted());
		assertNull(section.getDisplayColor());
	}

	@Test
	public void canonicalSetupHashIgnoresObjectPropertyOrder()
	{
		JsonObject one = gson.fromJson("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}", JsonObject.class);
		JsonObject two = gson.fromJson("{\"a\":{\"c\":3,\"d\":2},\"b\":1}", JsonObject.class);
		assertEquals(new SharedInventorySetup("11111111-1111-4111-8111-111111111111", "n", "", one, 1, false).contentHash(),
			new SharedInventorySetup("11111111-1111-4111-8111-111111111111", "n", "", two, 1, false).contentHash());
	}

	private static String fixture(String name) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(DIR + name)), StandardCharsets.UTF_8);
	}

	private static String fixtureUnchecked(String name)
	{
		try
		{
			return fixture(name);
		}
		catch (Exception ex)
		{
			throw new AssertionError(ex);
		}
	}
}
