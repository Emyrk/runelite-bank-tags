# AGENTS.md

Guidance for agents and contributors working on Bank Tags Extended.

## Project purpose

This repository is a standalone RuneLite plugin fork of the built-in Bank Tags plugin. It currently keeps tag data in RuneLite configuration under `emyrk-bank-tags`. The next major feature is optional synchronization of bank tags, tag tabs, icons, and layouts through a central server so members of one Group Ironman group can share the same organization.

Read these before changing behavior:

- `README.md`
- `docs/architecture.md`
- `docs/sync-design.md` for the planned synchronization boundary and unresolved decisions
- `docs/remote-sync-plan.md` for the proposed per-tag implementation sequence

## Repository map

- `src/main/java/com/emyrk/banktags/BankTagsPlugin.java`: plugin lifecycle, built-in config migration, bank search integration, and item tag editing.
- `src/main/java/com/emyrk/banktags/TagManager.java`: item-to-tag persistence and tag rename/remove operations.
- `src/main/java/com/emyrk/banktags/tabs/TabManager.java`: ordered tabs and tab icon persistence.
- `src/main/java/com/emyrk/banktags/tabs/TabInterface.java`: bank UI, imports/exports, tab operations, and most user mutation entry points.
- `src/main/java/com/emyrk/banktags/tabs/LayoutManager.java`: layout persistence and bank widget layout behavior.
- `src/main/java/com/emyrk/banktags/tabs/Layout.java`: in-memory ordered item layout, with `-1` representing an empty slot.
- `src/test/java/com/emyrk/banktags/`: unit tests and the development client launcher.

## Build and validation

Use Java 11 compatible code.

```sh
./gradlew test
./gradlew run
```

Run `./gradlew test` after every code change. Add focused tests for serialization, merge/conflict behavior, remote update handling, and feedback-loop prevention when sync code is introduced.

A successful build does not verify in-game behavior. Do not automate RuneScape input. Offer to run `./gradlew run`, then ask the user to test the changed behavior in the development client.

## Persistence contract

The current local source of truth is RuneLite `ConfigManager` in group `emyrk-bank-tags`:

- `item_<id>`: CSV tag names for an item. Negative IDs represent variation tags.
- `tagtabs`: CSV ordered tab names.
- `icon_<tag>`: item ID used as the tab icon.
- `layout_<tag>`: CSV item IDs by slot, with `-1` for empty slots.
- `hidden_<tag>`: hidden tag marker.
- `migratedFromBuiltin`: one-time import marker for the built-in `banktags` group.
- `useTabs`, `rememberTab`, `removeTabSeparators`, `preventTagTabDrags`, `position`, and `tab`: plugin and local UI preferences.

Do not rename the config group or keys without a migration. Preserve tag standardization through RuneLite `Text.standardize`, `Text.fromCSV`, and `Text.toCSV` where the existing code does so.

## Sync implementation rules

- Keep networking outside `TabInterface`, `TagManager`, `TabManager`, and `LayoutManager`. Introduce a small sync boundary so UI and persistence code do not depend directly on HTTP payloads.
- Route synced writes through the same domain mutation path as local writes. Do not scatter direct `ConfigManager` writes across a network callback.
- Preserve a local synchronized cache so tags remain usable while offline or when the server is unavailable.
- Remote sync must use `emyrk-bank-tags-sync` for synchronized data and `emyrk-bank-tags-sync-settings` for connection preferences. Never write remote values into the built-in `banktags` group or the existing `emyrk-bank-tags` local data.
- Once enabled, game-side synchronization is automatic per tag. Debounce compound mutations and upload only their completed state. Do not require routine manual save or retrieve actions.
- Never perform blocking network or disk I/O on the RuneLite client thread. Use injected `OkHttpClient` with asynchronous requests. Use `clientThread.invoke()` before touching RuneLite client state or bank widgets from a callback.
- Use injected `Gson`. Do not instantiate a separate JSON stack or add transitive RuneLite dependencies directly to `build.gradle`.
- Synchronization must be opt-in and disabled by default. Any config item enabling the third-party server must include this exact warning:
  `This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers`
- Do not send bank contents, player location, equipment, credentials, session tokens from RuneLite, or data about unrelated players. The intended payload is shared tag metadata only.
- Do not log secrets, authorization values, complete payloads, or personal identifiers. Use `log.debug()` for diagnostics.
- Design remote application and local observation so a remote update cannot be uploaded again as a new local update. Tests must cover this feedback-loop case.
- Do not silently overwrite divergent local and remote data. The conflict policy, group identity, authentication model, and deletion semantics must be explicitly chosen and documented before implementation.
- Treat protocol payloads as versioned. Reject or safely ignore unsupported schema versions.
- Keep local-only UI preferences out of the shared document unless the design explicitly changes this. The expected shared candidates are item tags, ordered tabs, icons, and layouts. See `docs/sync-design.md`.
- The wire protocol is `docs/remote-sync-protocol.md`; fixtures under `src/test/resources/fixtures/sync/v1/` are the source of truth for the server and site repos. Change both together or neither.

## RuneLite constraints

- Use RuneLite gameval constants instead of magic widget, item, object, or interface IDs.
- Do not use reflection, native access, external processes, dynamic code loading, Java serialization, or input injection.
- Use `LinkBrowser` for URLs.
- Keep event and frame handlers lightweight.
- Register listeners and sprite overrides in `startUp()`. Remove them in `shutDown()`.
- Do not block startup or shutdown. Explicitly cancel future scheduled tasks and use `shutdownNow()` for owned executors.
- Preserve the upstream copyright headers.
- Do not mix broad formatting changes with feature work.
- Do not commit build output, credentials, endpoint secrets, or local account data.

## Change discipline

1. Read the relevant manager and all of its callers before changing persistence.
2. Update `docs/architecture.md` when ownership or data flow changes.
3. Update `docs/sync-design.md` when a protocol or conflict decision is made.
4. Add or update tests before claiming the code is ready.
5. Run `./gradlew test` and report the exact result.
6. Ask the user to verify the behavior in-game through the development client.
