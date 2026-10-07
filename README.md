# Iron Stock Sync

Personal RuneLite plugin for capturing an OSRS Ironman bank snapshot and eventually syncing resource counts to a progression tracker.

## Current milestone

Opening the bank captures the complete RuneLite bank item container into an in-memory `itemId -> quantity` map and writes a debug snapshot to the RuneLite log.

No network requests or Google Sheets writes are implemented yet.

## Development

This project targets Java 11 and follows the RuneLite example-plugin structure.

Run the development client with:

```powershell
.\\gradlew.bat run
```

After logging in, enable **Iron Stock Sync**, open the bank, and inspect the developer log for:

```text
Bank snapshot captured (bank opened) - <count> unique items
Bank item <itemId> -> <quantity>
```
