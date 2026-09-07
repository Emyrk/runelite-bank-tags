# Per-Tag Remote Sync Implementation Plan

## Purpose

Add remote synchronization so a Group Ironman group can create, edit, publish, retrieve, and delete one bank tag tab at a time from RuneLite or from a web editor hosted with groupiron.men.

This plan spans three codebases:

1. This Bank Tags Extended RuneLite plugin.
2. The groupiron.men server and frontend.
3. Selected bank-layout editor behavior from banklayouts.com, adapted into the groupiron.men frontend.

The existing `bank-tags-sync` script is a useful prototype. It proves that a tab can be modeled independently with a name, icon, item set, layout, hash, timestamp, and deletion marker. The production design should replace file-level timestamp merging with server revisions and optimistic concurrency.

## Confirmed v1 product behavior

1. **One tag is the synchronization unit.** A tag document contains its name, icon, tagged item IDs, and optional layout.
2. **Tab order is separate group metadata.** Ordering cannot be represented reliably by one isolated tag. The server exposes a small ordered list of tag IDs in addition to per-tag documents.
3. **RuneLite synchronization is automatic.** Once enabled, completed local tag mutations are uploaded automatically and remote revisions are downloaded automatically. Normal use does not require save or retrieve menu actions.
4. **Website saves publish immediately.** Saving in the web editor creates a new remote revision. Connected clients receive it on their next poll.
5. **Conflicts never silently overwrite edits.** Conditional writes prevent stale clients from replacing newer remote data. A conflicted tag stops syncing until it is resolved, while unrelated tags continue syncing.
6. **Deletes use tombstones.** Deletion remains visible to clients long enough for offline clients to learn about it.
7. **The existing GroupIron group name and token authenticate requests.** Bank Tags Extended asks for the same values rather than reading another plugin's private configuration.
8. **Canonical transport is JSON.** RuneLite properties strings and the `banktaglayoutsplugin:` format are import/export adapters, not the server storage format.
9. **Synchronized data uses an isolated RuneLite configuration namespace.** The built-in `banktags` group and the existing `emyrk-bank-tags` local copy are never overwritten by remote synchronization.
10. **The synchronized namespace remains an offline cache.** Network outages do not stop the bank UI from using the last synchronized tags.

## User workflows

### Edit a tag in RuneLite

1. User edits a tag, icon, layout, name, membership, deletion state, or tab order normally.
2. The plugin completes the local domain operation in the synchronized configuration namespace.
3. The coordinator snapshots only the affected tag and debounces rapid writes.
4. The plugin conditionally creates or updates that tag using its known revision.
5. On success, the plugin stores the returned tag revision, content hash, and group revision.
6. Other connected clients discover and apply the new revision automatically.

### Receive a remote tag in RuneLite

1. The manifest poll reports a new, changed, renamed, reordered, or deleted tag.
2. The plugin fetches only the affected tag documents.
3. A clean local synchronized copy is updated automatically.
4. A locally dirty copy becomes conflicted and is not overwritten.
5. The bank UI refreshes once on the RuneLite client thread after the remote batch is applied.

### Edit from the website

1. User signs into a groupiron.men group with its existing group name and token.
2. A `Bank tags` page loads the group tag manifest.
3. User creates or opens one tag in the embedded bank-layout editor.
4. The editor loads and saves the canonical tag JSON through the GroupIron API.
5. Saving uses the tag revision originally loaded by the editor.
6. A stale save receives a conflict response and shows reload or overwrite choices.

### Delete a tag

1. User deletes from RuneLite or the website.
2. The server records a tombstone and increments the group revision.
3. A clean client removes the local tab, item associations, icon, layout, and local sync metadata.
4. A dirty client reports a conflict instead of deleting local work.

## Canonical per-tag model

Use a stable server ID so rename does not become delete-plus-create.

```json
{
  "schemaVersion": 1,
  "tagId": "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721",
  "name": "herblore",
  "iconItemId": 952,
  "itemIds": [199, 201, -203],
  "layout": [199, 201, -1, 203],
  "revision": 7,
  "deleted": false,
  "updatedAt": "2026-09-07T20:00:00Z"
}
```

Rules:

