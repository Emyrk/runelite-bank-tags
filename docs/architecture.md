# Architecture

## Overview

Bank Tags Extended is a standalone external RuneLite plugin based on RuneLite's built-in Bank Tags plugin. It replaces the built-in plugin's bank tag UI and stores an independent copy of its configuration under `emyrk-bank-tags`.

The plugin optionally synchronizes tag data through the groupiron.men API. Local tag data, synchronized cache data, connection settings, and sync metadata remain durable through RuneLite's `ConfigManager`.

## Lifecycle

`BankTagsPlugin` is the plugin entry point.

On startup it:

1. Copies missing keys from the built-in `banktags` config group once.
2. Cleans invalid characters from legacy tag values.
3. Registers custom tab sprites.
4. Registers `TabInterface`, `LayoutManager`, and `PotionStorage` event subscribers.
5. Reinitializes the bank interface on the client thread.
6. Calls `BankTagSyncCoordinator.start()`, which is a no-op unless sync is enabled and configured.

On shutdown it stops the coordinator first (cancelling polling, debounces, and in-flight requests), then unregisters those components, removes custom widgets and sprite overrides, and reinitializes the normal bank interface.

RuneLite displays one configuration proxy per plugin, so `BankTagsConfig` is the single visible configuration interface. It uses `emyrk-bank-tags-sync-settings` for both local UI preferences and sync connection settings, while tag data remains in `emyrk-bank-tags` or `emyrk-bank-tags-sync`. On upgrade, existing UI preferences are copied once from `emyrk-bank-tags` when the destination key is absent. A sync-related `ConfigChanged` event stops and restarts the coordinator. When the `enabled` flag flips, the plugin also reloads `TabManager` and reinitializes the bank because `BankTagsStorage.getActiveGroup()` switches repositories immediately.

The `resetSyncCache` item in that group is a self-resetting action (RuneLite config panels have no buttons; its `warning` is the confirmation dialog). When its value becomes `true` the plugin immediately writes it back to `false`, stops the coordinator, calls `BankTagsStorage.resetSyncStorage()` (which unsets every `emyrk-bank-tags-sync.*` key: tag data, `sync*` metadata, and the `syncStorageInitialized` marker, never touching `emyrk-bank-tags` or `banktags`), then on the client thread reloads `TabManager`, reinitializes the bank, and starts the coordinator again, which re-runs the first-enable path and reloads the group's tags from the server. The write-back event (`false`) is ignored so the coordinator is not restarted twice.

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
- Resolve sync conflicts and retry rejected uploads from the tab's right-click menu.

Tab right-click entries are `Widget.setAction(op, text)` slots dispatched by `opTagTab` on the `TAB_OP_*` constants. Slots 1–6 are the classic actions; slots 7–9 are the sync recovery actions, written on every rebuild by `addSyncActions` from `BankTagSyncCoordinator.tagState(tag)` (a slot that does not apply is set to `null` so a rebuilt widget never keeps a stale entry):

| Constant | Slot | Text | Shown when | Effect |
| --- | --- | --- | --- | --- |
| `TAB_OP_SYNC_USE_REMOTE` | 7 | `Sync: use server version` | `TagState.CONFLICTED` | `syncCoordinator.useRemoteVersion(tag)` |
| `TAB_OP_SYNC_OVERWRITE_REMOTE` | 8 | `Sync: overwrite server version` | `TagState.CONFLICTED` | chatbox confirmation, then `syncCoordinator.overwriteRemoteVersion(tag)` on the client thread |
| `TAB_OP_SYNC_RETRY` | 9 | `Sync: retry` | `TagState.REJECTED`, or `TagState.PENDING` while `GlobalState.OFFLINE` | `syncCoordinator.retry(tag)` |

`TabInterface.sendChatMessage(String)` is public so the coordinator can route its console messages through it (always via `ClientThread.invoke`).

