# Remote Sync Protocol (v1)

**Status: frozen (Milestone 0, issue #3, 2026-09-08).** This document is the normative wire protocol shared by the plugin, the group-ironmen server fork, and the group-ironmen site fork. The JSON fixtures under `src/test/resources/fixtures/sync/v1/` are the source of truth for the other repositories. Change both together or neither.

It supersedes the "Canonical per-tag model", "Group manifest model", "API plan for groupiron.men", "Server storage plan", and "Local sync metadata" sections of `docs/remote-sync-plan.md`.

All routes live under the existing authenticated scope `/api/group/{group_name}` and use the existing header `Authorization: <group token>` (raw token, no `Bearer`). `{tag_id}` is a lowercase UUID v4 string minted by the client; the server never generates tag IDs.

#### Documents

##### Tag document

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

Field rules (server-enforced unless noted):

| Field | Rule |
| --- | --- |
| `schemaVersion` | must be `1`; anything else → `400 unsupported_schema` |
| `tagId` | lowercase UUID v4; in a request body it is optional and, if present, must equal the path id → else `400 invalid_tag_id` |
| `name` | 1–50 chars after trimming; lowercase (client sends `Text.standardize`d names; server lowercases + trims again); must not contain any of `<` `/` `>` `:` (the plugin's `TabInterface.FILTERED_CHARS`) or `,` (breaks the plugin's CSV storage) → else `400 invalid_tag`; unique among non-deleted tags in the group → else `409 duplicate_name` |
| `iconItemId` | integer ≥ 0 |
| `itemIds` | array, 0–4000 entries, sorted ascending, no duplicates, no `0`; negative values are RuneLite variation IDs and are passed through untouched |
| `layout` | `null` or array of 0–4000 integers, each `-1` (empty slot) or `> 0`. Membership of layout items in the tag's effective item set is a **client-side** rule (variation expansion needs RuneLite's `ItemVariationMapping`); the server does not check it |
| `revision` | server-issued, starts at `1` on create, `+1` on every accepted PUT/DELETE of that tag; ignored in request bodies |
| `deleted` | server-managed; ignored in request bodies |
| `updatedAt` | server-managed RFC 3339 UTC; informational only, never used for conflict decisions |

Whole request body ≤ 100 000 bytes (the existing actix `JsonConfig` limit) → else `413 payload_too_large`.

##### Manifest

```json
{
  "schemaVersion": 1,
  "groupRevision": 42,
  "orderRevision": 3,
  "orderedTagIds": ["5e4a8e36-e5f4-4daa-ae7a-e510f3e66721"],
  "tags": [
    { "tagId": "5e4a8e36-e5f4-4daa-ae7a-e510f3e66721", "name": "herblore", "revision": 7, "deleted": false }
  ]
}
```

- `groupRevision` increments on **every** accepted write in the group (tag create/update/delete and order update), in the same transaction.
- `orderRevision` increments only when `orderedTagIds` changes: on an order update, on tag create (the new id is appended), and on tag delete (the id is removed).
- `tags` lists every tag including tombstones (`deleted: true`) not yet purged (retention 90 days).
- `orderedTagIds` contains exactly the non-deleted tag ids, each once.

##### Error body

Every 4xx from these routes is JSON:

```json
{ "error": "stale_revision", "message": "human readable", "current": { "tagId": "…", "name": "…", "revision": 8, "deleted": false } }
```

`error` is one of: `unsupported_schema`, `invalid_tag_id`, `invalid_tag`, `invalid_order`, `duplicate_name`, `tag_exists`, `tag_not_found`, `stale_revision`, `precondition_required`, `payload_too_large`. `current` is present on `409` only: tag metadata (`tagId,name,revision,deleted`) for tag routes, the full manifest for the order route. Never echo the token or the request body.

#### Concurrency: HTTP conditional headers only

ETags are the decimal revision in quotes, e.g. `ETag: "7"`. No `expectedRevision` body fields anywhere.

