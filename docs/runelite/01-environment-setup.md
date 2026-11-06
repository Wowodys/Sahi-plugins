# 01 — Environment Setup: JDK, Gradle, Versions, Distribution

> Two ways to build are supported in this folder:
> **(A) offline local-jars build** — used by `templates/skeleton-plugin`,
> verified working on this machine — and **(B) the official plugin-hub
> pipeline** — what you need when publishing. Versions below verified
> 2026-11-03 against `repo.runelite.net` and the plugin-hub repo.

## 1. Versions (researched 2026-11-03)

| Artifact | Version | Source |
|---|---|---|
| `net.runelite:runelite-api` (maven) | **1.13.6** (release; 1.13.7-SNAPSHOT on master) | https://repo.runelite.net/net/runelite/runelite-api/maven-metadata.xml |
| `net.runelite:client` (maven) | **1.13.6** | https://repo.runelite.net/net/runelite/client/maven-metadata.xml |
| Release cadence | 1.13.4 = 2026-10-24, 1.13.5 = 2026-11-01, 1.13.6 = 2026-11-03 | jar Last-Modified headers |
| plugin-hub build pin | **1.13.6** | https://raw.githubusercontent.com/runelite/plugin-hub/master/runelite.version |
| Java target | **11** (`options.release.set(11)`) — class files of published `runelite-api`/`client` 1.13.6 are major 55 (byte-verified locally too) | https://raw.githubusercontent.com/runelite/example-plugin/master/build.gradle |
| Gradle (official template) | wrapper **8.10** pinned (`gradle-8.10-all.zip` + sha256) | example-plugin `gradle/wrapper/gradle-wrapper.properties` |
| Lombok (official template) | 1.18.30 (compileOnly + annotationProcessor) | example-plugin `build.gradle` |

The local install matches: `~/.runelite/repository2/` holds
`runelite-api-1.13.6-runtime.jar` + `client-1.13.6.jar` + bundled libs
(guice 4.1.0-no_aop, guava 23.2-jre, slf4j-api 1.7.25, okhttp 3.14.9,
gson 2.8.5, http-api 1.2.26). `sdk/jars/versions.txt` has the full list.

JDKs on this machine — see `sdk/jdk-notes.md` for the full matrix:
- Temurin **17.0.19 JRE** at `C:\Users\sahij\AppData\Local\RuneLite\jre` — the
  client's runtime, and the JVM to run Gradle 9.1 with;
- Temurin **11.0.28 JDK** at `C:\Program Files\Eclipse Adoptium\jdk-11.0.28.6-hotspot`
  — the compile toolchain (Java 11 target);
- Gradle **9.1.0** on PATH (needs the 17 JRE to run; compiles Java 11 via toolchain).

## 2. Option A — offline build against the local jars (what the template does)

`templates/skeleton-plugin/build.gradle` (working, verified — `gradle build`
succeeds with zero network):

```gradle
plugins { id 'java' }

def sdkJars = file('../../sdk/jars')

java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = 11 }

dependencies {
    compileOnly fileTree(dir: sdkJars, include: ['*.jar'])  // api + client + libs; kuri/ subdir excluded
}

jar { manifest.attributes('RuneLite-Plugin': 'com.example.template.TemplatePlugin') }
```

plus `src/main/resources/runelite_plugin.json`:
`{"displayName": "...", "plugins": ["com.example.template.TemplatePlugin"]}`.

Build and install:

```bash
export JAVA_HOME="C:\Users\sahij\AppData\Local\RuneLite\jre"   # Gradle runs on 17
cd templates/skeleton-plugin
gradle build --no-daemon
# → build/libs/template-plugin-1.0.0.jar
cp build/libs/template-plugin-1.0.0.jar "C:\Users\sahij\.runelite\plugins\"
# restart the client; enable "Template" in the plugin config panel
```

