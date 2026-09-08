# Architecture

## Overview

Bank Tags Extended is a standalone external RuneLite plugin based on RuneLite's built-in Bank Tags plugin. It replaces the built-in plugin's bank tag UI and stores an independent copy of its configuration under `emyrk-bank-tags`.

The plugin has no server integration today. All durable data is stored through RuneLite's `ConfigManager`.

## Lifecycle

`BankTagsPlugin` is the plugin entry point.

On startup it:

1. Copies missing keys from the built-in `banktags` config group once.
2. Cleans invalid characters from legacy tag values.
3. Registers custom tab sprites.
4. Registers `TabInterface`, `LayoutManager`, and `PotionStorage` event subscribers.
5. Reinitializes the bank interface on the client thread.

On shutdown it unregisters those components, removes custom widgets and sprite overrides, then reinitializes the normal bank interface.

The built-in Bank Tags plugin must be disabled while this plugin is active because both modify the same bank interface.

## Components

### `BankTagsPlugin`

Responsibilities:

- Plugin lifecycle and dependency bindings.
- One-time migration from the built-in config group.
- Bank and Grand Exchange search integration.
- The item `Edit-tags` menu flow.
- Active tag, layout, and options state exposed through `BankTagsService`.

### `TagManager`

`TagManager` owns item-to-tag associations.

Tags are normalized with RuneLite text helpers and stored as CSV. Normal item tags use positive canonical item IDs. Variation tags map the item through `ItemVariationMapping` and store the resulting ID as negative. A lookup can combine exact and variation tags.

It also performs whole-tag removal, whole-tag rename, and hidden-tag persistence.

### `TabManager`

`TabManager` owns the in-memory ordered list of `TagTab` values. A tab has a standardized tag name and an icon item ID.

The ordered names are saved to `tagtabs`. Each icon is saved separately under `icon_<tag>`. `TabManager.clear()` only clears in-memory state. `save()` rewrites the order and all current icons.

### `TabInterface`

`TabInterface` owns the custom widgets added to the bank and most user-facing mutations:

- Create, delete, rename, reorder, import, and export tabs.
- Change tab icons.
- Tag inventory or equipment items.
- Add or remove items by menu action or drag.
- Enable, modify, or remove a layout.
- Restore the remembered active tab.

When the bank opens, it loads tab names from configuration, loads each icon, builds widgets, and optionally opens the remembered tab. UI callbacks that begin in chatbox completion handlers return to the client thread before changing plugin state.

### `LayoutManager` and `Layout`

`LayoutManager` loads and saves a layout for each tag. The persisted value is a CSV array of item IDs. Array position is the visual slot and `-1` means empty.

`LayoutManager` also performs the bank widget manipulation needed to render a custom order. It matches exact items, placeholders, and item variations. Layout edits are persisted after drag, duplicate, remove, and auto-layout operations.

`Layout` clones arrays at its public boundary. This prevents callers from mutating the internal array without using its methods.

## Configuration schema

All keys are in the `emyrk-bank-tags` group.

| Key | Value | Ownership |
| --- | --- | --- |
| `item_<id>` | CSV tag names | Shared-data candidate |
| `tagtabs` | CSV ordered tab names | Shared-data candidate |
| `icon_<tag>` | Icon item ID | Shared-data candidate |
| `layout_<tag>` | CSV item IDs by slot | Shared-data candidate |
| `hidden_<tag>` | Boolean marker | Currently local, decision required |
| `migratedFromBuiltin` | Boolean marker | Local migration state |
| `useTabs` | Boolean | Local UI preference |
| `rememberTab` | Boolean | Local UI preference |
| `removeTabSeparators` | Boolean | Local UI preference |
| `preventTagTabDrags` | Boolean | Local UI preference |
| `position` | Integer | Local scroll position |
| `tab` | String | Local remembered tab |

The source comments in `BankTagsPlugin` describe the main data keys. `BankTagsConfig` defines the visible and hidden preference keys.

### Planned synchronized storage

Remote sync will not write to the tables above. The plan introduces:

- `emyrk-bank-tags-sync`: an isolated active cache for remotely synchronized tags, order, icons, layouts, and revision metadata.
- `emyrk-bank-tags-sync-settings`: connection and synchronization preferences.