When the bank opens, it loads tab names from configuration, loads each icon, builds widgets, and optionally opens the remembered tab. UI callbacks that begin in chatbox completion handlers return to the client thread before changing plugin state.

### Synchronized folders

`BankTagFolderManager` owns local folder records in `emyrk-bank-tags-sync`. Each folder has a client-minted UUID, display name, icon item ID, and ordered child tag UUIDs. `folderOrder` stores the ordered folder IDs. Folder collapse state is a local preference under `emyrk-bank-tags-sync-settings` and is not synchronized.

`BankTagFolderSyncJson`, `BankTagFolderSyncMetadata`, and the folder methods on `BankTagSyncClient` implement the independent folder protocol documented in `docs/remote-sync-protocol.md`. `BankTagSyncCoordinator` polls the folder manifest after each tag-manifest poll, applies clean remote folders with observation suppressed, debounces folder document and order writes, and retries pending folder deletions. Folder deletion preserves its tags. Tag deletion removes the tag UUID from its owning folder before the tag tombstone is queued.

`TabInterface` renders folder headers, their expanded child rows, and unfiled tags. Folder-to-folder drags reorder folders. Tag drags move tags between folders, reorder children, or return them to the unfiled section. The existing flat tab strip remains in use when synchronization is inactive.

### `LayoutManager` and `Layout`

`LayoutManager` loads and saves a layout for each tag. The persisted value is a CSV array of item IDs. Array position is the visual slot and `-1` means empty.

`LayoutManager` also performs the bank widget manipulation needed to render a custom order. It matches exact items, placeholders, and item variations. Layout edits are persisted after drag, duplicate, remove, and auto-layout operations.

`Layout` clones arrays at its public boundary. This prevents callers from mutating the internal array without using its methods.

## Configuration schema

Tag data and migration state remain in `emyrk-bank-tags` when sync is disabled. The visible plugin preferences and sync connection settings use `emyrk-bank-tags-sync-settings` so RuneLite can render one consolidated settings panel.

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

### Sync coordinator and metadata

Milestone 3b adds `BankTagSyncMetadata` and `BankTagSyncCoordinator`.

`BankTagSyncMetadata` reads and writes only the reserved `sync`-prefixed keys of `emyrk-bank-tags-sync`, directly through `ConfigManager`, regardless of which repository is active. It never touches `banktags` or `emyrk-bank-tags`.

`BankTagSyncCoordinator` owns the automatic behaviour:

- **First enable** (no `syncStorageInitialized` marker): fetches the manifest. `401` stops with a chat message. A group with tags is fetched in full and applied into the sync namespace with `applyingRemote` set, then the marker is written and the bank reinitialized; local tags are untouched. An empty group copies the local tags into the sync namespace, mints a tag ID (`revision 0`) per tab, and schedules a create for each.
- **Polling** on RuneLite's shared `ScheduledExecutorService` with `If-None-Match`; only one poll is in flight. The manifest decision table from the protocol document produces fetches, local deletes, and conflict records; every needed tag is fetched first, and local state is only touched once all fetches succeeded. After applying, `TabInterface.refreshTabs()` rebuilds the UI, dirty tags are re-scheduled, and pending deletes are retried.
- **Uploads** are debounced per tag and send the snapshot taken on the client thread; the stored `baseHash` is the hash of what was sent. `409` and `404` responses become `syncConflict_` records and the tag becomes `CONFLICTED`. Network and server errors leave the tag dirty for the next poll sweep; while the coordinator is `OFFLINE` or has `INVALID_CREDENTIALS` the debounce still fires but nothing is sent. `400`/`413`/`428` and unreadable responses mark the tag `REJECTED` (an in-memory set): the poll sweep skips it until the user picks `Sync: retry` or changes the tag again.
- **Deletes** are recorded as `syncPendingDelete_` and sent with `If-Match`; `409`/`404` clear the pending record and let the next poll re-create the tab if it still exists remotely.
- **Order** uploads are debounced; a `409` reconciles the local order with the server's manifest and retries exactly once. A successful order response is a full manifest and is processed like a poll result.
- **Threading**: callbacks hop to the client thread through `ClientThread.invoke`; a generation counter makes replies from before `stop()` inert; nothing blocks.

