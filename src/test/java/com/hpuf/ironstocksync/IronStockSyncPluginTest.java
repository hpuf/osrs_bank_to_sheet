package com.hpuf.ironstocksync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class IronStockSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(IronStockSyncPlugin.class);
		RuneLite.main(args);
	}
}
