package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.BankTagsConfig;
import com.emyrk.banktags.inventorysync.InventorySetupSyncJson.CurrentKind;
import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.InventorySetupSyncFailure;
import com.emyrk.banktags.inventorysync.model.ManifestResponse;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.emyrk.banktags.sync.BankTagSyncJson.InvalidDocumentException;
import com.emyrk.banktags.sync.BankTagSyncJson.UnsupportedSchemaException;
import com.emyrk.banktags.sync.model.SyncFailure;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Asynchronous client for the combined Inventory Setups v1 API. */
@Singleton
public class InventorySetupSyncClient
{
	public interface Callback<T>
	{
		void onSuccess(T value);
		void onFailure(InventorySetupSyncFailure failure);
	}

	private interface Parser<T>
	{
		T parse(int status, String body) throws UnsupportedSchemaException, InvalidDocumentException;
	}

	private static final Object CALL_TAG = new Object();
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String DEFAULT_BASE_URL = "https://ironman.masley.com";

	private final OkHttpClient client;
	private final BankTagsConfig config;
	private final InventorySetupSyncJson json;

	@Inject
	public InventorySetupSyncClient(OkHttpClient client, BankTagsConfig config, InventorySetupSyncJson json)
	{
		this.client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
		this.config = config;
		this.json = json;
	}

	public void getManifest(@Nullable Long revision, Callback<ManifestResponse> callback)
	{
		Request.Builder request = request(callback, "inventory-setups");
		if (request == null)
		{
			return;
		}
		if (revision != null)
		{
			request.header("If-None-Match", etag(revision));
		}
		execute(request.get().build(), true, CurrentKind.NONE,
			(status, body) -> status == 304 ? ManifestResponse.notModified()
				: ManifestResponse.of(json.parseManifest(body)), callback);
	}

	public void getSetup(String id, Callback<SharedInventorySetup> callback)
	{
		get(callback, CurrentKind.SETUP, (status, body) -> json.parseSetup(body), "inventory-setups", id);
	}

	public void createSetup(SharedInventorySetup setup, Callback<SharedInventorySetup> callback)
	{
		putDocument("inventory-setups", setup.getSetupId(), 0, true, json.setupRequest(setup),
			CurrentKind.SETUP, (status, body) -> json.parseSetup(body), callback);
	}

	public void updateSetup(SharedInventorySetup setup, long revision, Callback<SharedInventorySetup> callback)
	{
		putDocument("inventory-setups", setup.getSetupId(), revision, false, json.setupRequest(setup),
			CurrentKind.SETUP, (status, body) -> json.parseSetup(body), callback);
	}

	public void deleteSetup(String id, long revision, Callback<SharedInventorySetup> callback)
	{
		delete("inventory-setups", id, revision, CurrentKind.SETUP,
			(status, body) -> json.parseSetup(body), callback);
	}

	public void getSection(String id, Callback<SharedInventorySetupSection> callback)
	{
		get(callback, CurrentKind.SECTION, (status, body) -> json.parseSection(body),
			"inventory-setup-sections", id);
	}

	public void createSection(SharedInventorySetupSection section,
		Callback<SharedInventorySetupSection> callback)
	{
		putDocument("inventory-setup-sections", section.getSectionId(), 0, true,
			json.sectionRequest(section), CurrentKind.SECTION,
			(status, body) -> json.parseSection(body), callback);
	}

	public void updateSection(SharedInventorySetupSection section, long revision,
		Callback<SharedInventorySetupSection> callback)
	{
		putDocument("inventory-setup-sections", section.getSectionId(), revision, false,
			json.sectionRequest(section), CurrentKind.SECTION,
			(status, body) -> json.parseSection(body), callback);
	}

	public void deleteSection(String id, long revision, Callback<SharedInventorySetupSection> callback)
	{
		delete("inventory-setup-sections", id, revision, CurrentKind.SECTION,
			(status, body) -> json.parseSection(body), callback);
	}

