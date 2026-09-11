package com.emyrk.banktags.inventorysync;

import com.emyrk.banktags.inventorysync.model.SharedInventorySetup;
import com.emyrk.banktags.inventorysync.model.SharedInventorySetupSection;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import inventorysetups.InventorySetup;
import inventorysetups.InventorySetupsPersistentDataManager;
import inventorysetups.InventorySetupsPlugin;
import inventorysetups.InventorySetupsSection;
import inventorysetups.serialization.InventorySetupSerializable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Translates between Inventory Setups' local config and transport-neutral shared documents. */
@Singleton
public class InventorySetupRepository
{
	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	public InventorySetupRepository(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson.newBuilder().serializeNulls().create();
	}

	public Snapshot snapshot(InventorySetupsPlugin plugin)
	{
		InventorySetupIds.ensureStableIds(plugin.getInventorySetups(), plugin.getSections());
		List<SharedInventorySetup> setups = new ArrayList<>();
		Map<String, String> idByName = new HashMap<>();
		for (InventorySetup setup : plugin.getInventorySetups())
		{
			String id = InventorySetupIds.requireStableId(setup);
			idByName.put(setup.getName(), id);
			JsonObject payload = gson.toJsonTree(InventorySetupSerializable.convertFromInventorySetup(setup))
				.getAsJsonObject();
			payload.remove("name");
			payload.remove("notes");
			payload.remove("sid");
			setups.add(new SharedInventorySetup(id, setup.getName(), setup.getNotes(), payload, 0, false));
		}

		List<SharedInventorySetupSection> sections = new ArrayList<>();
		for (InventorySetupsSection section : plugin.getSections())
		{
			List<String> ids = new ArrayList<>();
			for (String name : section.getSetups())
			{
				String id = idByName.get(name);
				if (id != null && !ids.contains(id))
				{
					ids.add(id);
				}
			}
			Integer color = section.getDisplayColor() == null ? null : section.getDisplayColor().getRGB();
			sections.add(new SharedInventorySetupSection(section.getSyncId(), section.getName(), color, ids, 0, false));
		}
		return new Snapshot(setups, sections);
	}

	/** Applies both domains as one client-thread transaction, then reloads the Inventory Setups view. */
	public void apply(InventorySetupsPlugin plugin, List<SharedInventorySetup> setups,
		List<SharedInventorySetupSection> sections)
	{
		Map<String, Boolean> maximized = new HashMap<>();
		for (InventorySetupsSection section : plugin.getSections())
		{
			maximized.put(section.getSyncId(), section.isMaximized());
		}

		String wholePrefix = ConfigManager.getWholeKey(InventorySetupsPlugin.CONFIG_GROUP, null,
			InventorySetupsPersistentDataManager.CONFIG_KEY_SETUPS_V3_PREFIX);
		for (String key : configManager.getConfigurationKeys(wholePrefix))
		{
			String[] parts = key.split("\\.", 2);
			if (parts.length == 2)
			{
				configManager.unsetConfiguration(parts[0], parts[1]);
			}
		}

		List<String> setupOrder = new ArrayList<>();
		Map<String, String> nameById = new LinkedHashMap<>();
		for (SharedInventorySetup setup : setups)
		{
			if (setup.isDeleted())
			{
				continue;
			}
			setupOrder.add(setup.getSetupId());
			nameById.put(setup.getSetupId(), setup.getName());
			JsonObject value = setup.getPayload();
			value.addProperty("name", setup.getName());
			value.addProperty("notes", setup.getNotes());
			value.addProperty("sid", setup.getSetupId());
			configManager.setConfiguration(InventorySetupsPlugin.CONFIG_GROUP,
				InventorySetupsPersistentDataManager.CONFIG_KEY_SETUPS_V3_PREFIX + setup.getSetupId(),
				gson.toJson(value));
		}
		configManager.setConfiguration(InventorySetupsPlugin.CONFIG_GROUP,
			InventorySetupsPersistentDataManager.CONFIG_KEY_SETUPS_ORDER_V3, gson.toJson(setupOrder));

		List<InventorySetupsSection> localSections = new ArrayList<>();
		for (SharedInventorySetupSection section : sections)
		{
			if (section.isDeleted())
			{
				continue;
			}
			InventorySetupsSection local = new InventorySetupsSection(section.getName());
			local.setSyncId(section.getSectionId());
			if (section.getDisplayColor() != null)
			{
				local.setDisplayColor(new Color(section.getDisplayColor(), true));
			}
			local.setMaximized(Boolean.TRUE.equals(maximized.get(section.getSectionId())));
			List<String> names = new ArrayList<>();
			for (String id : section.getOrderedSetupIds())
			{
				String name = nameById.get(id);
				if (name != null && !names.contains(name))
				{
					names.add(name);
				}
			}
			local.setSetups(names);
			localSections.add(local);
		}
		configManager.setConfiguration(InventorySetupsPlugin.CONFIG_GROUP,
			InventorySetupsPersistentDataManager.CONFIG_KEY_SECTIONS, gson.toJson(localSections));
		plugin.reloadInventorySetupSyncState();
	}

	public static final class Snapshot
	{
		private final List<SharedInventorySetup> setups;
		private final List<SharedInventorySetupSection> sections;

		Snapshot(List<SharedInventorySetup> setups, List<SharedInventorySetupSection> sections)
		{
			this.setups = setups;
			this.sections = sections;
		}

		public List<SharedInventorySetup> getSetups()
		{
			return setups;
		}

		public List<SharedInventorySetupSection> getSections()
		{
			return sections;
		}
	}
}
