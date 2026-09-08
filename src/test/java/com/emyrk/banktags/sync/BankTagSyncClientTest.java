package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsSyncConfig;
import com.emyrk.banktags.sync.model.BankTagManifest;
import com.emyrk.banktags.sync.model.ManifestResult;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BankTagSyncClientTest
{
	private static final String TAG_ID = "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721";
	private static final String OTHER_TAG_ID = "9c1b6f2e-2b1d-4d4e-8a5b-0f6a7c9e1d23";
	private static final SharedBankTag TAG = new SharedBankTag(TAG_ID, "herblore", 952,
		Arrays.asList(199, 201, -203), new int[]{199, 201, -1, 203}, 7, false);

	private final Gson gson = new Gson();
	private MockWebServer server;
	private BankTagsSyncConfig config;
	private BankTagSyncClient client;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		config = mock(BankTagsSyncConfig.class);
		when(config.serverBaseUrl()).thenReturn(server.url("/").toString());
		when(config.groupName()).thenReturn("gim");
		when(config.groupToken()).thenReturn("tok-123");
		client = new BankTagSyncClient(new OkHttpClient(), config, new BankTagSyncJson(new Gson()));
	}

	@After
	public void tearDown() throws IOException
	{
		client.cancelAll();
		server.shutdown();
	}

	@Test
	public void manifestRequestShape() throws Exception
	{
		server.enqueue(jsonResponse(200, fixture("manifest.json")));
		server.enqueue(jsonResponse(200, fixture("manifest.json")));

		Await<ManifestResult> withRevision = new Await<>();
		client.getManifest(42L, withRevision);
		withRevision.awaitSuccess();
		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/group/gim/bank-tags", request.getPath());
		assertEquals("tok-123", request.getHeader("Authorization"));
		assertFalse(request.getHeader("Authorization").startsWith("Bearer"));
		assertEquals("application/json", request.getHeader("Accept"));
		assertEquals("\"42\"", request.getHeader("If-None-Match"));

		Await<ManifestResult> withoutRevision = new Await<>();
		client.getManifest(null, withoutRevision);
		withoutRevision.awaitSuccess();
		RecordedRequest unconditional = server.takeRequest();
		assertNull(unconditional.getHeader("If-None-Match"));
	}

	@Test
	public void manifest304IsNotModified() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(304));

		Await<ManifestResult> await = new Await<>();
		client.getManifest(42L, await);
		ManifestResult result = await.awaitSuccess();
		assertTrue(result.isNotModified());
		assertNull(result.getManifest());
	}

	@Test
	public void manifestParsesFixture() throws Exception
	{
		String body = fixture("manifest.json").replace(
			"\"name\": \"slayer\", \"revision\": 1, \"deleted\": false",
			"\"name\": \"slayer\", \"revision\": 2, \"deleted\": true");
		assertTrue("fixture edit applied", body.contains("\"deleted\": true"));
		server.enqueue(jsonResponse(200, body));

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		ManifestResult result = await.awaitSuccess();
		assertFalse(result.isNotModified());
		BankTagManifest manifest = result.getManifest();
		assertNotNull(manifest);
		assertEquals(42, manifest.getGroupRevision());
		assertEquals(3, manifest.getOrderRevision());
		assertEquals(Arrays.asList(TAG_ID, OTHER_TAG_ID), manifest.getOrderedTagIds());
		assertEquals(2, manifest.getTags().size());
		assertEquals("herblore", manifest.getTags().get(0).getName());
		assertEquals(7, manifest.getTags().get(0).getRevision());
		assertFalse(manifest.getTags().get(0).isDeleted());
		assertTrue(manifest.getTags().get(1).isDeleted());
	}

	@Test
	public void createTagUsesIfNoneMatchStarAndBodyShape() throws Exception
	{
		server.enqueue(jsonResponse(201, fixture("tag.json")));

		Await<SharedBankTag> await = new Await<>();
		client.createTag(TAG_ID, TAG, await);
		SharedBankTag created = await.awaitSuccess();
		assertEquals(7, created.getRevision());

		RecordedRequest request = server.takeRequest();
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/group/gim/bank-tags/" + TAG_ID, request.getPath());
		assertEquals("*", request.getHeader("If-None-Match"));
		assertNull(request.getHeader("If-Match"));
		assertTrue(request.getHeader("Content-Type").startsWith("application/json"));

		JsonObject body = gson.fromJson(request.getBody().readUtf8(), JsonObject.class);
		assertEquals(Arrays.asList("schemaVersion", "name", "iconItemId", "itemIds", "layout"),
			new ArrayList<>(body.keySet()));
		assertEquals(1, body.get("schemaVersion").getAsInt());
		assertEquals("herblore", body.get("name").getAsString());
		assertEquals(952, body.get("iconItemId").getAsInt());
		assertEquals("[-203,199,201]", body.get("itemIds").toString());
		assertEquals("[199,201,-1,203]", body.get("layout").toString());
	}

	@Test
	public void updateTagUsesIfMatchQuotedRevision() throws Exception
	{
		server.enqueue(jsonResponse(200, fixture("tag.json")));

		Await<SharedBankTag> await = new Await<>();
		client.updateTag(TAG_ID, 7, TAG, await);
		await.awaitSuccess();

		RecordedRequest request = server.takeRequest();
		assertEquals("PUT", request.getMethod());
		assertEquals("\"7\"", request.getHeader("If-Match"));
		assertNull(request.getHeader("If-None-Match"));
	}

	@Test
	public void deleteTagSendsIfMatchAndNoBody() throws Exception
	{
		server.enqueue(jsonResponse(200, fixture("tag-tombstone.json")));

		Await<SharedBankTag> await = new Await<>();
		client.deleteTag(TAG_ID, 7, await);
		SharedBankTag tombstone = await.awaitSuccess();
		assertTrue(tombstone.isDeleted());
		assertEquals(8, tombstone.getRevision());
		assertNull(tombstone.getLayout());

		RecordedRequest request = server.takeRequest();
		assertEquals("DELETE", request.getMethod());
		assertEquals("/api/group/gim/bank-tags/" + TAG_ID, request.getPath());
		assertEquals("\"7\"", request.getHeader("If-Match"));
		assertEquals(0, request.getBodySize());
	}

	@Test
	public void putOrderPathAndBody() throws Exception
	{
		server.enqueue(jsonResponse(200, fixture("manifest.json")));

		Await<BankTagManifest> await = new Await<>();
		client.putOrder(3, Arrays.asList(OTHER_TAG_ID, TAG_ID), await);
		BankTagManifest manifest = await.awaitSuccess();
		assertEquals(42, manifest.getGroupRevision());

		RecordedRequest request = server.takeRequest();
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/group/gim/bank-tag-order", request.getPath());
		assertEquals("\"3\"", request.getHeader("If-Match"));
		JsonObject body = gson.fromJson(request.getBody().readUtf8(), JsonObject.class);
		assertEquals(gson.fromJson(fixture("order-request.json"), JsonObject.class), body);
		assertEquals("{\"schemaVersion\":1,\"orderedTagIds\":[\"" + OTHER_TAG_ID + "\",\"" + TAG_ID + "\"]}",
			body.toString());
	}

	@Test
	public void conflictParsesCurrentTag() throws Exception
	{
		server.enqueue(jsonResponse(409, fixture("error-stale-revision.json")));

		Await<SharedBankTag> await = new Await<>();
		client.updateTag(TAG_ID, 7, TAG, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.CONFLICT, failure.getKind());
		assertEquals(409, failure.getHttpStatus());
		assertEquals("stale_revision", failure.getErrorCode());
		assertEquals("tag revision 7 is stale; current is 8", failure.getMessage());
		assertNotNull(failure.getCurrentTag());
		assertEquals(TAG_ID, failure.getCurrentTag().getTagId());
		assertEquals(8, failure.getCurrentTag().getRevision());
		assertNull(failure.getCurrentManifest());
	}

	@Test
	public void conflictOnOrderParsesCurrentManifest() throws Exception
	{
		String body = "{\"error\":\"stale_revision\",\"message\":\"order revision 2 is stale; current is 3\","
			+ "\"current\":" + fixture("manifest.json") + "}";
		server.enqueue(jsonResponse(409, body));

		Await<BankTagManifest> await = new Await<>();
		client.putOrder(2, Arrays.asList(OTHER_TAG_ID, TAG_ID), await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.CONFLICT, failure.getKind());
		assertEquals("stale_revision", failure.getErrorCode());
		assertNull(failure.getCurrentTag());
		assertNotNull(failure.getCurrentManifest());
		assertEquals(42, failure.getCurrentManifest().getGroupRevision());
		assertEquals(3, failure.getCurrentManifest().getOrderRevision());
		assertEquals(2, failure.getCurrentManifest().getTags().size());
	}

	@Test
	public void unauthorizedMapsTo401Kind() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.UNAUTHORIZED, failure.getKind());
		assertEquals(401, failure.getHttpStatus());
	}

	@Test
	public void notFoundMapsToKind() throws Exception
	{
		server.enqueue(jsonResponse(404, "{\"error\":\"tag_not_found\",\"message\":\"no such tag\"}"));

		Await<SharedBankTag> await = new Await<>();
		client.getTag(TAG_ID, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.NOT_FOUND, failure.getKind());
		assertEquals(404, failure.getHttpStatus());
		assertEquals("tag_not_found", failure.getErrorCode());
	}

	@Test
	public void preconditionRequiredWithPlainTextBody() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(428)
			.setHeader("Content-Type", "text/plain")
			.setBody("precondition required"));

		Await<SharedBankTag> await = new Await<>();
		client.updateTag(TAG_ID, 7, TAG, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.PRECONDITION_REQUIRED, failure.getKind());
		assertEquals(428, failure.getHttpStatus());
		assertNull(failure.getErrorCode());
	}

	@Test
	public void payloadTooLarge413() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(413).setBody("payload too large"));

		Await<SharedBankTag> await = new Await<>();
		client.createTag(TAG_ID, TAG, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.PAYLOAD_TOO_LARGE, failure.getKind());
		assertEquals(413, failure.getHttpStatus());
		assertNull(failure.getErrorCode());
	}

	@Test
	public void serverError500() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500).setBody("<html>oops</html>"));

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.SERVER_ERROR, failure.getKind());
		assertEquals(500, failure.getHttpStatus());
	}

	@Test
	public void unsupportedSchemaIsInvalidResponse() throws Exception
	{
		server.enqueue(jsonResponse(200, fixture("tag.json").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")));

		Await<SharedBankTag> await = new Await<>();
		client.getTag(TAG_ID, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.INVALID_RESPONSE, failure.getKind());
		assertEquals(0, failure.getHttpStatus());
	}

	@Test
	public void malformedSuccessBodyIsInvalidResponse() throws Exception
	{
		server.enqueue(jsonResponse(200, "{\"schemaVersion\":1,\"tagId\":\"" + TAG_ID + "\"}"));

		Await<SharedBankTag> await = new Await<>();
		client.getTag(TAG_ID, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.INVALID_RESPONSE, failure.getKind());
	}

	@Test
	public void networkFailureIsNetworkKind() throws Exception
	{
		server.shutdown();

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.NETWORK, failure.getKind());
		assertEquals(0, failure.getHttpStatus());
	}

	@Test
	public void blankBaseUrlDefaultsToGroupIronMen()
	{
		when(config.serverBaseUrl()).thenReturn("");
		HttpUrl url = client.buildUrl("bank-tags");
		assertEquals("groupiron.men", url.host());
		assertEquals("https", url.scheme());
		assertEquals("/api/group/gim/bank-tags", url.encodedPath());

		when(config.serverBaseUrl()).thenReturn("   ");
		assertEquals("groupiron.men", client.buildUrl("bank-tag-order").host());
	}

	@Test
	public void invalidBaseUrlFailsWithoutRequest() throws Exception
	{
		when(config.serverBaseUrl()).thenReturn("not a url");

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		SyncFailure failure = await.awaitFailure();
		assertEquals(SyncFailure.Kind.BAD_REQUEST, failure.getKind());
		assertEquals(0, failure.getHttpStatus());
		assertEquals("invalid_server_url", failure.getErrorCode());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void cancelAllCancelsInFlight() throws Exception
	{
		// The delay must exceed the 1 s cancellation deadline below but stay well under MockWebServer's
		// 5 s shutdown grace: the serving thread sleeps for the full delay even after the client cancels,
		// and a longer delay makes tearDown's shutdown() fail on slow CI runners.
		server.enqueue(jsonResponse(200, fixture("manifest.json")).setBodyDelay(2, TimeUnit.SECONDS));

		Await<ManifestResult> await = new Await<>();
		client.getManifest(null, await);
		server.takeRequest(2, TimeUnit.SECONDS);
		client.cancelAll();
		SyncFailure failure = await.awaitFailure(1, TimeUnit.SECONDS);
		assertEquals(SyncFailure.Kind.NETWORK, failure.getKind());
	}

	private static MockResponse jsonResponse(int status, String body)
	{
		return new MockResponse().setResponseCode(status)
			.setHeader("Content-Type", "application/json")
			.setBody(body);
	}

	private static String fixture(String name) throws IOException
	{
		try (InputStream in = BankTagSyncClientTest.class.getResourceAsStream("/fixtures/sync/v1/" + name))
		{
			assertNotNull("missing fixture " + name, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Callback that records exactly one outcome and lets the test thread wait for it.
	 */
	private static final class Await<T> implements BankTagSyncClient.Callback<T>
	{
		private final CountDownLatch latch = new CountDownLatch(1);
		private final List<T> successes = new ArrayList<>();
		private final List<SyncFailure> failures = new ArrayList<>();

		@Override
		public synchronized void onSuccess(T value)
		{
			successes.add(value);
			latch.countDown();
		}

		@Override
		public synchronized void onFailure(SyncFailure failure)
		{
			failures.add(failure);
			latch.countDown();
		}

		T awaitSuccess() throws InterruptedException
		{
			await(2, TimeUnit.SECONDS);
			synchronized (this)
			{
				assertTrue("expected success but got " + failures, failures.isEmpty());
				assertEquals(1, successes.size());
				return successes.get(0);
			}
		}

		SyncFailure awaitFailure() throws InterruptedException
		{
			return awaitFailure(2, TimeUnit.SECONDS);
		}

		SyncFailure awaitFailure(long timeout, TimeUnit unit) throws InterruptedException
		{
			await(timeout, unit);
			synchronized (this)
			{
				assertTrue("expected failure but got " + successes, successes.isEmpty());
				assertEquals(1, failures.size());
				return failures.get(0);
			}
		}

		private void await(long timeout, TimeUnit unit) throws InterruptedException
		{
			if (!latch.await(timeout, unit))
			{
				fail("callback not invoked within " + timeout + " " + unit);
			}
		}
	}
}
