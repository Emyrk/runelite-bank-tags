package com.emyrk.banktags.combatachievementsync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.combatachievementsync.model.CombatAchievementProgress;
import com.emyrk.banktags.sync.model.SyncFailure;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Asynchronous client for exact Combat Achievement progress uploads. */
@Slf4j
@Singleton
public class CombatAchievementSyncClient
{
	public interface Callback
	{
		void onSuccess();
		void onFailure(SyncFailure failure);
	}

	static final String DEFAULT_BASE_URL = "https://ironman.masley.com";
	private static final Object CALL_TAG = new Object();
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient client;
	private final BankTagsConfig config;
	private final CombatAchievementSyncJson json;

	@Inject
	public CombatAchievementSyncClient(OkHttpClient client, BankTagsConfig config, CombatAchievementSyncJson json)
	{
		this.client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
		this.config = config;
		this.json = json;
	}

	public void putProgress(CombatAchievementProgress progress, Callback callback)
	{
		Request.Builder request = request(callback);
		if (request == null)
		{
			return;
		}
		request.put(RequestBody.create(JSON, json.progressRequest(progress)));
		client.newCall(request.build()).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(Call call, IOException exception)
			{
				callback.onFailure(SyncFailure.network(exception.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					if (response.isSuccessful())
					{
						callback.onSuccess();
						return;
					}
					callback.onFailure(new SyncFailure(SyncFailure.kindForStatus(response.code()),
						response.code(), null, null, null, null));
				}
			}
		});
	}

	public void cancelAll()
	{
		for (Call call : client.dispatcher().queuedCalls())
		{
			if (call.request().tag() == CALL_TAG)
			{
				call.cancel();
			}
		}
		for (Call call : client.dispatcher().runningCalls())
		{
			if (call.request().tag() == CALL_TAG)
			{
				call.cancel();
			}
		}
	}

	@Nullable
	private Request.Builder request(Callback callback)
	{
		try
		{
			String configured = config.serverBaseUrl();
			String base = isBlank(configured) ? DEFAULT_BASE_URL : configured.trim();
			HttpUrl baseUrl = HttpUrl.parse(base);
			if (baseUrl == null)
			{
				throw new IllegalArgumentException("invalid server URL");
			}
			HttpUrl url = baseUrl.newBuilder()
				.addPathSegments("api/group")
				.addPathSegment(trim(config.groupName()))
				.addPathSegments("combat-achievements/snapshot")
				.build();
			return new Request.Builder().url(url).tag(CALL_TAG)
				.header("Authorization", trim(config.groupToken()))
				.header("Accept", "application/json");
		}
		catch (IllegalArgumentException ex)
		{
			log.debug("combat achievement sync request URL is invalid");
			callback.onFailure(new SyncFailure(SyncFailure.Kind.BAD_REQUEST, 0,
				"invalid_server_url", ex.getMessage(), null, null));
			return null;
		}
	}

	private static boolean isBlank(@Nullable String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static String trim(@Nullable String value)
	{
		return value == null ? "" : value.trim();
	}
}
