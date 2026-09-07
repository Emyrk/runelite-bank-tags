# RuneLite Bank Tags Extended

A standalone fork of RuneLite's built-in **Bank Tags** plugin. This project is intended as a development base for extending bank tags and eventually submitting the resulting plugin to the RuneLite Plugin Hub.

## Current behavior

The initial implementation tracks RuneLite's built-in Bank Tags plugin and uses a separate Java package so it can be loaded as an external plugin. On first startup, it copies existing tags, tab order, icons, layouts, and settings from RuneLite's built-in `banktags` configuration into its own `emyrk-bank-tags` configuration group.

**Disable RuneLite's built-in Bank Tags plugin before enabling Bank Tags Extended.** Running both plugins together is unsupported because both plugins modify the same bank interface. The fork uses its own configuration after the one-time import.

## Development

This project requires Java 11 or newer to run Gradle and compiles plugin code for Java 11.

```sh
./gradlew test
./gradlew run
```

The `run` task starts a development RuneLite client with this plugin loaded. Logging into a Jagex account requires the setup described in RuneLite's "Using Jagex Accounts" wiki page.

## Upstream source

The fork was initially ported from RuneLite release `1.12.38`, revision `b505980edd4576104368874d4597bca7b7c463a1`:

```text
runelite-client/src/main/java/net/runelite/client/plugins/banktags
```

Original copyright notices are retained in the source files. This repository is distributed under the BSD 2-Clause License. See [LICENSE](LICENSE).
