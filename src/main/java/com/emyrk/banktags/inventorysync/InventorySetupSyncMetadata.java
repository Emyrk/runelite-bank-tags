package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.BankTagsStorage;
import com.emyrk.banktags.inventorysync.model.InventorySetupManifest;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Per-server-and-group persistent sync state. Switching identity never deletes another cache. */
@Singleton
public class InventorySetupSyncMetadata
{
	static final String GROUP = BankTagsStorage.SYNC_DATA_GROUP;
	static final String ROOT = "inventorySetup";
	static final String ACTIVE_IDENTITY = ROOT + "Identity";

	private static final String INITIALIZED = "Initialized";
	private static final String GROUP_REVISION = "GroupRevision";
	private static final String SETUP_ORDER_REVISION = "SetupOrderRevision";
	private static final String SECTION_ORDER_REVISION = "SectionOrderRevision";
	private static final String SETUP_ORDER = "SetupOrder";
	private static final String SECTION_ORDER = "SectionOrder";
	private static final String SETUP_DOC = "SetupDoc_";
	private static final String SECTION_DOC = "SectionDoc_";
	private static final String SETUP_HASH = "SetupHash_";
	private static final String SECTION_HASH = "SectionHash_";
	private static final String PENDING_SETUP_DELETE = "PendingSetupDelete_";
	private static final String PENDING_SECTION_DELETE = "PendingSectionDelete_";
	private static final String SETUP_CONFLICT_DOC = "SetupConflictDoc_";
	private static final String SECTION_CONFLICT_DOC = "SectionConflictDoc_";
	private static final String SETUP_CONFLICT_REASON = "SetupConflictReason_";
	private static final String SECTION_CONFLICT_REASON = "SectionConflictReason_";
	private static final String ORDER_CONFLICT_MANIFEST = "OrderConflictManifest";

	public enum IdentityChange
	{
		INITIAL,
		UNCHANGED,
		CHANGED
	}

	private final ConfigManager config;
	private final InventorySetupSyncJson json;
	private String activePrefix;

	@Inject
	public InventorySetupSyncMetadata(ConfigManager config, InventorySetupSyncJson json)
	{
		this.config = config;
		this.json = json;
	}

	public IdentityChange activateIdentity(String serverBaseUrl, String groupName)
	{
		String identity = identity(serverBaseUrl, groupName);
		String previous = config.getConfiguration(GROUP, ACTIVE_IDENTITY);
		activePrefix = ROOT + "Cache_" + sha256(identity) + "_";
		config.setConfiguration(GROUP, ACTIVE_IDENTITY, identity);
		if (previous == null)
		{
			return IdentityChange.INITIAL;
		}
		return identity.equals(previous) ? IdentityChange.UNCHANGED : IdentityChange.CHANGED;
	}

	public boolean initialized() { return Boolean.parseBoolean(get(INITIALIZED)); }
	public void initialized(boolean value) { setFlag(INITIALIZED, value); }
	public long groupRevision() { return number(GROUP_REVISION); }
	public void groupRevision(long value) { putNumber(GROUP_REVISION, value); }
	public long setupOrderRevision() { return number(SETUP_ORDER_REVISION); }
	public void setupOrderRevision(long value) { putNumber(SETUP_ORDER_REVISION, value); }
	public long sectionOrderRevision() { return number(SECTION_ORDER_REVISION); }
	public void sectionOrderRevision(long value) { putNumber(SECTION_ORDER_REVISION, value); }
	public List<String> setupOrder() { return csv(SETUP_ORDER); }
	public void setupOrder(List<String> ids) { set(SETUP_ORDER, String.join(",", ids)); }
	public List<String> sectionOrder() { return csv(SECTION_ORDER); }
	public void sectionOrder(List<String> ids) { set(SECTION_ORDER, String.join(",", ids)); }

