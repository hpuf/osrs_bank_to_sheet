# Iron Stock Sync

Personal RuneLite plugin for capturing an OSRS Ironman bank snapshot and eventually syncing resource counts to a progression tracker.

## Current milestone

The plugin now:

- captures the complete bank as an `itemId -> quantity` map when the bank opens;
- keeps the in-memory snapshot current while the bank remains open;
- saves the latest snapshot to RuneLite's per-character profile configuration;
- compares new snapshots with the cached copy and skips unchanged banks;
- writes a final changed snapshot when the bank closes after deposits or withdrawals.

No network requests or Google Sheets writes are implemented yet.

## Development

This project targets Java 11 and follows the RuneLite example-plugin structure.

Run the development client with:

```powershell
.\\gradlew.bat run
```

After logging in, enable **Iron Stock Sync** and open the bank.

On the first open (or after the bank changes), the debug log should contain:

```text
Bank snapshot updated (bank opened) - <count> unique items
Bank item <itemId> -> <quantity>
```

Opening the same unchanged bank again should instead produce:

```text
Bank snapshot unchanged (bank opened) - <count> unique items
```

If you deposit or withdraw items before closing the bank, the final changed state is cached on close.
