package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.emyrk.banktags.inventorysync.model.InventorySetupSyncFailure;
import com.emyrk.banktags.inventorysync.model.ManifestResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InventorySetupSyncClientTest
{
	private static final String SETUP_ID = "11111111-1111-4111-8111-111111111111";
	private static final String SECTION_ID = "33333333-3333-4333-8333-333333333333";
	private final Gson gson = new Gson();
	private MockWebServer server;
	private InventorySetupSyncClient client;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		BankTagsConfig config = mock(BankTagsConfig.class);
		when(config.serverBaseUrl()).thenReturn(server.url("/").toString());
		when(config.groupName()).thenReturn("gim");
		when(config.groupToken()).thenReturn("token");
		client = new InventorySetupSyncClient(new OkHttpClient(), config, new InventorySetupSyncJson(gson));
	}

	@After
	public void tearDown() throws IOException
	{
		client.cancelAll();
		server.shutdown();
	}

	@Test
	public void setupManifestCrudAndOrderUseSpecifiedRoutesAndHeaders() throws Exception
	{
		server.enqueue(json(200, setupManifest()));
		Await<ManifestResponse> manifest = new Await<>();
		client.getManifest(8L, manifest);
		manifest.success();
		RecordedRequest request = server.takeRequest();
		assertEquals("/api/group/gim/inventory-setups", request.getPath());
		assertEquals("\"8\"", request.getHeader("If-None-Match"));
		assertEquals("token", request.getHeader("Authorization"));

		SharedInventorySetup setup = new SharedInventorySetup(SETUP_ID, "Zulrah", "notes", new JsonObject(), 0, false);
		server.enqueue(json(201, setupDocument(1, false)));
		Await<SharedInventorySetup> create = new Await<>();
		client.createSetup(setup, create); create.success();
		request = server.takeRequest();
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/group/gim/inventory-setups/" + SETUP_ID, request.getPath());
		assertEquals("*", request.getHeader("If-None-Match"));
		JsonObject body = gson.fromJson(request.getBody().readUtf8(), JsonObject.class);
		assertEquals("notes", body.get("notes").getAsString());

		server.enqueue(json(200, setupDocument(2, false)));
		Await<SharedInventorySetup> update = new Await<>();
		client.updateSetup(setup, 1, update); update.success();
		assertEquals("\"1\"", server.takeRequest().getHeader("If-Match"));

		server.enqueue(json(200, setupDocument(3, true)));
		Await<SharedInventorySetup> delete = new Await<>();
		client.deleteSetup(SETUP_ID, 2, delete);
		assertTrue(delete.success().isDeleted());
		request = server.takeRequest();
		assertEquals("DELETE", request.getMethod());
		assertEquals("\"2\"", request.getHeader("If-Match"));

		server.enqueue(json(200, setupManifest()));
		Await<InventorySetupManifest> order = new Await<>();
		client.putSetupOrder(3, Arrays.asList(SETUP_ID), order); order.success();
		request = server.takeRequest();
		assertEquals("/api/group/gim/inventory-setup-order", request.getPath());
		assertEquals("\"3\"", request.getHeader("If-Match"));
	}

	@Test
	public void sectionCrudAndOrderUseSpecifiedRoutes() throws Exception
	{
		SharedInventorySetupSection section = new SharedInventorySetupSection(SECTION_ID, "Bossing", null,
			Arrays.asList(SETUP_ID), 0, false);
		server.enqueue(json(201, sectionDocument(1, false)));
		Await<SharedInventorySetupSection> create = new Await<>();
		client.createSection(section, create); create.success();
		RecordedRequest request = server.takeRequest();
		assertEquals("/api/group/gim/inventory-setup-sections/" + SECTION_ID, request.getPath());
		assertEquals("*", request.getHeader("If-None-Match"));
		assertNull(gson.fromJson(request.getBody().readUtf8(), JsonObject.class).get("displayColor").isJsonNull() ? null : "not-null");

		server.enqueue(json(200, sectionDocument(2, false)));
		Await<SharedInventorySetupSection> update = new Await<>();
		client.updateSection(section, 1, update); update.success();
		assertEquals("\"1\"", server.takeRequest().getHeader("If-Match"));

		server.enqueue(json(200, sectionDocument(3, true)));
		Await<SharedInventorySetupSection> delete = new Await<>();
		client.deleteSection(SECTION_ID, 2, delete); delete.success();
		assertEquals("DELETE", server.takeRequest().getMethod());

		server.enqueue(json(200, sectionManifest()));
		Await<InventorySetupManifest> order = new Await<>();
		client.putSectionOrder(2, Arrays.asList(SECTION_ID), order); order.success();
		assertEquals("/api/group/gim/inventory-setup-section-order", server.takeRequest().getPath());
	}

	private MockResponse json(int status, String body)
	{
		return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
	}

	private String setupDocument(long revision, boolean deleted)
	{
		return "{\"schemaVersion\":1,\"setupId\":\"" + SETUP_ID + "\",\"name\":\"Zulrah\","
			+ "\"notes\":\"notes\",\"payload\":{},\"revision\":" + revision + ",\"deleted\":" + deleted + "}";
	}

	private String setupManifest()
	{
		return "{\"schemaVersion\":1,\"groupRevision\":8,\"setupOrderRevision\":3,\"sectionOrderRevision\":2,"
			+ "\"orderedSetupIds\":[\"" + SETUP_ID + "\"],\"orderedSectionIds\":[\"" + SECTION_ID + "\"],"
			+ "\"setups\":[{\"setupId\":\"" + SETUP_ID + "\",\"name\":\"Zulrah\",\"revision\":1,\"deleted\":false}],"
			+ "\"sections\":[{\"sectionId\":\"" + SECTION_ID + "\",\"name\":\"Bossing\",\"revision\":1,\"deleted\":false}]}";
	}

	private String sectionDocument(long revision, boolean deleted)
	{
		return "{\"schemaVersion\":1,\"sectionId\":\"" + SECTION_ID + "\",\"name\":\"Bossing\","
			+ "\"displayColor\":null,\"orderedSetupIds\":[\"" + SETUP_ID + "\"],\"revision\":" + revision
			+ ",\"deleted\":" + deleted + "}";
	}

	private String sectionManifest()
	{
		return setupManifest();
	}

	private static final class Await<T> implements InventorySetupSyncClient.Callback<T>
	{
		private final CountDownLatch latch = new CountDownLatch(1);
		private T value;
		private InventorySetupSyncFailure failure;
		@Override public void onSuccess(T value) { this.value = value; latch.countDown(); }
		@Override public void onFailure(InventorySetupSyncFailure failure) { this.failure = failure; latch.countDown(); }
		T success() throws Exception
		{
			assertTrue(latch.await(5, TimeUnit.SECONDS));
			if (failure != null) throw new AssertionError(failure.getKind());
			return value;
		}
	}
}
