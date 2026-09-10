package com.emyrk.banktags.sync;

import com.emyrk.banktags.sync.model.SharedBankTag;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the v1 sync protocol fixtures described in docs/remote-sync-protocol.md.
 * The fixtures are the source of truth shared with the server and site repositories.
 */
public class ProtocolFixturesTest
{
	private static final Path FIXTURE_DIR = Paths.get("src", "test", "resources", "fixtures", "sync", "v1");
	private static final int MINIMUM_FIXTURE_COUNT = 9;

	/**
	 * SharedBankTag.contentHash() of tag.json. Changing the hash encoding breaks stored baseHash values,
	 * so this constant must only change together with a migration of syncTag_ metadata.
	 */
	private static final String TAG_JSON_CONTENT_HASH =
		"58149b3fb9c90de739c0e6bf3cef8da69bc593e72bed0b6085713173c1ba83b4";

	private final Gson gson = new Gson();
	private final TreeMap<String, JsonElement> fixtures = new TreeMap<>();

	@Before
	public void loadFixtures() throws IOException
	{
		assertTrue("fixture directory missing: " + FIXTURE_DIR.toAbsolutePath(), Files.isDirectory(FIXTURE_DIR));
		try (DirectoryStream<Path> files = Files.newDirectoryStream(FIXTURE_DIR, "*.json"))
		{
			for (Path file : files)
			{
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
				{
					JsonElement parsed = gson.fromJson(reader, JsonElement.class);
					assertTrue("fixture is not a JSON document: " + file, parsed != null && !parsed.isJsonNull());
					fixtures.put(file.getFileName().toString(), parsed);
				}
			}
		}
		assertTrue("expected at least " + MINIMUM_FIXTURE_COUNT + " fixtures, found " + fixtures.keySet(),
			fixtures.size() >= MINIMUM_FIXTURE_COUNT);
	}

	@Test
	public void testTagFixtureMatchesProtocolExample()
	{
		JsonObject tag = object("tag.json");
		assertEquals(1, tag.get("schemaVersion").getAsInt());
		assertEquals("5e4a8e36-e5f4-4daa-ae7a-e510f3e66721", tag.get("tagId").getAsString());
		assertEquals("herblore", tag.get("name").getAsString());
		assertEquals(952, tag.get("iconItemId").getAsInt());
		assertEquals(7, tag.get("revision").getAsLong());
		assertFalse(tag.get("deleted").getAsBoolean());
		assertArrayEquals(new int[]{199, 201, -203}, ints(tag.getAsJsonArray("itemIds")));
		assertArrayEquals(new int[]{199, 201, -1, 203}, ints(tag.getAsJsonArray("layout")));
	}

	@Test
	public void testTombstoneAndNoLayoutFixtures()
	{
		JsonObject tombstone = object("tag-tombstone.json");
		assertEquals(object("tag.json").get("tagId").getAsString(), tombstone.get("tagId").getAsString());
		assertTrue(tombstone.get("deleted").getAsBoolean());
		assertEquals(0, tombstone.getAsJsonArray("itemIds").size());
		assertTrue(tombstone.get("layout").isJsonNull());
		assertEquals(8, tombstone.get("revision").getAsLong());

		JsonObject noLayout = object("tag-no-layout.json");
		assertEquals("9c1b6f2e-2b1d-4d4e-8a5b-0f6a7c9e1d23", noLayout.get("tagId").getAsString());
		assertEquals("slayer", noLayout.get("name").getAsString());
		assertTrue(noLayout.get("layout").isJsonNull());
		assertArrayEquals(new int[]{4155, 8901, -5698}, ints(noLayout.getAsJsonArray("itemIds")));
	}

	@Test
	public void testManifestOrderListsExactlyTheLiveTags()
	{
		JsonObject manifest = object("manifest.json");
		assertEquals(1, manifest.get("schemaVersion").getAsInt());
		assertEquals(42, manifest.get("groupRevision").getAsLong());
		assertEquals(3, manifest.get("orderRevision").getAsLong());

		Set<String> liveIds = new HashSet<>();
		for (JsonElement entry : manifest.getAsJsonArray("tags"))
		{
			JsonObject tag = entry.getAsJsonObject();
			if (!tag.get("deleted").getAsBoolean())
			{
				assertTrue("duplicate tag id in manifest", liveIds.add(tag.get("tagId").getAsString()));
			}
		}

		List<String> ordered = new ArrayList<>();
		for (JsonElement id : manifest.getAsJsonArray("orderedTagIds"))
		{
			ordered.add(id.getAsString());
		}
		assertEquals("orderedTagIds must contain each live id once", liveIds.size(), ordered.size());
		assertEquals(liveIds, new HashSet<>(ordered));
		assertEquals("herblore is first", "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721", ordered.get(0));

		JsonObject order = object("order-request.json");
		assertEquals(1, order.get("schemaVersion").getAsInt());
		List<String> requested = new ArrayList<>();
		for (JsonElement id : order.getAsJsonArray("orderedTagIds"))
		{
			requested.add(id.getAsString());
		}
		assertEquals("order request must be a permutation of the live ids", liveIds, new HashSet<>(requested));
		assertEquals(liveIds.size(), requested.size());
	}

