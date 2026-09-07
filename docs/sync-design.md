# Shared Bank Tag Sync Design Notes

## Status

This document records the intended boundary and the decisions that must be made before implementing central-server synchronization. It is not yet a finalized protocol specification.

## Goal

Allow members of one Group Ironman group to use the same bank tag organization across separate RuneLite clients.

The initial shared-data candidates are:

- Item-to-tag associations.
- Ordered tag tabs.
- Tab icon item IDs.
- Per-tag item layouts.

The plugin should continue to work from an isolated synchronized RuneLite configuration cache when offline. Remote synchronization must never overwrite the built-in `banktags` group or the existing `emyrk-bank-tags` local data.

## Non-goals

- Synchronizing actual bank contents or quantities.
- Tracking player location, equipment, activity, or other gameplay state.
- Discovering Group Ironman membership from other players.
- Storing Jagex or RuneLite credentials.
- Making network availability a requirement for opening or using the bank.
- Sharing local display and navigation preferences such as active tab or scroll position.

## Confirmed client behavior

Once synchronization is enabled and credentials are configured, RuneLite synchronizes automatically in both directions:

- A completed local mutation queues an upload for only the affected tag.
- Manifest polling discovers remote changes and retrieves only affected tags.
- Compound actions are debounced so intermediate config writes are not uploaded.
- A conflict blocks only that tag. Other tags continue syncing.
- Normal gameplay does not require manual save or retrieve actions.

## Isolated local storage

Use separate RuneLite configuration namespaces:

- `banktags`: built-in Bank Tags data, never written by this plugin's sync layer.
- `emyrk-bank-tags`: existing Bank Tags Extended local data, retained as the pre-sync source and fallback.
- `emyrk-bank-tags-sync`: active synchronized tag data and revision cache.
- `emyrk-bank-tags-sync-settings`: opt-in, group name, group token, endpoint, polling, and debounce preferences.

On first enable, fetch remote before seeding the sync cache. If remote has tags, copy remote data into `emyrk-bank-tags-sync`. If remote is empty, copy existing `emyrk-bank-tags` data into the sync cache and upload it one tag at a time. Never delete or reverse-copy into either source namespace.

Disabling sync switches the plugin back to the pre-sync local repository. It does not delete the remote group or the synchronized cache.

## Proposed client boundary

Avoid adding HTTP calls directly to the current managers. A useful separation is:

1. **Shared model**: a versioned, transport-neutral representation of tags, tabs, icons, and layouts.
2. **Local repository**: reads and atomically applies the shared model to `ConfigManager`.
3. **Sync client**: performs asynchronous HTTP requests with injected OkHttp and Gson.
4. **Coordinator**: decides when to pull, when to push, how to compare revisions, and how to suppress feedback loops.
5. **UI integration**: refreshes active bank widgets on the client thread after a remote model is applied.

The existing managers can remain responsible for RuneLite-specific behavior. The new repository should centralize shared-state reads and writes so network code does not need to know config key formats.

## Candidate shared document

A complete snapshot is simpler than replaying every existing `ConfigManager` write because one UI action can update multiple keys.

A conceptual payload could contain:

```json
{
  "schemaVersion": 1,
  "revision": "server-issued-opaque-value",
  "tabs": [
    {
      "name": "herblore",
      "iconItemId": 952,
      "itemIds": [199, 201, -203],
      "layout": [199, 201, -1, 203]
    }
  ]
}
```

This is illustrative only. Before implementation, decide whether item associations should be grouped by tab, grouped by item, or represented both ways. The canonical representation must avoid duplicate or contradictory data.

Required protocol properties:

- Explicit `schemaVersion`.
- Server-issued revision or ETag for conditional writes.
- Deterministic normalization of tag names.
- Defined meaning for negative variation item IDs.
- Defined deletion behavior.
- Payload size limits and validation.
- Stable handling of unknown fields and unsupported versions.

## Local and remote flow

### Startup or login