### Sync status, backoff, and recovery (Milestone 3c)

`BankTagSyncStatus` holds the two observable enums. Menu text and chat messages read them, never exceptions or HTTP codes.

- `GlobalState`: `DISABLED` → `INITIALIZING` on `start()`, `ONLINE` after any successful request, `OFFLINE` after a `NETWORK`/`SERVER_ERROR` poll or first-enable failure, `INVALID_CREDENTIALS` after a `401` from any call. Every transition goes through the private `setGlobalState`, the only place that emits the connection messages (`invalid group name or token…`, `server unreachable, retrying in the background…`, `reconnected.`), each once per transition. Transitions into or out of `OFFLINE` refresh the tab strip because the `Sync: retry` entry depends on it.
- `TagState` is derived on demand by `tagState(tag)`: no `syncTag_` (or sync inactive) → `LOCAL_ONLY`; a `syncConflict_` for the tab's id, or for a remote tag of the same name (`duplicate_name`) → `CONFLICTED`; in the rejected set → `REJECTED`; a debounce pending, an upload in flight, or snapshot hash ≠ `baseHash` → `PENDING`; else `SYNCED`. Conflicts and rejections are announced once when they appear (`'<name>' changed on the server and locally…`, `'<name>' was rejected by the server (<code>).`) and refresh the tab strip; a `PENDING`→`SYNCED` upload while online changes no menu entry and does not rebuild the bank.
- **Backoff**: polling is a self-rescheduling `schedule(poll, delay)` instead of a fixed-delay timer. After success the delay is `pollIntervalSeconds`; after a `NETWORK`/`SERVER_ERROR` failure it is `min(pollIntervalSeconds × 2^consecutiveFailures, 300)` (10, 20, 40, 80, 160, 300, 300… for the default interval), reset on the next success. A first-enable transport failure retries on the same schedule. On `401` every timer is cancelled and nothing is rescheduled; the settings-change `stop(); start()` is the only way back.
- **Recovery actions** (client thread): `useRemoteVersion` applies the conflict's remote document with `applyingRemote` set (or deletes the tab when it is a tombstone), stores the remote revision and hash, and clears the conflict. `overwriteRemoteVersion` sends the local snapshot with `If-Match: "<conflict revision>"`; when the remote is a tombstone the id cannot be re-created, so a fresh id is minted (`revision 0`) and the tab is created under it, the old id is forgotten. A further `409` re-records the conflict. `retry` drops the rejection, resets the backoff, uploads immediately even while `OFFLINE`, and schedules an immediate poll. Every action ends with `TabInterface.refreshTabs()`.

Guice wiring note: `TabInterface` and `LayoutManager` depend on the coordinator, and the coordinator depends on `TabInterface` and (through `BankTagSnapshotService`) on `LayoutManager`. The cycle is broken with `Provider<TabInterface>` in the coordinator and `Provider<BankTagSyncCoordinator>` in `LayoutManager`.

### Sync client

Milestone 3a adds the HTTP layer under `com.emyrk.banktags.sync` without any coordinator, UI, or lifecycle wiring. `BankTagSyncClient` issues the v1 protocol requests through the injected `OkHttpClient` (with a 15 second call timeout) and reads `BankTagsSyncConfig` on every call, so URL, group, and token changes apply without a restart. `BankTagSyncJson` encodes request bodies and decodes tag, manifest, and error documents by walking JSON trees, so unknown fields are ignored and every required field is checked. `BankTagManifest`, `SyncFailure`, and `ManifestResult` are the immutable results; `SyncFailure` maps HTTP status codes to a `Kind` and carries the `current` tag or manifest from a `409`. Every request is asynchronous and its callbacks run on OkHttp threads: they must never touch RuneLite client state or bank widgets directly, and the future coordinator owns retries, backoff, and the hop back to the client thread. The client never logs URLs, headers, bodies, or the token.

