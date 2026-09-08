package com.emyrk.banktags.sync;

import com.emyrk.banktags.BankTagsSyncConfig;
import com.emyrk.banktags.sync.model.BankTagManifest;
import com.emyrk.banktags.sync.model.ManifestResult;
import com.emyrk.banktags.sync.model.SharedBankTag;
import com.emyrk.banktags.sync.model.SyncFailure;
import java.io.IOException;
import java.util.List;
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
import okhttp3.ResponseBody;

/**
 * Asynchronous HTTP client for the v1 bank-tag sync API (docs/remote-sync-protocol.md).
 * <p>
 * Every method returns immediately and reports through {@link Callback} on an OkHttp thread.
 * Callbacks must never touch RuneLite client state directly; callers hop to the client thread
 * themselves. The client performs no retries, no backoff, and owns no threads.
 */
@Slf4j
@Singleton
public class BankTagSyncClient
{
	public interface Callback<T>
	{
		void onSuccess(T value);

		void onFailure(SyncFailure failure);
	}

	/**
	 * Parses a successful (2xx) response body into the callback value.
	 */
	private interface BodyParser<T>
	{
		T parse(int status, String body)
			throws BankTagSyncJson.UnsupportedSchemaException, BankTagSyncJson.InvalidDocumentException;
	}

	static final String DEFAULT_BASE_URL = "https://groupiron.men";
	static final String ERROR_INVALID_SERVER_URL = "invalid_server_url";

	private static final Object CALL_TAG = new Object();
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String ROUTE_MANIFEST = "manifest";
	private static final String ROUTE_TAG = "tag";
	private static final String ROUTE_ORDER = "order";

	private final OkHttpClient client;
	private final BankTagsSyncConfig config;
	private final BankTagSyncJson json;

	@Inject
	public BankTagSyncClient(OkHttpClient okHttpClient, BankTagsSyncConfig config, BankTagSyncJson json)
	{
		this.client = okHttpClient.newBuilder()
			.callTimeout(15, TimeUnit.SECONDS)
			.build();
		this.config = config;
		this.json = json;
	}

	/**
	 * {@code GET /bank-tags}. Sends {@code If-None-Match: "<rev>"} when a revision is given;
	 * a {@code 304} yields {@link ManifestResult#notModified()}.
	 */
	public void getManifest(@Nullable Long ifNoneMatchGroupRevision, Callback<ManifestResult> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tags");
		if (request == null)
		{
			return;
		}
		if (ifNoneMatchGroupRevision != null)
		{
			request.header("If-None-Match", etag(ifNoneMatchGroupRevision));
		}
		execute(request.get().build(), ROUTE_MANIFEST, false, true, (status, body) ->
			status == 304 ? ManifestResult.notModified() : ManifestResult.of(json.parseManifest(body)), callback);
	}

	/**
	 * {@code GET /bank-tags/{tagId}}.
	 */
	public void getTag(String tagId, Callback<SharedBankTag> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tags", tagId);
		if (request == null)
		{
			return;
		}
		execute(request.get().build(), ROUTE_TAG, false, false, this::parseTag, callback);
	}

	/**
	 * {@code PUT /bank-tags/{tagId}} with {@code If-None-Match: *} (create).
	 */
	public void createTag(String tagId, SharedBankTag tag, Callback<SharedBankTag> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tags", tagId);
		if (request == null)
		{
			return;
		}
		request.header("If-None-Match", "*")
			.put(RequestBody.create(JSON, json.tagRequestBody(tag)));
		execute(request.build(), ROUTE_TAG, false, false, this::parseTag, callback);
	}

	/**
	 * {@code PUT /bank-tags/{tagId}} with {@code If-Match: "<rev>"} (update).
	 */
	public void updateTag(String tagId, long ifMatchRevision, SharedBankTag tag, Callback<SharedBankTag> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tags", tagId);
		if (request == null)
		{
			return;
		}
		request.header("If-Match", etag(ifMatchRevision))
			.put(RequestBody.create(JSON, json.tagRequestBody(tag)));
		execute(request.build(), ROUTE_TAG, false, false, this::parseTag, callback);
	}

	/**
	 * {@code DELETE /bank-tags/{tagId}} with {@code If-Match: "<rev>"}. The response is the tombstoned document.
	 */
	public void deleteTag(String tagId, long ifMatchRevision, Callback<SharedBankTag> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tags", tagId);
		if (request == null)
		{
			return;
		}
		request.header("If-Match", etag(ifMatchRevision)).delete();
		execute(request.build(), ROUTE_TAG, false, false, this::parseTag, callback);
	}