	public void putSetupOrder(long revision, List<String> ids, Callback<InventorySetupManifest> callback)
	{
		putOrder("inventory-setup-order", revision, json.setupOrder(ids), callback);
	}

	public void putSectionOrder(long revision, List<String> ids, Callback<InventorySetupManifest> callback)
	{
		putOrder("inventory-setup-section-order", revision, json.sectionOrder(ids), callback);
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

	private <T> void get(Callback<T> callback, CurrentKind currentKind, Parser<T> parser,
		String... path)
	{
		Request.Builder request = request(callback, path);
		if (request != null)
		{
			execute(request.get().build(), false, currentKind, parser, callback);
		}
	}

	private <T> void putDocument(String route, String id, long revision, boolean create, String body,
		CurrentKind currentKind, Parser<T> parser, Callback<T> callback)
	{
		Request.Builder request = request(callback, route, id);
		if (request == null)
		{
			return;
		}
		request.header(create ? "If-None-Match" : "If-Match", create ? "*" : etag(revision))
			.put(RequestBody.create(JSON, body));
		execute(request.build(), false, currentKind, parser, callback);
	}

	private <T> void delete(String route, String id, long revision, CurrentKind currentKind,
		Parser<T> parser, Callback<T> callback)
	{
		Request.Builder request = request(callback, route, id);
		if (request != null)
		{
			execute(request.header("If-Match", etag(revision)).delete().build(), false,
				currentKind, parser, callback);
		}
	}

	private void putOrder(String route, long revision, String body,
		Callback<InventorySetupManifest> callback)
	{
		Request.Builder request = request(callback, route);
		if (request != null)
		{
			execute(request.header("If-Match", etag(revision))
				.put(RequestBody.create(JSON, body)).build(), false, CurrentKind.MANIFEST,
				(status, responseBody) -> json.parseManifest(responseBody), callback);
		}
	}

	@Nullable
	private Request.Builder request(Callback<?> callback, String... path)
	{
		try
		{
			String configured = config.serverBaseUrl();
			String base = configured == null || configured.trim().isEmpty()
				? DEFAULT_BASE_URL : configured.trim();
			HttpUrl url = HttpUrl.parse(base);
			if (url == null)
			{
				throw new IllegalArgumentException("invalid server URL");
			}
			HttpUrl.Builder builder = url.newBuilder().addPathSegments("api/group")
				.addPathSegment(trim(config.groupName()));
			for (String segment : path)
			{
				builder.addPathSegment(segment);
			}
			return new Request.Builder().url(builder.build()).tag(CALL_TAG)
				.header("Authorization", trim(config.groupToken()))
				.header("Accept", "application/json");
		}
		catch (IllegalArgumentException ex)
		{
			callback.onFailure(new InventorySetupSyncFailure(SyncFailure.Kind.BAD_REQUEST, 0,
				"invalid_server_url", ex.getMessage(), null, null, null));
			return null;
		}
	}

	private <T> void execute(Request request, boolean allow304, CurrentKind currentKind,
		Parser<T> parser, Callback<T> callback)
	{
		client.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(Call call, IOException exception)
			{
				callback.onFailure(InventorySetupSyncFailure.network(exception.getMessage()));
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
					callback.onFailure(InventorySetupSyncFailure.network(ex.getMessage()));
					return;
				}

				if ((status >= 200 && status < 300) || (allow304 && status == 304))
				{
					try
					{
						callback.onSuccess(parser.parse(status, body));
					}
					catch (Exception ex)
					{
						callback.onFailure(new InventorySetupSyncFailure(SyncFailure.Kind.INVALID_RESPONSE,
							status, null, ex.getMessage(), null, null, null));
					}
					return;
				}
				callback.onFailure(json.parseErrorBody(status, body, currentKind));
			}
		});
	}

	private static String etag(long revision)
	{
		return "\"" + revision + "\"";
	}

	private static String trim(String value)
	{
		return value == null ? "" : value.trim();
	}
}
