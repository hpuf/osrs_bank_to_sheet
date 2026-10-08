package com.hpuf.ironstocksync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
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
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@PluginDescriptor(
	name = "Iron Stock Sync",
	description = "Captures Ironman bank stock for a personal progression tracker",
	tags = {"ironman", "bank", "tracker"},
	internalName = "iron_stock_sync"
)
public class IronStockSyncPlugin extends Plugin
{
	private static final String SNAPSHOT_KEY = "bankSnapshot";
	private static final String SNAPSHOT_TIME_KEY = "bankSnapshotTime";
	private static final String LAST_SYNCED_SNAPSHOT_TIME_KEY = "lastSyncedSnapshotTime";
	private static final int PAYLOAD_VERSION = 1;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private IronStockSyncConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	private boolean bankOpen;
	private String cachedSnapshotTime;
	private String syncInFlightSnapshotTime;
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
		cachedSnapshotTime = null;
		syncInFlightSnapshotTime = null;
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
		cachedSnapshotTime = configManager.getRSProfileConfiguration(
			IronStockSyncConfig.GROUP, SNAPSHOT_TIME_KEY);

		clientThread.invokeLater(() -> captureBankSnapshot("bank opened"));
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() != InterfaceID.BANKMAIN)
		{
			return;
		}

		if (bankOpen)
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

			if (cachedSnapshotTime != null)
			{
				syncSnapshotIfNeeded(snapshot, cachedSnapshotTime);
			}
			return;
		}

		String snapshotTime = Instant.now().toString();
		configManager.setRSProfileConfiguration(
			IronStockSyncConfig.GROUP, SNAPSHOT_KEY, gson.toJson(snapshot));
		configManager.setRSProfileConfiguration(
			IronStockSyncConfig.GROUP, SNAPSHOT_TIME_KEY, snapshotTime);

		cachedBankSnapshot = snapshot;
		cachedSnapshotTime = snapshotTime;

		log.debug("Bank snapshot updated ({}) - {} unique items", reason, snapshot.size());
		snapshot.forEach((itemId, quantity) ->
			log.debug("Bank item {} -> {}", itemId, quantity));

		syncSnapshotIfNeeded(snapshot, snapshotTime);
	}

	private void syncSnapshotIfNeeded(Map<Integer, Integer> snapshot, String snapshotTime)
	{
		if (!config.syncEnabled())
		{
			return;
		}

		String endpoint = config.endpointUrl().trim();
		String token = config.apiToken().trim();
		if (endpoint.isEmpty() || token.isEmpty())
		{
			log.warn("Bank sync is enabled, but the Apps Script URL or sync token is missing");
			return;
		}

		HttpUrl url = HttpUrl.parse(endpoint);
		if (url == null || !"https".equalsIgnoreCase(url.scheme())
			|| !"script.google.com".equalsIgnoreCase(url.host()))
		{
			log.warn("Bank sync URL must be an HTTPS script.google.com Apps Script web app URL");
			return;
		}

		String lastSyncedSnapshotTime = configManager.getRSProfileConfiguration(
			IronStockSyncConfig.GROUP, LAST_SYNCED_SNAPSHOT_TIME_KEY);
		if (snapshotTime.equals(lastSyncedSnapshotTime)
			|| snapshotTime.equals(syncInFlightSnapshotTime))
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("version", PAYLOAD_VERSION);
		payload.addProperty("token", token);
		payload.addProperty("capturedAt", snapshotTime);

		JsonObject items = new JsonObject();
		snapshot.forEach((itemId, quantity) ->
			items.addProperty(Integer.toString(itemId), quantity));
		payload.add("items", items);

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		syncInFlightSnapshotTime = snapshotTime;
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				clientThread.invoke(() ->
					handleSyncResult(snapshotTime, false, "network error: " + ex.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean success = false;
				String detail;

				try (response)
				{
					if (!response.isSuccessful())
					{
						detail = "HTTP " + response.code();
					}
					else
					{
						ResponseBody body = response.body();
						if (body == null)
						{
							detail = "empty response";
						}
						else
						{
							SyncResponse syncResponse = gson.fromJson(body.charStream(), SyncResponse.class);
							success = syncResponse != null && syncResponse.ok;
							detail = success
								? (syncResponse.ignored ? "accepted (already newer)" : "accepted")
								: (syncResponse == null || syncResponse.error == null
									? "invalid response"
									: syncResponse.error);
						}
					}
				}
				catch (RuntimeException ex)
				{
					detail = "invalid response: " + ex.getMessage();
				}

				boolean finalSuccess = success;
				String finalDetail = detail;
				clientThread.invoke(() ->
					handleSyncResult(snapshotTime, finalSuccess, finalDetail));
			}
		});
	}

	private void handleSyncResult(String snapshotTime, boolean success, String detail)
	{
		if (snapshotTime.equals(syncInFlightSnapshotTime))
		{
			syncInFlightSnapshotTime = null;
		}

		if (!success)
		{
			log.warn("Bank snapshot sync failed: {}", detail);
			return;
		}

		if (snapshotTime.equals(cachedSnapshotTime))
		{
			configManager.setRSProfileConfiguration(
				IronStockSyncConfig.GROUP, LAST_SYNCED_SNAPSHOT_TIME_KEY, snapshotTime);
		}

		log.debug("Bank snapshot sync successful: {}", detail);
	}

	private Map<Integer, Integer> loadCachedSnapshot()
	{
		String json = configManager.getRSProfileConfiguration(
			IronStockSyncConfig.GROUP, SNAPSHOT_KEY);
		if (json == null || json.isEmpty())
		{
			return Collections.emptyMap();
		}

		try
		{
			JsonObject object = gson.fromJson(json, JsonObject.class);
			if (object == null)
			{
				return Collections.emptyMap();
			}

			Map<Integer, Integer> snapshot = new TreeMap<>();
			for (Map.Entry<String, JsonElement> entry : object.entrySet())
			{
				int itemId = Integer.parseInt(entry.getKey());
				int quantity = entry.getValue().getAsInt();
				if (itemId > 0 && quantity > 0)
				{
					snapshot.put(itemId, quantity);
				}
			}

			return Collections.unmodifiableMap(snapshot);
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

	@Provides
	IronStockSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronStockSyncConfig.class);
	}

	private static final class SyncResponse
	{
		private boolean ok;
		private boolean ignored;
		private String error;
	}
}
