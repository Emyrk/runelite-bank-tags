package com.emyrk.banktags.inventorysync;

import inventorysetups.InventorySetup;
import inventorysetups.InventorySetupsSection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class InventorySetupIds
{
	private InventorySetupIds() {}

	public static boolean ensureStableIds(List<InventorySetup> setups, List<InventorySetupsSection> sections)
	{
		boolean changed = false;
		Set<String> used = new HashSet<>();
		for (InventorySetup setup : setups)
		{
			String id = normalize(setup.getSyncId());
			if (id == null || !used.add(id))
			{
				id = newId(used);
				changed = true;
			}
			setup.setSyncId(id);
		}
		for (InventorySetupsSection section : sections)
		{
			String id = normalize(section.getSyncId());
			if (id == null || !used.add(id))
			{
				id = newId(used);
				changed = true;
			}
			section.setSyncId(id);
		}
		return changed;
	}

	public static String requireStableId(InventorySetup setup)
	{
		String id = normalize(setup.getSyncId());
		if (id == null)
		{
			id = UUID.randomUUID().toString();
			setup.setSyncId(id);
		}
		return id;
	}

	public static String normalize(String id)
	{
		if (id == null || !id.equals(id.toLowerCase(Locale.ROOT))) return null;
		try
		{
			UUID value = UUID.fromString(id);
			return value.version() == 4 && value.toString().equals(id) ? id : null;
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	/**
	 * Selects setup document keys during stable-ID migration. While the marker is present, only
	 * documents named by the published order are loaded, so legacy and staged UUID copies cannot
	 * both enter memory after an interrupted rewrite.
	 */
	public static List<String> setupKeysForLoad(List<String> order, Set<String> availableKeys,
		String configPrefix, boolean migrationInProgress)
	{
		Set<String> remaining = new HashSet<>(availableKeys);
		List<String> selected = new java.util.ArrayList<>();
		for (String id : order)
		{
			String key = configPrefix + id;
			if (remaining.remove(key))
			{
				selected.add(key);
			}
		}
		if (!migrationInProgress)
		{
			List<String> extras = new java.util.ArrayList<>(remaining);
			java.util.Collections.sort(extras);
			selected.addAll(extras);
		}
		return selected;
	}

	private static String newId(Set<String> used)
	{
		String id;
		do id = UUID.randomUUID().toString(); while (!used.add(id));
		return id;
	}
}
