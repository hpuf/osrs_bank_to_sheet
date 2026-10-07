package com.hpuf.ironstocksync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Iron Stock Sync",
	description = "Captures Ironman bank stock for a personal progression tracker",
	tags = {"ironman", "bank", "tracker"},
	internalName = "iron_stock_sync"
)
public class IronStockSyncPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "iron-stock-sync";
	private static final String SNAPSHOT_KEY = "bankSnapshot";
	private static final String SNAPSHOT_TIME_KEY = "bankSnapshotTime";
	private static final Type SNAPSHOT_TYPE = new TypeToken<Map<Integer, Integer>>() { }.getType();

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	private boolean bankOpen;
	private Map<Integer, Integer> cachedBankSnapshot = Collections.emptyMap();
	private Map<Integer, Integer> currentBankSnapshot = Collections.emptyMap();

	@Override
	protected void startUp()
	{
		log.debug("Iron Stock Sync started");
	}

	@Override
	protected void shutDown()
	{
		bankOpen = false;
		cachedBankSnapshot = Collections.emptyMap();
		currentBankSnapshot = Collections.emptyMap();
		log.debug("Iron Stock Sync stopped");
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.BANKMAIN)
		{
			return;
		}

		bankOpen = true;
		cachedBankSnapshot = loadCachedSnapshot();

		// Let the bank container finish populating before taking the initial snapshot.
		clientThread.invokeLater(() -> captureBankSnapshot("bank opened"));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() != InterfaceID.BANKMAIN)
		{
			return;
		}

		// ItemContainerChanged keeps this copy current while the bank is open, so
		// closing the bank is a convenient final checkpoint after deposits/withdrawals.
		if (bankOpen && !currentBankSnapshot.isEmpty())
		{
			persistSnapshotIfChanged(currentBankSnapshot, "bank closed");
		}

		bankOpen = false;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!bankOpen || event.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		currentBankSnapshot = buildSnapshot(event.getItemContainer());
	}

	private void captureBankSnapshot(String reason)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			log.debug("Bank snapshot skipped ({}): bank container is not available", reason);
			return;
		}

		currentBankSnapshot = buildSnapshot(bank);
		persistSnapshotIfChanged(currentBankSnapshot, reason);
	}

	private void persistSnapshotIfChanged(Map<Integer, Integer> snapshot, String reason)
	{
		if (snapshot.equals(cachedBankSnapshot))
		{
			log.debug("Bank snapshot unchanged ({}) - {} unique items", reason, snapshot.size());
			return;
		}

		configManager.setRSProfileConfiguration(CONFIG_GROUP, SNAPSHOT_KEY, gson.toJson(snapshot));
		configManager.setRSProfileConfiguration(CONFIG_GROUP, SNAPSHOT_TIME_KEY, Instant.now().toString());
		cachedBankSnapshot = snapshot;

		log.debug("Bank snapshot updated ({}) - {} unique items", reason, snapshot.size());
		snapshot.forEach((itemId, quantity) ->
			log.debug("Bank item {} -> {}", itemId, quantity));
	}

	private Map<Integer, Integer> loadCachedSnapshot()
	{
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, SNAPSHOT_KEY);
		if (json == null || json.isEmpty())
		{
			return Collections.emptyMap();
		}

		try
		{
			Map<Integer, Integer> snapshot = gson.fromJson(json, SNAPSHOT_TYPE);
			if (snapshot == null)
			{
				return Collections.emptyMap();
			}

			return Collections.unmodifiableMap(new TreeMap<>(snapshot));
		}
		catch (RuntimeException ex)
		{
			log.debug("Unable to read cached bank snapshot; a fresh snapshot will replace it", ex);
			return Collections.emptyMap();
		}
	}

	static Map<Integer, Integer> buildSnapshot(ItemContainer bank)
	{
		Map<Integer, Integer> snapshot = new TreeMap<>();

		for (Item item : bank.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}

			snapshot.merge(item.getId(), item.getQuantity(), Integer::sum);
		}

		return Collections.unmodifiableMap(snapshot);
	}
}
