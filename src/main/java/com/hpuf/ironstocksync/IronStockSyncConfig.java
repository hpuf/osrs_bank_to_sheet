package com.hpuf.ironstocksync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(IronStockSyncConfig.GROUP)
public interface IronStockSyncConfig extends Config
{
    String GROUP = "iron-stock-sync";

    @ConfigItem(
        keyName = "endpointUrl",
        name = "Apps Script URL",
        description = "Google Apps Script web app /exec URL used to sync bank snapshots.",
        position = 0
    )
    default String endpointUrl()
    {
        return "";
    }

    @ConfigItem(
        keyName = "apiToken",
        name = "Sync token",
        description = "Private token used to authenticate bank snapshots.",
        secret = true,
        position = 1
    )
    default String apiToken()
    {
        return "";
    }

    @ConfigItem(
        keyName = "syncEnabled",
        name = "Enable syncing",
        description = "Send changed bank item IDs and quantities to the configured Apps Script endpoint.",
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
        position = 2
    )
    default boolean syncEnabled()
    {
        return false;
    }
}
