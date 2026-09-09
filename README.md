# RuneLite Bank Tags Extended

A standalone fork of RuneLite's built-in **Bank Tags** plugin. This project is intended as a development base for extending bank tags and eventually submitting the resulting plugin to the RuneLite Plugin Hub.

## Current behavior

The initial implementation tracks RuneLite's built-in Bank Tags plugin and uses a separate Java package so it can be loaded as an external plugin. On first startup, it copies existing tags, tab order, icons, layouts, and settings from RuneLite's built-in `banktags` configuration into its own `emyrk-bank-tags` configuration group.

**Disable RuneLite's built-in Bank Tags plugin before enabling Bank Tags Extended.** Running both plugins together is unsupported because both plugins modify the same bank interface. The fork uses its own configuration after the one-time import.

## Development

This project requires Java 11 or newer to run Gradle. The build pins a Java 11 toolchain, so compiling, testing, and `./gradlew run` always use JDK 11; Gradle downloads one automatically if none is installed.

If Nix is installed, enter the development shell first:

```sh
nix-shell
```

The shell provides Java 11 and sets `JAVA_HOME`. Then use the Gradle wrapper:

```sh
./gradlew test
./gradlew run
```

The `run` task starts a development RuneLite client with this plugin loaded. Logging into a Jagex account requires the setup described in RuneLite's "Using Jagex Accounts" wiki page.

## Install (sideload)

Either download `runelite-bank-tags-<version>-all.jar` from this repo's
[GitHub Releases](../../releases) page, or build it yourself:

```sh
./gradlew shadowJar
# -> build/libs/runelite-bank-tags-0.0.0-dev-all.jar
```

Every push to `main` publishes a Release with that jar automatically. The
tag is a patch bump off the latest `vX.Y.Z` tag unless the squash-merge
subject (the PR title) contains `#minor`, `#major`, or `#skip-release`;
see `scripts/release-version.sh`. Pull requests only run `./gradlew test`.

**The launcher never loads it.** RuneLite only scans the
`sideloaded-plugins` folder when the client itself is started with
`--developer-mode` *and* without the launcher's own
`-Drunelite.launcher.version` property (`RuneLite.developerMode` is only
true when both hold), and both the Jagex Launcher and the RuneLite
launcher always set that property. A client launched through either one
therefore never reads the folder, and adding `--developer-mode` under
"Client arguments" in the launcher does not change that. To run a
sideloaded jar you must start the client without a launcher (see
"Sideload a release jar" below); for dev/testing, run the plugin from
source as described next.

### Dev/test: run the plugin from source

`./gradlew run` starts the full RuneLite client with the plugin loaded
from source, no sideloaded jar needed. A client started this way has no
launcher to hand it your Jagex account session, so dump the session to
disk once (needs RuneLite launcher 2.6.3+), following RuneLite's own
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
guide:

1. Open the launcher's configuration: run `RuneLite --configure`. On
   macOS that is `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`;
   on Windows use "RuneLite (Configure)" from the Start menu.
2. Add `--insecure-write-credentials` to **Client arguments** and
   **Save**.
3. Launch once through the Jagex Launcher: RuneLite writes the session to
   `~/.runelite/credentials.properties`.
4. Remove `--insecure-write-credentials` from **Client arguments** again.
5. Start the dev client:

   ```sh
   ./gradlew run
   ```

   It reads `~/.runelite/credentials.properties` and logs you in without
   a password. On macOS the `run` task already passes the same
   `--add-opens` and Java2D flags the official launcher uses, so you do
   **not** need a `JAVA_TOOL_OPTIONS` workaround.

**Delete `~/.runelite/credentials.properties` when you're done testing.**
The file logs into your account without a password. Never share it or
commit it anywhere. If one leaks, invalidate it with the "End sessions"
button under account settings on runescape.com.

### Sideload a release jar (client started directly)

To use a built jar instead of running from source, copy it into your
RuneLite sideloaded-plugins folder (create it if it doesn't exist):

- **Windows**: `%USERPROFILE%\.runelite\sideloaded-plugins`
- **macOS**: `~/.runelite/sideloaded-plugins`
- **Linux**: `~/.runelite/sideloaded-plugins`

Then start the client **directly** (not through a launcher), picking up
the jar:

```sh
java -jar RuneLite.jar --developer-mode
```

**Fully quit any running RuneLite** first (not just close the window;
use File > Exit / quit from the system tray, or kill the process) before
starting it, so it picks up the new jar.

Disable RuneLite's built-in Bank Tags plugin, then enable **Bank Tags
Extended** in the plugin list.

### Updating the Bolt development JAR

After changing the plugin, close its RuneLite window and run:

```sh
./scripts/update-bolt-dev.sh
```

The script builds the executable development JAR, using a temporary Nix JDK when no host JDK is available, and installs it at:

```text
~/.var/app/com.adamcake.Bolt/data/bolt-launcher/dev-jars/bank-tags-extended-dev.jar
```

Keep Bolt's custom RuneLite JAR pointed at that path, leave its custom RuneLite launch command blank, and relaunch RuneLite after each update.

## Upstream source

The fork was initially ported from RuneLite release `1.12.38`, revision `b505980edd4576104368874d4597bca7b7c463a1`:

```text
runelite-client/src/main/java/net/runelite/client/plugins/banktags
```

Original copyright notices are retained in the source files. This repository is distributed under the BSD 2-Clause License. See [LICENSE](LICENSE).

## Documentation

- [Architecture](docs/architecture.md)
- [Shared bank tag sync design notes](docs/sync-design.md)
- [Per-tag remote sync implementation plan](docs/remote-sync-plan.md)
