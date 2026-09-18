package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.emyrk.banktags.sync.model.SyncFailure;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Collections;
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

public class CombatAchievementSyncClientTest
{
	private MockWebServer server;
	private CombatAchievementSyncClient client;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		BankTagsConfig config = mock(BankTagsConfig.class);
		when(config.serverBaseUrl()).thenReturn(server.url("/").toString());
		when(config.groupName()).thenReturn("gim group");
		when(config.groupToken()).thenReturn("token");
		Gson gson = new Gson();
		client = new CombatAchievementSyncClient(new OkHttpClient(), config,
			new CombatAchievementSyncJson(gson));
	}

	@After
	public void tearDown() throws IOException
	{
		client.cancelAll();
		server.shutdown();
	}

	@Test
	public void putUsesProgressRouteTokenAndExactBody() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(204));
		Await callback = new Await();
		client.putProgress(new CombatAchievementProgress("Display Name", 123, 456,
			Collections.singletonList("CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED")), callback);
		callback.success();

		RecordedRequest request = server.takeRequest();
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/group/gim%20group/combat-achievements/snapshot", request.getPath());
		assertEquals("token", request.getHeader("Authorization"));
		JsonObject body = new Gson().fromJson(request.getBody().readUtf8(), JsonObject.class);
		assertEquals(1, body.get("schemaVersion").getAsInt());
		assertEquals("Display Name", body.get("playerName").getAsString());
		assertEquals(123, body.get("clientRevision").getAsInt());
		assertEquals(456, body.get("achievementPoints").getAsInt());
		assertEquals("CA_TASK_ABBERANT_SPECTRE_KILLCOUNT_1_COMPLETED",
			body.getAsJsonArray("completedTaskIds").get(0).getAsString());
	}

	private static final class Await implements CombatAchievementSyncClient.Callback
	{
		private final CountDownLatch latch = new CountDownLatch(1);
		private SyncFailure failure;
		@Override public void onSuccess() { latch.countDown(); }
		@Override public void onFailure(SyncFailure failure) { this.failure = failure; latch.countDown(); }
		void success() throws InterruptedException
		{
			assertTrue(latch.await(5, TimeUnit.SECONDS));
			assertNull(failure);
		}
	}
}