- `schemaVersion` must equal a supported version.
- `tagId` is server-generated and stable across rename.
- `name` is normalized with the same semantics as RuneLite `Text.standardize`.
- Names are unique among non-deleted tags within a group.
- `itemIds` is a sorted, duplicate-free set.
- Negative `itemIds` preserve RuneLite variation-tag semantics.
- `layout` preserves slot order. `-1` means an empty slot.
- Every non-empty layout item must also be in the effective tag item set.
- Item IDs, layout length, name length, and total payload size have server-enforced limits.
- `revision` is server-issued and increases for each change to that tag.
- `updatedAt` is informational. It is not used to resolve conflicts.

## Group manifest model

The manifest makes change discovery cheap while tag bodies remain independent.

```json
{
  "schemaVersion": 1,
  "groupRevision": 42,
  "orderedTagIds": [
    "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721"
  ],
  "tags": [
    {
      "tagId": "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721",
      "name": "herblore",
      "revision": 7,
      "deleted": false
    }
  ]
}
```

The manifest is group-wide metadata. Tag content still saves and retrieves one tag at a time. Reordering updates the manifest, not every tag document.

## API plan for groupiron.men

All endpoints live under the existing authenticated group scope and use the current `Authorization` group token.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/group/{group_name}/bank-tags` | Get manifest, optionally only when newer than a supplied group revision |
| `POST` | `/api/group/{group_name}/bank-tags` | Create one tag |
| `GET` | `/api/group/{group_name}/bank-tags/{tag_id}` | Retrieve one tag |
| `PUT` | `/api/group/{group_name}/bank-tags/{tag_id}` | Update one tag with an expected revision |
| `DELETE` | `/api/group/{group_name}/bank-tags/{tag_id}` | Tombstone one tag with an expected revision |
| `PUT` | `/api/group/{group_name}/bank-tags/order` | Replace ordered tag IDs with an expected group revision |

Response behavior:

- `200`: retrieve or update succeeded.
- `201`: create succeeded.
- `204` or `304`: manifest has not changed since the supplied revision.
- `400`: invalid schema or payload.
- `401`: invalid group name or token.
- `404`: unknown tag.
- `409`: tag or group revision conflict. Return current metadata, not secrets.
- `413`: payload exceeds limits.

Use request bodies with `expectedRevision` or HTTP conditional headers consistently. Do not mix two concurrency mechanisms.

## Server storage plan

Add migrations in the existing GroupIron server migration system.

### `bank_tags`

- `group_id BIGINT NOT NULL`
- `tag_id UUID PRIMARY KEY`
- `name CITEXT NOT NULL`
- `icon_item_id INTEGER NOT NULL`
- `item_ids JSONB NOT NULL`
- `layout JSONB NULL`
- `revision BIGINT NOT NULL`
- `deleted_at TIMESTAMPTZ NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

Add a partial unique index on `(group_id, name)` for non-deleted rows.

### `bank_tag_groups`

- `group_id BIGINT PRIMARY KEY`
- `group_revision BIGINT NOT NULL`
- `ordered_tag_ids JSONB NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`

Every tag create, update, rename, or delete and every order update increments `group_revision` in the same database transaction as the changed data.

Retain tombstones for a defined period, recommended 90 days. The manifest must continue reporting retained tombstones. A later cleanup migration or job may purge older tombstones.

## RuneLite plugin design

### Isolated local storage

Remote synchronization must not write to RuneLite's built-in Bank Tags settings or to the current fork's pre-sync data.

Use three distinct namespaces:

| Config group | Purpose | Sync may write it? |
| --- | --- | --- |
| `banktags` | RuneLite built-in Bank Tags data | Never |
| `emyrk-bank-tags` | Existing Bank Tags Extended local data and migration source | Never after sync migration |
| `emyrk-bank-tags-sync` | Active synchronized tags, tabs, icons, layouts, tombstone state, and revision metadata | Yes |

Keep connection preferences in a separate `emyrk-bank-tags-sync-settings` config group so credentials and sync controls are not mixed with tag records.

When sync is enabled, the bank UI and managers use `emyrk-bank-tags-sync` as their active repository. When sync is disabled, the plugin can return to `emyrk-bank-tags` without reverse-copying remote changes into it. The original `banktags` group remains untouched in both modes.