	@Test
	public void testEveryErrorFixtureUsesAKnownErrorCode()
	{
		Set<String> codes = new HashSet<>();
		for (JsonElement code : fixtures.get("error-codes.json").getAsJsonArray())
		{
			codes.add(code.getAsString());
		}
		assertEquals(10, codes.size());

		int checked = 0;
		for (String file : fixtures.keySet())
		{
			if (!file.startsWith("error-") || file.equals("error-codes.json"))
			{
				continue;
			}
			JsonObject error = fixtures.get(file).getAsJsonObject();
			String code = error.get("error").getAsString();
			assertTrue(file + " uses unknown error code " + code, codes.contains(code));
			assertTrue(file + " must have a message", error.get("message").getAsString().length() > 0);
			checked++;
		}
		assertTrue("expected at least one error-*.json fixture", checked > 0);

		JsonObject stale = object("error-stale-revision.json").getAsJsonObject("current");
		assertEquals(8, stale.get("revision").getAsLong());
		assertEquals("herblore", stale.get("name").getAsString());
	}

	@Test
	public void testTagFixtureContentHashIsPinned()
	{
		JsonObject json = object("tag.json");
		List<Integer> itemIds = new ArrayList<>();
		for (int itemId : ints(json.getAsJsonArray("itemIds")))
		{
			itemIds.add(itemId);
		}
		SharedBankTag tag = new SharedBankTag(
			json.get("tagId").getAsString(),
			json.get("name").getAsString(),
			json.get("iconItemId").getAsInt(),
			itemIds,
			ints(json.getAsJsonArray("layout")),
			json.get("revision").getAsLong(),
			json.get("deleted").getAsBoolean());

		assertEquals(TAG_JSON_CONTENT_HASH, tag.contentHash());
	}

	@Test
	public void testFolderExtensionFixturesMatchProtocol()
	{
		Path folderDir = Paths.get("src", "test", "resources", "fixtures", "sync", "folders", "v1");
		JsonObject folder = readObject(folderDir.resolve("folder.json"));
		assertEquals(1, folder.get("schemaVersion").getAsInt());
		assertEquals(952, folder.get("iconItemId").getAsInt());
		assertEquals(2, folder.getAsJsonArray("orderedTagIds").size());
		assertFalse(folder.get("deleted").getAsBoolean());

		JsonObject manifest = readObject(folderDir.resolve("manifest.json"));
		assertEquals(9, manifest.get("groupRevision").getAsLong());
		assertEquals(2, manifest.get("orderRevision").getAsLong());
		assertEquals(1, manifest.getAsJsonArray("orderedFolderIds").size());
		assertEquals(1, manifest.getAsJsonArray("folders").size());

		JsonObject tombstone = readObject(folderDir.resolve("folder-tombstone.json"));
		assertTrue(tombstone.get("deleted").getAsBoolean());
		assertEquals(0, tombstone.getAsJsonArray("orderedTagIds").size());

		JsonObject order = readObject(folderDir.resolve("order-request.json"));
		assertEquals(manifest.getAsJsonArray("orderedFolderIds"), order.getAsJsonArray("orderedFolderIds"));
	}

	private JsonObject readObject(Path file)
	{
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			JsonElement element = gson.fromJson(reader, JsonElement.class);
			assertTrue("fixture is not a JSON object: " + file, element != null && element.isJsonObject());
			return element.getAsJsonObject();
		}
		catch (IOException ex)
		{
			throw new AssertionError("unable to read fixture " + file, ex);
		}
	}

	private JsonObject object(String file)
	{
		JsonElement element = fixtures.get(file);
		assertTrue("missing fixture " + file, element != null);
		return element.getAsJsonObject();
	}

	private static int[] ints(JsonArray array)
	{
		int[] values = new int[array.size()];
		for (int i = 0; i < values.length; i++)
		{
			values[i] = array.get(i).getAsInt();
		}
		return values;
	}
}
