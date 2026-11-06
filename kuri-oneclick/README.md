# kuri-oneclick — compiled-in RuneLite plugin (official Gradle source workflow)

`Kuri One-Click Location`: a 1:1 click at a fixed world tile. The plugin
injects ONE real-action context-menu entry (`MenuAction.WALK` at the target's
scene coordinates). Clicking it makes the client perform the ordinary
walk-here exactly as if you had clicked the tile by hand — one click in, one
ordinary game action out, nothing synthetic follows. The engine only
validates at click time and observes the server's reaction afterwards.

## Why this layout

The previous attempt (`.runelite/sideloaded-plugins` + `--developer-mode`)
could never work reliably:

- `RuneLite.java:238` gates developer mode:
  `developerMode = options.has("developer-mode") && RuneLiteProperties.getLauncherVersion() == null`
  — the standard launcher always sets `runelite.launcher.version`, which
  silently disables side-loading.
- Sideloading also scans jar files only, from a single directory.

This project removes sideloading from the equation entirely: the plugin is a
Gradle project **compiled into the client's runtime classpath**. Discovery
then comes from the core scan in `PluginManager.loadCorePlugins()`:

```java
ClassPath.from(getClass().getClassLoader())
    .getTopLevelClassesRecursive(PLUGIN_PACKAGE)   // "net.runelite.client.plugins"
```

which is why this plugin's package is `net.runelite.client.plugins.kuri.oneclick`
— anything compiled onto the classpath under that package is a first-class
built-in plugin. No `--developer-mode` dependence, no launcher gate, no jars.

## Layout

```
kuri-plugin-dev/
├── runelite/                      ← official repository (shallow clone)
│   ├── settings.gradle.kts        ← EDITED: includes ../kuri-oneclick
│   └── runelite-client/build.gradle.kts ← EDITED: implementation(project(":kuri-oneclick")) + run task
└── kuri-oneclick/                 ← THIS project (outside the repo)
    └── src/main/java/net/runelite/client/plugins/kuri/oneclick/
        ├── KuriOneClickPlugin.java      wiring + all @Subscribe hooks (thin)
        ├── KuriOneClickConfig.java      @ConfigGroup interface (runtime-proxied)
        ├── engine/                      state machine — NO RuneLite imports
        │   ├── OneClickEngine.java      IDLE → ARMED → (observe) → IDLE
        │   ├── ClickState.java
        │   ├── ConfirmResult.java       PENDING / CONFIRMED / ABORTED
        │   ├── BoundedAction.java       name() + confirm(now, armedAt)
        │   └── PlayerSnapshot.java      plain x/y/plane value
        ├── world/                       coordinate validation
        │   ├── RsCoords.java            region/chunk arithmetic
        │   └── TileCoordinateValidator.java  world→scene, bounds, tile presence
        ├── menu/                        all menu-array manipulation
        │   └── WalkMenuEntries.java     inject / dedup / match / pin
        ├── location/
        │   └── WalkToTileAction.java    the concrete bounded action
        └── overlay/
            └── LocationClickOverlay.java  OverlayPanel status
```

Separation of concerns is strict: menu hooks translate RuneLite events into
layer calls; the world layer validates coordinates; the engine layer owns
every state transition. The engine package imports nothing from RuneLite, so
it is unit-testable with hand-built snapshots.

## Exact wiring lines

`runelite/settings.gradle.kts` (after the `client` include):

```kotlin
include("kuri-oneclick")
project(":kuri-oneclick").projectDir = file("../kuri-oneclick")
```

`runelite/runelite-client/build.gradle.kts`, inside `dependencies { }`:

```kotlin
implementation(project(":kuri-oneclick"))
```

and a run task (this tree no longer has the old application-plugin `run`):

```kotlin
tasks.register<JavaExec>("run") {
    group = "runelite"
    mainClass = "net.runelite.client.RuneLite"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = providers.gradleProperty("clientRunHeap").getOrElse("1g")

    // Mirror the JVM arguments the official launcher passes to the client.
    // -ea is REQUIRED for --developer-mode: RuneLite.main refuses to start in
    // developer mode without assertions enabled.
    jvmArgs(
        "-ea",
        "-Xss2m",
        "-XX:CompileThreshold=1500",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "-Dsun.java2d.d3d=true",
        "-Dsun.java2d.opengl=false",
    )
}
```

The `runtimeOnly` + `compileOnly` pairing keeps the Gradle task graph
acyclic: `:client` puts the plugin's classes on its RUNTIME classpath (the
`run` JavaExec and the shadow jar), while the plugin compiles against
`:client` one-way with `compileOnly`. Making the client edge
`implementation` instead produces a hard Gradle failure
(`:client:compileJava -> :kuri-oneclick:jar -> :client:compileJava`).

## Build & run

From `kuri-plugin-dev/runelite` (any shell; `gradlew.bat` on cmd/PowerShell):

```bash
./gradlew :client:compileJava                 # fast check: client compiles
./gradlew :kuri-oneclick:compileJava          # fast check: plugin compiles
./gradlew :client:run --args="--debug --developer-mode"
```

`--debug` = extra client logging (DEBUG lines in `~/.runelite/logs/client.log`);
`--developer-mode` is harmless here (both flags exist in `RuneLite.main`;
running from source there is no `runelite.launcher.version`, so they always
take effect). The `-ea` assertion requirement for developer mode is satisfied
by the run task's `jvmArgs`. Heap override: `-PclientRunHeap=4g`.

## Modern-API notes (things that changed in this RuneLite tree)

- **Events** live in `net.runelite.api.events.*` (not `net.runelite.client.events`).
- **Config binding**: `Plugin.configure(Binder)` is empty now; each plugin
  provides its config via a `@Provides` method (the plugin class is installed
  as a Guice module by `PluginModuleFactory`) — see `KuriOneClickPlugin#getConfig`,
  same pattern as `AgilityPlugin#getConfig`.
- **Menu API**: `client.getMenu().createMenuEntry(-1)`; `MenuAction.WALK` takes
  scene coordinates in `param0`/`param1`.
- **World access**: `client.getTopLevelWorldView()` is preferred;
  `client.getScene()` is deprecated.

First run in the client: options panel → search "Kuri" → enable
**Kuri One-Click Location**. The overlay (bottom-left) shows state, target
identity (region 11157 / chunk 717996 for the default tile), and why the
entry is absent when a gate withholds it.

## IntelliJ

1. File → Open → `kuri-plugin-dev/runelite` (open the repository root — the
   Gradle project is rooted there; `kuri-oneclick` joins as module
   `:kuri-oneclick` through the settings include).
2. Trust + let Gradle sync finish (first sync downloads dependencies).
3. Run configuration **"Client (dev) --debug --developer-mode"** is
   pre-provisioned (`.idea/runConfigurations/`); press ▶ to run, 🐞 to debug
   with breakpoints anywhere in `kuri-oneclick` or the client itself.
   Equivalent terminal debug: `./gradlew :client:run --debug-jvm` then
   attach a Remote JVM Debug configuration to localhost:5005.
4. Set Project SDK to a JDK 11+ (17 recommended).

## Keeping the repository fresh

The clone is shallow (`--depth 1`). To update:

```bash
cd runelite
git pull --unshallow    # or: git fetch --depth 1 && git reset --hard origin/master
```

After major repository updates, re-check the two edited files (upstream
restructures its build occasionally — e.g. this tree moved settings.gradle to
Kotlin DSL and split runelite-api into a composite included build) and re-copy
`runelite/config/checkstyle/*` into `../config/checkstyle/` if the config
changed.