Facts encoded here (learned the hard way, documented in `sdk/jdk-notes.md`):
Gradle 9.1 cannot run on Java 11; the bundled 17 is a JRE with no `javac`
(the build fails with "does not provide the required capabilities:
[JAVA_COMPILER]"); hence run-17 + toolchain-11. Use `--no-daemon` in
restricted environments.

## 3. Option B — the official plugin-hub pipeline

The upstream template (`github.com/runelite/example-plugin`, master) resolves
from the network instead:

```gradle
repositories {
    mavenLocal()
    maven { url = 'https://repo.runelite.net'; content { includeGroupByRegex("net\\.runelite.*") } }
    mavenCentral()
}
def runeLiteVersion = 'latest.release'    // plugin-hub README: MUST be 'latest.release'
dependencies {
    compileOnly group: 'net.runelite', name: 'client', version: runeLiteVersion  // transitively includes runelite-api
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
}
tasks.withType(JavaCompile).configureEach { options.release.set(11) }
```

Notes that differ from older folklore (all from the current sources — see the
URL table below):

- There is **no `runelite.version` ext property anymore**, and no
  `RuneLite-Plugin` manifest attribute in the official pipeline — plugin class
  names travel in `runelite_plugin.json` inside the jar (generated from the
  repo-root `runelite-plugin.properties` file: `displayName=, author=,
  description=, tags=, version=, plugins=com.example.ExamplePlugin, build=standard`).
- Guice comes transitively from the client artifact (4.1.0, javax annotations).
- The template's `run` task boots the real client with your plugin via
  `ExternalPluginManager.loadBuiltin(ExamplePlugin.class)` + `--developer-mode`
  — handy for testing without copying jars around (needs the test harness
  deps: `net.runelite:client` + `net.runelite:jshell` in testImplementation).

### Publishing to Plugin Hub (README quotes, master)

1. Repo layout: one file per plugin under `plugins/` containing exactly
   `repository=<https git url>` and `commit=<full 40-hex hash>`; submit as a
   new branch + PR to `runelite/plugin-hub`. Updates = bump `commit=`.
2. Icon: `icon.png` ≤ 48×72 px at the repo root.
3. License: BSD 2-Clause recommended.
4. CI builds with the packager's `standard-build.gradle` (pinned 1.13.6,
   lombok 1.18.30, release 11) — your own `build.gradle` is **replaced** in
   `build=standard` mode.
5. Automated checks: dependencies need Gradle verification metadata
   (`--write-verification-metadata sha256`); a bytecode scan against
   `disallowed-apis.txt` rejects e.g. `new OkHttpClient(...)`, `new Gson(...)`
   (inject the client's), `WidgetInfo`/`WidgetID` (use `ComponentID`/`InterfaceID`),
   `ChatMessageManager.update(MessageNode)`, and any
   `AccountClient`/`AccountSession`/`SessionManager` access.
6. Review policy (README): *"We will review your plugin to ensure it isn't
   malicious, doesn't break Jagex's rules, or isn't one of our previously
   Rejected/Rolled-Back features. If it is difficult for us to ensure the
   plugin isn't against the rules we will not merge it."*

## 4. When to rebuild for a new client version

Releases land every few days and the API occasionally moves (the WorldView
refactor in 1.11.0 and the gameval migration in 2025 are the big recent ones).
You no longer do this by hand — §5's pipeline automates it. The manual
procedure remains the mental model: compare `~/.runelite/repository2/` with
`sdk/jars/`, compile against the newest jars, fix whatever javac flags.

## 5. SDK drift pipeline (detect → verify → fix → signal → block)

`tools/drift/` automates §4. See **`tools/drift/README.md`** for the full
design; the operating summary:

```bash
cd /c/Users/sahij/Documents/kuri-plugin-dev
python tools/drift/check_drift.py --quick   # daily signal: version/sha scan vs the live repo
python tools/drift/check_drift.py --apply   # full: canary-compile vs newest jars, then auto-sync + re-pin
```

- If the newest jars still compile the template, the pipeline **re-pins
  `sdk/jars` automatically** (removing superseded versions and regenerating
  `versions.txt`).
- If the canary **fails** (API changed), it writes `DRIFT-REPORT.md` plus
  `api-diff.md` — a javap signature diff (removed/added classes, changed
  members) between the pinned and newest jar — and sets pin status
  `needs_human`. From there a human or agent fixes the template/docs against
  the diff, reruns `--apply`, and the state returns to ok.
- The **Gradle guard** (`checkSdkPin` task in the template's `build.gradle`)
  fails any build while drift is unresolved, so a plugin jar can never be
  produced against a stale SDK. `-PallowStaleSdk=true` builds anyway, loudly.
- Exit codes (for scheduling): 0 in-sync · 1 auto-fixed · 2 needs human ·
  3 blocked · 4 environment error.

## 6. Source URL index

- example-plugin build.gradle: https://raw.githubusercontent.com/runelite/example-plugin/master/build.gradle
- plugin-hub README: https://raw.githubusercontent.com/runelite/plugin-hub/master/README.md
- plugin-hub version pin: https://raw.githubusercontent.com/runelite/plugin-hub/master/runelite.version
- packager (standard build, disallowed APIs): https://github.com/runelite/plugin-hub-tooling
- maven metadata: https://repo.runelite.net/net/runelite/runelite-api/maven-metadata.xml
- javadocs (published 1.13.6): https://static.runelite.net/runelite-api/apidocs/ and https://static.runelite.net/runelite-client/apidocs/
- main repo: https://github.com/runelite/runelite
- (Note: `https://runelite.net/api/...` javadoc URLs 404 — use `static.runelite.net`; release-notes blog posts stop at 1.10.27, so maven metadata is the version authority.)