	public void acceptManifest(InventorySetupManifest manifest)
	{
		groupRevision(manifest.getGroupRevision());
		setupOrderRevision(manifest.getSetupOrderRevision());
		sectionOrderRevision(manifest.getSectionOrderRevision());
		setupOrder(manifest.getOrderedSetupIds());
		sectionOrder(manifest.getOrderedSectionIds());
		initialized(true);
	}

	public void putSetup(SharedInventorySetup document, String hash)
	{
		set(SETUP_DOC + document.getSetupId(), json.setupDocument(document));
		set(SETUP_HASH + document.getSetupId(), hash == null ? "" : hash);
	}

	public void putSection(SharedInventorySetupSection document, String hash)
	{
		set(SECTION_DOC + document.getSectionId(), json.sectionDocument(document));
		set(SECTION_HASH + document.getSectionId(), hash == null ? "" : hash);
	}

	@Nullable
	public SharedInventorySetup setup(String id)
	{
		try
		{
			String value = get(SETUP_DOC + id);
			return value == null ? null : json.parseSetup(value);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	@Nullable
	public SharedInventorySetupSection section(String id)
	{
		try
		{
			String value = get(SECTION_DOC + id);
			return value == null ? null : json.parseSection(value);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	public Map<String, SharedInventorySetup> setups()
	{
		Map<String, SharedInventorySetup> result = new LinkedHashMap<>();
		for (String id : ids(SETUP_DOC))
		{
			SharedInventorySetup document = setup(id);
			if (document != null)
			{
				result.put(id, document);
			}
		}
		return result;
	}

	public Map<String, SharedInventorySetupSection> sections()
	{
		Map<String, SharedInventorySetupSection> result = new LinkedHashMap<>();
		for (String id : ids(SECTION_DOC))
		{
			SharedInventorySetupSection document = section(id);
			if (document != null)
			{
				result.put(id, document);
			}
		}
		return result;
	}

	public String setupHash(String id) { return valueOrEmpty(get(SETUP_HASH + id)); }
	public String sectionHash(String id) { return valueOrEmpty(get(SECTION_HASH + id)); }

	public void pendingSetupDelete(String id, long baseRevision)
	{
		putNumber(PENDING_SETUP_DELETE + id, baseRevision);
	}

	public void pendingSectionDelete(String id, long baseRevision)
	{
		putNumber(PENDING_SECTION_DELETE + id, baseRevision);
	}

	public Map<String, Long> pendingSetupDeletes() { return revisions(PENDING_SETUP_DELETE); }
	public Map<String, Long> pendingSectionDeletes() { return revisions(PENDING_SECTION_DELETE); }
	public boolean isSetupDeletePending(String id) { return get(PENDING_SETUP_DELETE + id) != null; }
	public boolean isSectionDeletePending(String id) { return get(PENDING_SECTION_DELETE + id) != null; }
	public void clearPendingSetupDelete(String id) { unset(PENDING_SETUP_DELETE + id); }
	public void clearPendingSectionDelete(String id) { unset(PENDING_SECTION_DELETE + id); }

	public void setupConflict(String localId, String reason, SharedInventorySetup remote)
	{
		set(SETUP_CONFLICT_REASON + localId, reason);
		set(SETUP_CONFLICT_DOC + localId, json.setupDocument(remote));
	}

	public void sectionConflict(String localId, String reason, SharedInventorySetupSection remote)
	{
		set(SECTION_CONFLICT_REASON + localId, reason);
		set(SECTION_CONFLICT_DOC + localId, json.sectionDocument(remote));
	}

	@Nullable
	public SharedInventorySetup setupConflict(String localId)
	{
		try
		{
			String value = get(SETUP_CONFLICT_DOC + localId);
			return value == null ? null : json.parseSetup(value);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	@Nullable
	public SharedInventorySetupSection sectionConflict(String localId)
	{
		try
		{
			String value = get(SECTION_CONFLICT_DOC + localId);
			return value == null ? null : json.parseSection(value);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	public Map<String, SharedInventorySetup> setupConflicts()
	{
		Map<String, SharedInventorySetup> result = new LinkedHashMap<>();
		for (String id : ids(SETUP_CONFLICT_DOC))
		{
			SharedInventorySetup document = setupConflict(id);
			if (document != null)
			{
				result.put(id, document);
			}
		}
		return result;
	}

	public Map<String, SharedInventorySetupSection> sectionConflicts()
	{
		Map<String, SharedInventorySetupSection> result = new LinkedHashMap<>();
		for (String id : ids(SECTION_CONFLICT_DOC))
		{
			SharedInventorySetupSection document = sectionConflict(id);
			if (document != null)
			{
				result.put(id, document);
			}
		}
		return result;
	}

	public void clearSetupConflict(String id)
	{
		unset(SETUP_CONFLICT_DOC + id);
		unset(SETUP_CONFLICT_REASON + id);
	}

	public void clearSectionConflict(String id)
	{
		unset(SECTION_CONFLICT_DOC + id);
		unset(SECTION_CONFLICT_REASON + id);
	}

	public void orderConflict(InventorySetupManifest manifest)
	{
		set(ORDER_CONFLICT_MANIFEST, json.manifestDocument(manifest));
	}

	@Nullable
	public InventorySetupManifest orderConflict()
	{
		try
		{
			String value = get(ORDER_CONFLICT_MANIFEST);
			return value == null ? null : json.parseManifest(value);
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	public void clearOrderConflict() { unset(ORDER_CONFLICT_MANIFEST); }

	private Map<String, Long> revisions(String prefix)
	{
		Map<String, Long> result = new LinkedHashMap<>();
		for (String id : ids(prefix))
		{
			result.put(id, number(prefix + id));
		}
		return result;
	}

	private List<String> csv(String key)
	{
		String value = get(key);
		return value == null || value.isEmpty() ? new ArrayList<>()
			: new ArrayList<>(Arrays.asList(value.split(",")));
	}

	private List<String> ids(String prefix)
	{
		List<String> result = new ArrayList<>();
		String fullPrefix = GROUP + "." + key(prefix);
		for (String wholeKey : config.getConfigurationKeys(fullPrefix))
		{
			if (wholeKey.startsWith(fullPrefix))
			{
				result.add(wholeKey.substring(fullPrefix.length()));
			}
		}
		return result;
	}

	private long number(String key)
	{
		try
		{
			String value = get(key);
			return value == null ? 0 : Long.parseLong(value);
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private void putNumber(String key, long value) { set(key, Long.toString(value)); }

	private void setFlag(String key, boolean value)
	{
		if (value)
		{
			set(key, "true");
		}
		else
		{
			unset(key);
		}
	}

	@Nullable
	private String get(String key) { return config.getConfiguration(GROUP, key(key)); }
	private void set(String key, Object value) { config.setConfiguration(GROUP, key(key), value); }
	private void unset(String key) { config.unsetConfiguration(GROUP, key(key)); }

	private String key(String suffix)
	{
		if (activePrefix == null)
		{
			throw new IllegalStateException("Inventory Setup sync identity is not active");
		}
		return activePrefix + suffix;
	}

	private static String identity(String serverBaseUrl, String groupName)
	{
		String server = serverBaseUrl == null ? "" : serverBaseUrl.trim();
		if (server.isEmpty())
		{
			server = "https://ironman.masley.com";
		}
		while (server.endsWith("/"))
		{
			server = server.substring(0, server.length() - 1);
		}
		String group = groupName == null ? "" : groupName.trim();
		return server + "\n" + group;
	}

	private static String sha256(String value)
	{
		try
		{
			byte[] bytes = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for (byte valueByte : bytes)
			{
				result.append(String.format("%02x", valueByte));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static String valueOrEmpty(@Nullable String value) { return value == null ? "" : value; }
}
