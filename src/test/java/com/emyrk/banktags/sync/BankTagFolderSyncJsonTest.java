package com.emyrk.banktags.sync;

import com.emyrk.banktags.sync.model.BankTagFolderManifest;
import com.emyrk.banktags.sync.model.SharedBankTagFolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankTagFolderSyncJsonTest
{
	private final Gson gson = new Gson();
	private final BankTagFolderSyncJson json = new BankTagFolderSyncJson(gson);

	@Test
	public void parsesFolderManifestAndTombstone() throws Exception
	{
		SharedBankTagFolder folder = json.parseFolder(fixture("folder.json"));
		assertEquals("Gathering", folder.getName());
		assertEquals(3, folder.getRevision());
		assertEquals(2, folder.getOrderedTagIds().size());

		SharedBankTagFolder tombstone = json.parseFolder(fixture("folder-tombstone.json"));
		assertTrue(tombstone.isDeleted());
		assertTrue(tombstone.getOrderedTagIds().isEmpty());

		BankTagFolderManifest manifest = json.parseManifest(fixture("manifest.json"));
		assertEquals(9, manifest.getGroupRevision());
		assertEquals(2, manifest.getOrderRevision());
		assertEquals(1, manifest.getFolders().size());
	}

	@Test
	public void requestBodiesContainOnlyClientOwnedFields() throws Exception
	{
		SharedBankTagFolder folder = json.parseFolder(fixture("folder.json"));
		JsonObject body = gson.fromJson(json.folderRequestBody(folder), JsonObject.class);
		assertEquals(Arrays.asList("schemaVersion", "name", "iconItemId", "orderedTagIds"), new java.util.ArrayList<>(body.keySet()));
		assertFalse(body.has("revision"));
		assertFalse(body.has("deleted"));

		JsonObject order = gson.fromJson(json.folderOrderRequestBody(
			java.util.Collections.singletonList(folder.getFolderId())), JsonObject.class);
		assertEquals(gson.fromJson(fixture("order-request.json"), JsonObject.class), order);
	}

	@Test(expected = BankTagSyncJson.UnsupportedSchemaException.class)
	public void rejectsUnsupportedSchema() throws Exception
	{
		json.parseFolder(fixture("folder.json").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
	}

	private static String fixture(String name) throws IOException
	{
		try (InputStream in = BankTagFolderSyncJsonTest.class.getResourceAsStream("/fixtures/sync/folders/v1/" + name))
		{
			if (in == null) throw new IOException("missing fixture " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