1. Plugin selects `emyrk-bank-tags-sync` as the active repository when sync is enabled, otherwise it uses `emyrk-bank-tags`.
2. It renders the active local cache immediately.
3. If sync is enabled and configured, request the latest manifest asynchronously.
4. Compare its group and per-tag revisions with locally stored revisions.
5. Fetch and apply only changed tag documents.
6. Refresh the bank UI once on the client thread if it is open.

The exact trigger should be chosen after authentication and player identity are defined. Do not block `startUp()`.

### Local mutation

1. Complete the whole domain operation in `emyrk-bank-tags-sync`.
2. Notify the coordinator of the affected tag after the compound operation finishes.
3. Build a normalized snapshot for only that tag.
4. Debounce and asynchronously upload it with the expected server revision.
5. Store the accepted server revision and content hash.

Rapid layout drags and bulk item tagging should not produce one request per config write. Debouncing or coalescing is required.

### Remote mutation

1. Validate schema, size, names, IDs, and revision.
2. Mark the apply operation as remote-originated.
3. Persist the model locally.
4. Clear the remote-apply marker.
5. Refresh UI state on the client thread.

The coordinator must not observe step 3 and upload the same state as a new local edit.

## Conflict policy

Use complete per-tag snapshots with optimistic concurrency. The server rejects a stale expected revision with `409 Conflict`.

If a remote revision changes while the local content still matches its last synchronized hash, apply remote automatically. If the local hash also changed, mark only that tag conflicted and preserve both the local synchronized cache and the remote server version until the user resolves it.

The remaining v1 decision is whether users may explicitly overwrite the remote version after a conflict or whether resolution is remote-wins only. Do not silently apply last-writer-wins behavior.

Operations needing special attention:

- Rename versus rename.
- Rename versus delete.
- Tab reorder by two clients.
- Layout edits by two clients.
- One client importing a large tab while another edits it.
- Removing a tag from one item versus deleting the entire tab.

## Group identity and authentication

This remains unresolved.

The implementation needs a way to map multiple plugin installations to one server-side group without collecting RuneLite or Jagex credentials. Questions include:

- Is a group created with an invite code, shared secret, or server account?
- How is a member removed or a leaked invite rotated?
- Can a user belong to more than one shared group?
- Is the character name required at all?
- What does the server log, and for how long?
- How are write permissions separated from read permissions?

Do not place server secrets in the repository. Do not log authorization headers or invite secrets.

## RuneLite privacy and configuration requirements

Synchronization is a third-party network feature. Its enable toggle must default to disabled and include this exact warning:

> This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers

Only shared tag metadata should be transmitted. Do not transmit bank contents, quantities, player locations, equipment, credentials, or unrelated player data.

## Reliability requirements

- Local reads and edits work while the server is down.
- Network failures do not run on or stall the client thread.
- Requests have bounded timeouts.
- Pending work is coalesced and bounded.
- Shutdown cancels scheduled work and requests owned by the plugin without blocking.
- Invalid server responses leave the last known-good local state intact.
- Applying a document is all-or-nothing from the sync coordinator's perspective.
- Logs identify failure classes without leaking payloads or secrets.

## Test plan

Add unit tests before connecting the feature to the bank UI:

1. Shared model serialization and schema version handling.
2. Snapshot creation from existing config keys.
3. Atomic apply, including removal of keys absent from the remote snapshot.
4. Negative variation item IDs and empty layout slots.
5. Tag normalization and duplicate handling.
6. Conditional upload success and stale revision response.
7. Offline startup and retry behavior.
8. Debouncing several local writes into one upload.
9. Remote apply does not trigger an upload feedback loop.
10. Active tab refresh happens on the client thread.
11. Secrets and payloads are absent from logs.
12. Disabling sync stops network work without deleting local tags.

Integration tests should use a mock HTTP server. In-game verification remains manual.

## Decisions required before coding

- Authentication and group enrollment.
- Server ownership and endpoint configuration.
- Snapshot versus operation protocol.
- Conflict and deletion semantics.
- Whether hidden-tag state is shared or local.
- Pull triggers and polling or push strategy.
- Offline edit queue behavior.
- Maximum document size and rate limits.
- User-visible status and conflict messages.