The built-in `banktags` group and current `emyrk-bank-tags` data remain unchanged by synchronization. When sync is disabled, the plugin can switch back to `emyrk-bank-tags` without copying remote values into it. See `docs/remote-sync-plan.md`.

Milestone 1 introduces `BankTagsStorage` as the repository selector used by `TagManager`, `TabManager`, and `LayoutManager`. The synchronized namespace becomes active only when sync is enabled and `syncStorageInitialized` is present. `initializeSyncStorageFromLocal()` copies only tag data keys into the synchronized namespace and leaves local UI preferences and both source groups unchanged.

The wire protocol is frozen in `docs/remote-sync-protocol.md`. Sync metadata lives in `emyrk-bank-tags-sync` under reserved `sync`-prefixed keys that cannot collide with tag data keys:

| Key | Value |
| --- | --- |
| `syncGroupRevision` | last applied `groupRevision` (long) |
| `syncOrderRevision` | last applied `orderRevision` (long) |
| `syncTag_<tagId>` | JSON `{ "name": "herblore", "revision": 7, "baseHash": "<sha256 hex>" }` — `name` is the current local standardized name; `revision` the last remote revision this client has applied or received on write; `baseHash` the `SharedBankTag.contentHash()` of that synced state |
| `syncPendingDelete_<tagId>` | JSON `{ "revision": 7 }` — a local delete not yet acknowledged by the server |
| `syncConflict_<tagId>` | JSON `{ "remote": <tag doc>, "reason": "stale_revision" \| "remote_changed" \| "duplicate_name" \| "tag_exists" }` — present only while the tag is conflicted |

`BankTagSnapshotService` reads and applies one complete tag. `SharedBankTag` is the canonical local model for name, icon, exact and variation item IDs, optional layout, remote identity, revision, and deletion state. Its SHA-256 content hash is deterministic and excludes transport metadata such as tag ID and revision.

### Sync client

Milestone 3a adds the HTTP layer under `com.emyrk.banktags.sync` without any coordinator, UI, or lifecycle wiring. `BankTagSyncClient` issues the v1 protocol requests through the injected `OkHttpClient` (with a 15 second call timeout) and reads `BankTagsSyncConfig` on every call, so URL, group, and token changes apply without a restart. `BankTagSyncJson` encodes request bodies and decodes tag, manifest, and error documents by walking JSON trees, so unknown fields are ignored and every required field is checked. `BankTagManifest`, `SyncFailure`, and `ManifestResult` are the immutable results; `SyncFailure` maps HTTP status codes to a `Kind` and carries the `current` tag or manifest from a `409`. Every request is asynchronous and its callbacks run on OkHttp threads: they must never touch RuneLite client state or bank widgets directly, and the future coordinator owns retries, backoff, and the hop back to the client thread. The client never logs URLs, headers, bodies, or the token.

## Mutation flow

Most writes ultimately call one of these methods:

- `TagManager.setTagString(...)`
- `TagManager.setHidden(...)`
- `TabManager.save()`
- `LayoutManager.saveLayout(...)`
- `LayoutManager.removeLayout(...)`

Today these methods write directly to `ConfigManager`. `TabInterface` can perform one user operation that causes several writes. Rename is the clearest example: it removes an old icon and layout, changes tab order, writes a new icon and layout, then rewrites item tags.

This means configuration-change events alone are a poor transaction boundary for synchronization. A future sync layer should capture a coherent shared document or explicit domain operation after the full local mutation completes.

## Existing tests

`BankTagsPluginTest` uses Mockito and Guice field binding. It currently verifies bank search behavior for explicit `tag:` searches, normal searches, and fall-through behavior.

There are no tests yet for:

- Tag persistence mutations.
- Tab ordering and icons.
- Layout serialization.
- Migration.
- Import and export.
- Networking or conflict handling.

## Build

The Gradle build resolves the latest RuneLite release, compiles plugin code for Java 11, and provides:

```sh
./gradlew test
./gradlew run
```

`run` starts a development RuneLite client with developer mode and debug logging enabled.