Migration and bootstrap rules:

1. Never rename or clear `banktags` or `emyrk-bank-tags` keys.
2. On first sync enable, create the sync namespace without deleting either source namespace.
3. Fetch the remote manifest before deciding how to seed the sync namespace.
4. If the remote group has tags, populate the sync namespace from remote and leave local source data as a backup.
5. If the remote group has no tags, copy the current `emyrk-bank-tags` tag data into the sync namespace and upload it automatically one tag at a time.
6. Record a `syncStorageInitialized` marker only after the initial remote fetch and local namespace creation succeed.
7. Switching sync off changes the active repository only. It must not delete remote data or synchronized local cache data.
8. Provide an explicit `Reset synchronized cache` action for recovery. It clears only `emyrk-bank-tags-sync`, never either source group.

This isolation makes first-run remote authority safe because the user's previous Bank Tags data remains recoverable in its original namespace.


### New packages and responsibilities

Create `src/main/java/com/emyrk/banktags/sync/` with these boundaries:

- `model/SharedBankTag.java`: immutable canonical tag DTO.
- `model/BankTagManifest.java`: immutable manifest DTO.
- `BankTagSnapshotService.java`: converts one local tag to canonical form and applies one canonical tag locally.
- `BankTagSyncClient.java`: asynchronous OkHttp API client and Gson serialization.
- `BankTagSyncCoordinator.java`: polling, automatic per-tag upload/download/delete, revisions, conflict detection, and feedback-loop suppression.
- `BankTagSyncMetadata.java`: local IDs, revisions, hashes, and group revision persistence.
- `BankTagSyncStatus.java`: small state model for user-visible status and conflicts.

Networking must not enter `TagManager`, `TabManager`, `LayoutManager`, or `TabInterface`.

### Local application boundary

`BankTagSnapshotService` should orchestrate existing managers through new focused methods instead of duplicating raw `ConfigManager` writes:

- `TagManager.replaceItemsForTag(String tag, Collection<Integer> itemIds)`
- `TabManager.upsert(TagTab tab)` and stable rename support
- `LayoutManager.replaceLayout(String tag, int[] layout)`
- A single orchestration method that applies rename, item differences, icon, tab membership, and layout before refreshing the UI

Applying one tag must preserve unrelated tags on every item. For example, replacing `herblore` item membership must add or remove only `herblore`, not rewrite other tag names attached to those item IDs.

Because `ConfigManager` has no transaction, suppress observation and UI refresh until all writes for the tag finish. Then perform one refresh on the client thread.

### Local sync metadata

Store non-shared metadata in `emyrk-bank-tags-sync` alongside the synchronized cache, using reserved keys that cannot collide with tag names:

- `syncGroupRevision`: latest observed group revision.
- `syncTag_<tagId>`: JSON containing local standardized name, remote revision, and last-synchronized content hash.

A content hash is calculated from normalized name, icon, sorted items, and exact layout. It excludes remote timestamps and revisions.

Dirty detection:

- Current local hash equals baseline hash: local tag is clean and remote can auto-apply.
- Current local hash differs from baseline: local tag is dirty.
- Remote revision equals stored revision: there is nothing to apply.
- Remote revision changed and local is dirty: conflict.

### Configuration

Add opt-in settings to `BankTagsConfig`:

- Enable remote group sync, default `false`, with the required third-party server warning.
- Group name.
- Group token, rendered as a secret field when supported by the RuneLite config API.
- Optional server base URL for self-hosting, blank meaning the public groupiron.men server.
- Poll interval with a conservative minimum, recommended default 10 seconds.
- Debounce delay for automatic uploads, with a safe minimum and recommended default of 1 second.

Never log the group token, authorization header, full payload, or complete endpoint containing sensitive query data.

### UI changes

Normal tab actions remain unchanged and synchronize automatically. Do not add routine `Save tab to group` or `Retrieve tab from group` actions.

Add only recovery and visibility controls:

- Sync state for the active tag: synced, uploading, offline, or conflicted.
- `Use remote version` for a conflicted tag.
- `Overwrite remote version` for a conflicted tag, with confirmation and a fresh expected revision.
- `Retry sync` after a transient failure.
- `Reset synchronized cache` in plugin configuration, with confirmation.

