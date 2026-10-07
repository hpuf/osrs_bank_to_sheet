package com.hpuf.ironstocksync;

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
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private boolean bankOpen;
	private Map<Integer, Integer> lastBankSnapshot = Collections.emptyMap();

	@Override
	protected void startUp()
	{
		log.debug("Iron Stock Sync started");
	}

	@Override
	protected void shutDown()
	{
		bankOpen = false;
		lastBankSnapshot = Collections.emptyMap();
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

		// Let the bank container finish populating before taking the initial snapshot.
		clientThread.invokeLater(() -> captureAndLogBankSnapshot("bank opened"));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!bankOpen || event.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		// Keep the in-memory copy current while the bank is open. Later milestones
		// will compare this snapshot and sync only when something actually changed.
		lastBankSnapshot = buildSnapshot(event.getItemContainer());
	}

	private void captureAndLogBankSnapshot(String reason)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			log.debug("Bank snapshot skipped ({}): bank container is not available", reason);
			return;
		}

		Map<Integer, Integer> snapshot = buildSnapshot(bank);
		lastBankSnapshot = snapshot;

		log.debug("Bank snapshot captured ({}) - {} unique items", reason, snapshot.size());
		snapshot.forEach((itemId, quantity) ->
			log.debug("Bank item {} -> {}", itemId, quantity));
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