	/**
	 * {@code PUT /bank-tag-order} with {@code If-Match: "<orderRevision>"}. The response is the manifest.
	 */
	public void putOrder(long ifMatchOrderRevision, List<String> orderedTagIds, Callback<BankTagManifest> callback)
	{
		Request.Builder request = newRequest(callback, "bank-tag-order");
		if (request == null)
		{
			return;
		}
		request.header("If-Match", etag(ifMatchOrderRevision))
			.put(RequestBody.create(JSON, json.orderRequestBody(orderedTagIds)));
		execute(request.build(), ROUTE_ORDER, true, false, (status, body) -> json.parseManifest(body), callback);
	}

	/**
	 * Cancels every queued or running call issued by this client. Their callbacks receive a
	 * {@link SyncFailure.Kind#NETWORK} failure.
	 */
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

	/**
	 * Builds {@code <base>/api/group/<groupName>/<segments...>} from the current configuration.
	 * Configuration is read on every call so changes take effect without a restart.
	 *
	 * @throws IllegalArgumentException when the configured server URL does not parse
	 */
	HttpUrl buildUrl(String... segments)
	{
		String configured = config.serverBaseUrl();
		String base = configured == null || configured.trim().isEmpty() ? DEFAULT_BASE_URL : configured.trim();
		HttpUrl baseUrl = HttpUrl.parse(base);
		if (baseUrl == null)
		{
			throw new IllegalArgumentException("server URL is not a valid http(s) URL");
		}
		String groupName = config.groupName();
		HttpUrl.Builder builder = baseUrl.newBuilder()
			.addPathSegments("api/group")
			.addPathSegment(groupName == null ? "" : groupName.trim());
		for (String segment : segments)
		{
			builder.addPathSegment(segment);
		}
		return builder.build();
	}

	/**
	 * Starts a request builder carrying the common headers, or reports an invalid server URL to
	 * the callback and returns {@code null}.
	 */
	@Nullable
	private Request.Builder newRequest(Callback<?> callback, String... segments)
	{
		HttpUrl url;
		try
		{
			url = buildUrl(segments);
		}
		catch (IllegalArgumentException ex)
		{
			callback.onFailure(new SyncFailure(SyncFailure.Kind.BAD_REQUEST, 0, ERROR_INVALID_SERVER_URL,
				ex.getMessage(), null, null));
			return null;
		}
		String token = config.groupToken();
		return new Request.Builder()
			.url(url)
			.tag(CALL_TAG)
			.header("Authorization", token == null ? "" : token.trim())
			.header("Accept", "application/json");
	}

	private SharedBankTag parseTag(int status, String body)
		throws BankTagSyncJson.UnsupportedSchemaException, BankTagSyncJson.InvalidDocumentException
	{
		return json.parseTag(body);
	}

	private <T> void execute(Request request, String routeTemplate, boolean orderRoute, boolean allowNotModified,
		BodyParser<T> parser, Callback<T> callback)
	{
		client.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("bank tag sync {} {} -> {}", request.method(), routeTemplate, ex.getClass().getSimpleName());
				callback.onFailure(SyncFailure.network(ex.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int status = response.code();
				String body;
				try (Response ignored = response)
				{
					ResponseBody responseBody = response.body();
					body = responseBody == null ? "" : responseBody.string();
				}
				catch (IOException ex)
				{
					log.debug("bank tag sync {} {} -> {} (body read failed)", request.method(), routeTemplate, status);
					callback.onFailure(SyncFailure.network(ex.getMessage()));
					return;
				}
				log.debug("bank tag sync {} {} -> {}", request.method(), routeTemplate, status);
				handle(status, body);
			}

			private void handle(int status, String body)
			{
				boolean success = response2xx(status) || (allowNotModified && status == 304);
				if (!success)
				{
					callback.onFailure(json.parseErrorBody(status, body, orderRoute));
					return;
				}
				T value;
				try
				{
					value = parser.parse(status, body);
				}
				catch (BankTagSyncJson.UnsupportedSchemaException | BankTagSyncJson.InvalidDocumentException
					| RuntimeException ex)
				{
					callback.onFailure(SyncFailure.invalidResponse(ex.getMessage()));
					return;
				}
				callback.onSuccess(value);
			}
		});
	}

	private static boolean response2xx(int status)
	{
		return status >= 200 && status < 300;
	}

	private static String etag(long revision)
	{
		return "\"" + revision + "\"";
	}
}