Keep `TabInterface` limited to normal domain mutations and displaying status. It notifies the coordinator that a complete tag operation finished but does not construct HTTP requests.

### Polling and threading

- Start polling only when sync is enabled and credentials are non-empty.
- Use an owned single-thread scheduled executor only to trigger polls.
- Use injected OkHttp asynchronous requests for network work.
- Permit only one manifest request in flight.
- Coalesce a missed poll rather than queueing unbounded work.
- Debounce local mutations per tag so layout drags and bulk tagging produce one upload for the final state.
- Allow different tags to continue syncing when one tag is conflicted.
- Call `clientThread.invoke()` before refreshing bank widgets or showing RuneLite UI.
- Cancel scheduled futures, cancel owned HTTP calls, and call `shutdownNow()` during shutdown without waiting.

## Website plan

Add a `Bank tags` page to the groupiron.men frontend.

Reuse the behavior and visual concepts from the banklayouts.com editor, but isolate an adapter around its import/export string format. The page should work directly with canonical JSON internally.

Required page behavior:

- List tags in manifest order.
- Create, rename, edit, delete, and reorder tags.
- Edit icon, tagged item set, and grid layout.
- Import and export RuneLite `banktag:` and `banktaglayoutsplugin:` strings.
- Optionally generate a banklayouts.com share link.
- Display loaded revision and dirty state.
- Save one active tag at a time.
- Handle `409 Conflict` with `Reload remote` and `Overwrite with latest confirmation` choices.
- Warn before navigating away from unsaved edits.

Before copying source or assets, verify the bank-layout editor's license and attribution requirements. If reuse is not clearly licensed, reimplement the data transformations and editor behavior rather than copying code.

## TDD implementation sequence

### Milestone 0: approve contracts

No production code.

1. Record the confirmed automatic synchronization and isolated-storage decisions in all three repositories.
2. Confirm that the group token authorizes bank-tag editing for anyone who possesses it.
3. Confirm tombstone retention duration.
4. Confirm whether `Overwrite remote version` is allowed in v1 or conflicts are remote-wins only.
5. Freeze JSON examples and API error shapes in the three repositories.

Acceptance: plugin, server, and website share the same versioned schema fixtures.

### Milestone 1: per-tag local domain boundary

**Status: implemented in the plugin repository on September 7, 2026.** Networking, automatic initialization, and UI refresh coordination remain later milestones.

Implemented boundaries:

- `BankTagsStorage` selects local or initialized synchronized configuration without modifying source namespaces.
- `BankTagsSyncConfig` reserves isolated connection settings with sync disabled by default.
- `SharedBankTag` normalizes one tag and computes a deterministic SHA-256 content hash.
- `BankTagSnapshotService` snapshots, applies, renames, and deletes one tag through manager APIs.
- Manager persistence now routes through `BankTagsStorage`.
- Tests cover isolated initialization, preservation of unrelated item tags, exact and negative variation IDs, apply/delete orchestration, hashing, and sprite resources.

#### Red

Add plugin tests for:

- Snapshot one tag without including unrelated tag data.
- Snapshot includes positive exact IDs and negative variation IDs.
- Apply replaces only the target tag's item membership.
- Apply preserves unrelated tags on the same items.
- Apply icon, layout, and rename together.
- Apply deletion removes tab, icon, layout, and only that tag's item associations.
- Normalization produces a deterministic hash regardless of item iteration order.

#### Green

Implement `SharedBankTag`, `BankTagSnapshotService`, manager APIs, and hash calculation without networking.

#### Refactor

Move repeated standardization and item-diff logic into named helpers. Keep config key knowledge in managers and sync metadata classes, not DTOs.

Acceptance: all existing tests and new local-domain tests pass with `./gradlew test`.

### Milestone 2: GroupIron server API

#### Red

Add Rust server tests for:

- Authenticated create and retrieve.
- Group isolation.
- Duplicate normalized name rejection.
- Conditional update success.
- Stale tag revision returns `409` without mutation.
- Rename preserves `tagId`.
- Delete creates a tombstone.
- Manifest revision increments transactionally.
- Order rejects unknown, duplicate, or deleted IDs.
- Payload limits and unsupported schema versions.

#### Green