## Mutation flow

Most writes ultimately call one of these methods:

- `TagManager.setTagString(...)`
- `TagManager.setHidden(...)`
- `TabManager.save()`
- `LayoutManager.saveLayout(...)`
- `LayoutManager.removeLayout(...)`

Today these methods write directly to `ConfigManager` (through `BankTagsStorage`). `TabInterface` can perform one user operation that causes several writes. Rename is the clearest example: it removes an old icon and layout, changes tab order, writes a new icon and layout, then rewrites item tags.

Configuration-change events are therefore a poor transaction boundary for synchronization. Instead, every user mutation entry point notifies `BankTagSyncCoordinator` **after** its persistence call completes, inside the same client-thread block. The coordinator then debounces and uploads the completed per-tag snapshot. The hooks are:

| Entry point | Notification |
| --- | --- |
| `TabInterface.handleNewTab` (new tab, import tab) | `onTagMutated(tag)` then `onTabOrderChanged()` |
| `TabInterface.opTagTab` change icon | `onTagMutated(tag)` |
| `TabInterface.opTagTab` enable/disable layout | `onTagMutated(tag)` |
| `TabInterface.deleteTab` (both delete options, rename-merge) | `onTagDeleted(tag)` then `onTabOrderChanged()` |
| `TabInterface.renameTab` | `onTagRenamed(old, new)`; merge branch `onTagMutated(new)` |
| `TabInterface.moveTagTab` | `onTabOrderChanged()` |
| `TabInterface.onWidgetDrag` item dropped on a tab | `onTagMutated(tab)` |
| `TabInterface` `Remove-tag` menu click | `onTagMutated(activeTag)` |
| `TabInterface.opDuplicateItem`, `opRemoveLayout` | `onTagMutated(activeLayout tag)` |
| `TabInterface.handleDeposit` (fast path and chatbox path) | `onTagMutated(tag)` per tag involved |
| `LayoutManager.dragCompleteHandler`, layout auto-append while drawing | `onTagMutated(layout tag)` |
| `LayoutManager` auto-layout "Keep" (moved onto the client thread) | `onTagMutated(tag)` |
| `BankTagsPlugin.editTags` | `onTagMutated(tag)` for the union of old and new exact/variation tags |

Only names that have a tab are synchronized; item tags without a tab stay local. Every notification is ignored while the coordinator is applying a remote update (`isApplyingRemote()`), which prevents a remote change from being uploaded again.

## Existing tests

`BankTagsPluginTest` uses Mockito and Guice field binding. It verifies bank search behavior for explicit `tag:` searches, normal searches, and fall-through behavior, the sync-settings restart, and the `resetSyncCache` action (flag written back to `false`, only the sync group cleared, restart on the client thread).

`BankTagsStorageTest` covers repository selection, the first-enable copy, and `resetSyncStorage`. `BankTagSyncClientTest` and `BankTagSyncCoordinatorTest` run against MockWebServer with a deterministic `FakeScheduler`; the coordinator suite covers first enable, debounce, conflicts, remote apply without feedback upload, tombstones, order reconciliation, backoff delays, `401` handling, the three recovery actions, and the `TagState` transitions. `ProtocolFixturesTest` pins the JSON fixtures.

There are no tests yet for:

- Tab ordering and icons outside the sync snapshot.
- Layout serialization.
- Migration.
- Import and export.

## Build

The Gradle build resolves the latest RuneLite release, compiles plugin code for Java 11, and provides:

```sh
./gradlew test
./gradlew run
```

`run` starts a development RuneLite client with developer mode and debug logging enabled.