| Method | Path | Request headers | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/bank-tags` | optional `If-None-Match: "<groupRevision>"` | `200` manifest + `ETag: "<groupRevision>"`; `304` empty when unchanged | `401` |
| `GET` | `/bank-tags/{tag_id}` | — | `200` tag doc (tombstones return `deleted: true`, empty `itemIds`, `layout: null`) + `ETag: "<revision>"` | `400 invalid_tag_id`, `404 tag_not_found` |
| `PUT` | `/bank-tags/{tag_id}` | `If-None-Match: *` (create) **or** `If-Match: "<revision>"` (update); body = tag doc | `201` (create) / `200` (update), body = stored doc, `ETag` | `428 precondition_required` (neither header), `409 tag_exists` (create, id exists — including tombstoned), `409 stale_revision`, `409 duplicate_name`, `404 tag_not_found` (update of unknown id), `400 invalid_tag`, `413` |
| `DELETE` | `/bank-tags/{tag_id}` | `If-Match: "<revision>"` | `200` body = tombstoned doc | `428`, `404`, `409 stale_revision`. Deleting an already-deleted tag with a matching `If-Match` is `200` (idempotent) |
| `PUT` | `/bank-tag-order` | `If-Match: "<orderRevision>"`; body `{ "schemaVersion": 1, "orderedTagIds": [...] }` | `200` body = manifest | `428`, `409 stale_revision` (`current` = manifest), `400 invalid_order` (unknown, duplicate, deleted, or missing ids — the list must be a permutation of the current non-deleted ids) |

The path is `/bank-tag-order` (not `/bank-tags/order`) so it cannot collide with `/bank-tags/{tag_id}`.

#### Folder extension (v1)

Folders are an independent synchronized subprotocol. They do not change the frozen tag document or `/bank-tags` manifest. A folder is one level deep and contains an ordered list of stable tag UUIDs. A live tag may belong to at most one live folder. Tags omitted from every folder are unfiled.

##### Folder document

```json
{
  "schemaVersion": 1,
  "folderId": "3f8c3ed2-6e1e-4b86-93b1-f7f874f17290",
  "name": "Minigames",
  "iconItemId": 952,
  "orderedTagIds": ["5e4a8e36-e5f4-4daa-ae7a-e510f3e66721"],
  "revision": 3,
  "deleted": false,
  "updatedAt": "2026-09-10T18:00:00Z"
}
```

- `folderId` is a lowercase UUID v4 minted by the client.
- `name` contains 1 to 50 characters after trimming.
- `iconItemId` is a nonnegative integer. Zero means the client may display a child tag icon as a fallback.
- `orderedTagIds` contains unique live tag UUIDs in display order.
- `revision`, `deleted`, and `updatedAt` are server-managed as for tag documents.
- Deleting a folder tombstones only the folder. Its children remain live and become unfiled.
- Deleting a tag removes its ID from its owning folder and advances that folder's revision.

##### Folder manifest

```json
{
  "schemaVersion": 1,
  "groupRevision": 51,
  "orderRevision": 4,
  "orderedFolderIds": ["3f8c3ed2-6e1e-4b86-93b1-f7f874f17290"],
  "folders": [
    { "folderId": "3f8c3ed2-6e1e-4b86-93b1-f7f874f17290", "name": "Minigames", "revision": 3, "deleted": false }
  ]
}
```

Folder group and order revisions are independent from tag manifest revisions. `orderedFolderIds` contains each live folder exactly once. `folders` includes retained tombstones.

| Method | Path | Request headers | Success |
| --- | --- | --- | --- |
| `GET` | `/bank-tag-folders` | optional `If-None-Match: "<groupRevision>"` | `200` folder manifest, or `304` when unchanged |
| `GET` | `/bank-tag-folders/{folder_id}` | none | `200` folder document |
| `PUT` | `/bank-tag-folders/{folder_id}` | `If-None-Match: *` for create, or `If-Match: "<revision>"` for update | `201` or `200` folder document |
| `DELETE` | `/bank-tag-folders/{folder_id}` | `If-Match: "<revision>"` | `200` tombstoned folder document |
| `PUT` | `/bank-folder-order` | `If-Match: "<orderRevision>"` | `200` folder manifest |

The folder order request body is `{ "schemaVersion": 1, "orderedFolderIds": [...] }`. Conditional-write and error semantics match the tag routes. Folder state is stored separately in SQLite by the private group-ironmen server. The browser and RuneLite keep expanded or collapsed state locally and never send it.

#### Server storage

Both tables in the existing `groupironman` schema, added as two named blocks in `db::update_schema` (`has_migration_run` / `commit_migration`), names `create_bank_tags_table` and `create_bank_tag_groups_table`.

```sql
CREATE TABLE IF NOT EXISTS groupironman.bank_tags (
  tag_id UUID PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES groupironman.groups(group_id),
  name TEXT NOT NULL,
  icon_item_id INTEGER NOT NULL,
  item_ids INTEGER[] NOT NULL,
  layout INTEGER[] NULL,
  revision BIGINT NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS bank_tags_group_idx ON groupironman.bank_tags (group_id);
CREATE UNIQUE INDEX IF NOT EXISTS bank_tags_group_live_name_idx
  ON groupironman.bank_tags (group_id, name) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS groupironman.bank_tag_groups (
  group_id BIGINT PRIMARY KEY REFERENCES groupironman.groups(group_id),
  group_revision BIGINT NOT NULL DEFAULT 0,
  order_revision BIGINT NOT NULL DEFAULT 0,
  ordered_tag_ids UUID[] NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL
);
```

Every write: one transaction that `INSERT ... ON CONFLICT (group_id) DO NOTHING` the `bank_tag_groups` row, then `SELECT ... FOR UPDATE` it (serializes writers per group), validates revisions, mutates, bumps `group_revision` (and `order_revision` when the order list changed), commits. Tombstone = `deleted_at = now()`, `item_ids = '{}'`, `layout = NULL`, `revision + 1`, id removed from `ordered_tag_ids`. Purge rows with `deleted_at < now() - interval '90 days'` (Milestone 5).

#### Client-side (plugin) local metadata

All in the `emyrk-bank-tags-sync` config group. Keys are reserved with a `sync` prefix so they cannot collide with `item_`, `icon_`, `layout_`, `hidden_`, `tagtabs`.

| Key | Value |
| --- | --- |
| `syncGroupRevision` | last applied `groupRevision` (long) |
| `syncOrderRevision` | last applied `orderRevision` (long) |
| `syncTag_<tagId>` | JSON `{ "name": "herblore", "revision": 7, "baseHash": "<sha256 hex>" }` — `name` is the current local standardized name; `revision` the last remote revision this client has applied or received on write; `baseHash` the `SharedBankTag.contentHash()` of that synced state |
| `syncPendingDelete_<tagId>` | JSON `{ "revision": 7 }` — a local delete not yet acknowledged by the server |
| `syncConflict_<tagId>` | JSON `{ "remote": <tag doc>, "reason": "stale_revision" \| "remote_changed" \| "duplicate_name" \| "tag_exists" }` — present only while the tag is conflicted |

`contentHash` is client-local. It is never sent to the server and never compared across implementations.

Dirty test for a tag: `snapshot(name).contentHash() != syncTag_<id>.baseHash`.

Decision table on a manifest poll, per tag id in the manifest:

| Remote vs stored revision | Local dirty? | Action |
| --- | --- | --- |
| equal | — | nothing |
| newer, `deleted: false` | clean | `GET` tag, `apply`, update `syncTag_` (`revision`, `baseHash`) |
| newer, `deleted: false` | dirty | write `syncConflict_` with `reason: remote_changed`; do not touch local data |
| newer, `deleted: true` | clean | `delete` locally, remove `syncTag_`, `syncPendingDelete_` |
| newer, `deleted: true` | dirty | conflict, `reason: remote_changed` |
| id unknown locally, `deleted: false` | — | `GET` tag, `apply` as a new tab (if a local tab with the same name exists and has no `syncTag_`, conflict `reason: duplicate_name`) |
| id unknown locally, `deleted: true` | — | nothing |

Decision table on a write response:

| Response | Action |
| --- | --- |
| `200`/`201` | store returned `revision`, recompute `baseHash` from the *local* snapshot that was sent, clear `syncPendingDelete_`/`syncConflict_` |
| `409 stale_revision` | write `syncConflict_` from `current` (fetch the full doc first), `reason: stale_revision` |
| `409 duplicate_name` / `409 tag_exists` | conflict with that reason |
| `404 tag_not_found` on update | treat as remote delete of that id: conflict `reason: remote_changed` with `remote.deleted = true` |
| `400`/`413`/`428` | log at debug (class only), mark tag `error`, do not retry until the tag changes again |
| `401` | stop polling, status `invalid credentials`, no retries until config changes |
| `5xx`/IO | keep dirty, exponential backoff (poll interval × 2^n, capped at 5 min, reset on success) |

Conflict recovery actions: `Use remote version` (apply `syncConflict_.remote`, clear conflict, store its revision) and `Overwrite remote version` (confirm, then `PUT` with `If-Match: "<current remote revision>"` from the conflict record; when the remote is a tombstone the client does not resurrect the id but mints a fresh `tagId` and creates it with `If-None-Match: *`, forgetting the old id). `Retry sync` re-runs the pending write for that tag with its stored revision and triggers an immediate manifest poll.

First enable (no `syncStorageInitialized` marker): `GET /bank-tags`. On `401` stop with status `invalid credentials`. If the manifest has any non-deleted tag: fetch each, apply into the sync namespace, write metadata, set the marker, then reload the bank UI. Otherwise: `initializeSyncStorageFromLocal()`, set the marker, mint a UUID per local tab, write `syncTag_` with `revision: 0` and `baseHash: ""`, and enqueue a create for each. Either way `emyrk-bank-tags` and `banktags` are never written.