Add schema migration, models, validators, database functions, and routes under the existing authenticated group scope.

#### Refactor

Extract transaction and revision helpers only after create, update, delete, and order share proven behavior.

Acceptance: server tests pass and API fixtures match the plugin fixtures byte-for-byte where canonical JSON is required.

### Milestone 3: RuneLite HTTP sync

#### Red

Add plugin tests with MockWebServer for:

- Credentials and authorization header.
- Manifest not-modified response.
- Automatic create and update after a completed local mutation.
- Automatic retrieve and tombstone application after a manifest change.
- Rapid changes to one tag debounce into one upload.
- Changes to two tags remain independent uploads.
- Remote clean update auto-applies.
- Remote dirty update becomes a conflict and does not mutate local config.
- Applying remote data does not upload it again.
- Only one poll is in flight.
- Invalid JSON and unsupported schema leave local data unchanged.
- Disable and shutdown cancel future work.
- UI refresh is scheduled on `ClientThread`.

#### Green

Implement `BankTagSyncClient`, isolated storage selection, metadata, coordinator, lifecycle wiring, and configuration. Add conflict recovery UI only after automatic coordinator behavior is covered.

#### Refactor

Separate HTTP response parsing from sync decisions. Consolidate state transitions into `BankTagSyncStatus` so menu and chat messages do not infer state from exceptions.

Acceptance: plugin tests pass, no blocking network work runs on the client thread, synchronized tags still work with the server unavailable, and neither `banktags` nor `emyrk-bank-tags` changes during remote sync tests.

### Milestone 4: website editor

#### Red

Add frontend tests for:

- Canonical JSON to editor state and back.
- RuneLite import/export compatibility.
- Save sends only the active tag.
- Dirty navigation warning.
- Conflict response preserves local edits until the user chooses.
- Reorder updates only the manifest.
- Deleted tags disappear from normal lists but remain conflict-aware.

Add backend integration tests for the browser's exact request sequence.

#### Green

Add the Bank Tags page, API methods, editor adapter, and conflict UI.

#### Refactor

Separate editor state, RuneLite-format conversion, and remote persistence. Do not let Svelte components construct API payloads ad hoc.

Acceptance: a website-created tag can be retrieved by the development RuneLite client, edited in RuneLite, saved, and reopened on the website with the same icon, items, and layout.

### Milestone 5: ordering, rollout, and hardening

#### Red

Add end-to-end fixtures for two simulated clients and one website session:

- Website edit reaches both clean clients.
- One dirty client conflicts while one clean client updates.
- Rename is reflected everywhere without changing `tagId`.
- Delete reaches clients that were offline during deletion.
- Reordering does not alter tag content revisions.

#### Green

Finish group order controls, status messaging, retry backoff, metrics, tombstone cleanup, and operational limits.

#### Refactor

Review boundaries after real end-to-end use. Remove format duplication, clarify names, and update `docs/architecture.md` and `docs/sync-design.md` with final decisions.

Acceptance: automated suites pass in all repositories, server migrations are reversible or safely forward-only, and manual RuneLite verification covers create, publish, retrieve, conflict, rename, reorder, offline recovery, and delete.

## Manual validation checklist

Use a development group and non-sensitive test token.

1. Start two RuneLite development clients and one website session.
2. Create a tag from client A and verify it uploads automatically.
3. Verify client B receives the icon, items, layout, and tab membership.
4. Edit the tag on the website and verify both clean clients update.
5. Make an unsaved local edit on client B, then publish from the website.
6. Verify client A updates and client B reports a conflict without losing its local edit.
7. Resolve client B with `Use remote version`.
8. Rename and reorder from the website.
9. Disconnect client B, delete the tag, reconnect, and verify tombstone handling.
10. Stop the server and verify both clients retain fully usable local tags.

Do not automate RuneScape input. The user performs these checks in the development clients.

## Explicitly deferred work

- Real-time WebSocket or server-sent-event delivery. Polling is sufficient for v1.
- Fine-grained multi-writer merging within one tag.
- Per-member permissions beyond possession of the group token.
- Sharing actual bank contents through this plugin.
- Cross-group public layout catalogs.
- Uploading intermediate writes inside a compound local operation. Only the completed per-tag state is uploaded.
