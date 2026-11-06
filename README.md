# kuri-plugin-dev — Local Development Environment for RuneLite "One-Click" Plugins

Self-contained folder for (a) building standalone RuneLite plugins in the
"one real click = one bounded, event-driven sequence" style, and (b) reverse
engineering more encrypted Kuri (kuriplugins.com) plugins in the future.
Built 2026-11-03 from the completed analysis in
`C:\Users\sahij\Documents\kuri-analysis\` plus web research on the current
RuneLite API/plugin-hub (sources cited inline in every doc).

**Nothing here is a stub** — every doc is substantive, the template compiles,
and every tool was executed once inside this folder (verification log at the
bottom).

---

## Folder map

```
kuri-plugin-dev/
├── README.md                        ← you are here
├── docs/
│   ├── reverse-engineering-kuri/    ← the "how Kuri works" corpus
│   │   ├── 01-kenc-container-format.md    KENC v1: AES-256-GCM layout, AAD, load-plan contract
│   │   ├── 02-key-capture-method.md       javaagent design: okhttp + KencFile hooks, bootstrap trick
│   │   ├── 03-string-deobfuscation.md     LCG-XOR scheme, layer-2 XOR, "run the classes" method
│   │   ├── 04-transport-and-capture.md    okhttp vs JDK HttpClient split, endpoints, heartbeat, rlicn
│   │   ├── 05-plugin-internals-tour.md    package layout, Menu facade, queries, obfuscation layers, port map
│   │   └── 06-tooling.md                  exact commands for every tool in tools/
│   ├── plugin-development/          ← the "how to build one" corpus
│   │   ├── 01-design-philosophy.md        THE RULES: one click, client UI layer, server-confirmed
│   │   │                                  progress, state hygiene, human-shaped input, fail visible
│   │   ├── 02-plugin-anatomy.md           lifecycle, Guice, event bus, config, key listeners
│   │   ├── 03-menu-and-widgets.md         menu injection, PostMenuSort, MenuAction id table, dialogs
│   │   ├── 04-interaction-patterns.md     build/remove loops, spawn tracking, inventory, NPC flows
│   │   ├── 05-overlay-and-stats.md        OverlayPanel, xp/hr math, timers, materials counter
│   │   └── 06-case-study-construction.md  the worked example, distilled
│   └── runelite/                    ← the API reference layer
│       ├── 01-environment-setup.md        JDK/Gradle/versions (offline build + official plugin-hub path)
│       ├── 02-api-cheatsheet.md           verified signatures + version-trap table
│       ├── 03-events-reference.md         event-by-event reference with delivery order
│       └── 04-gotchas.md                  client-thread rules, scene coords, menu timing, renames
├── sdk/
│   ├── jars/                        runelite-api-1.13.6-runtime.jar, client-1.13.6.jar,
│   │   │                            compile-time libs (see jars/versions.txt)
│   │   └── kuri/kuri-runelite-v1.7.64.jar   Kuri framework jar — ANALYSIS ONLY, do not distribute
│   ├── cfr.jar                      CFR 0.152 decompiler
│   └── jdk-notes.md                 which JDK for what (Gradle=17 JRE, compile=11 toolchain)
├── tools/
│   ├── kenc_decrypt.py              standalone KENC v1 decryptor (nonce-normalization fix applied)
│   ├── deob_strings.py              LCG-XOR string annotator (in-place; arg = source dir)
│   ├── ConstDump.java / EnumDump.java  constant recovery by running the classes (compiled once here)
│   ├── fs_watcher.py                file-system timeline watcher (arg = out.jsonl [dirs...])
│   ├── capture-agent/               the key/plaintext -javaagent (src + built jars + USAGE.md + smoke tests)
│   ├── drift/                       SDK drift pipeline: check_drift.py (detect→canary→auto-fix),
│   │                                api_diff.py (signature diff), sdk-pin.json (state read by the
│   │                                Gradle guard), DRIFT-REPORT.md, README.md (design + tests)
│   └── testdata/                    test-container.kenc + test-container.info + test-plan.json
│                                    (+ out-test-plugin.jar = verification artifact)
├── templates/
│   └── skeleton-plugin/             COMPLETE compilable Gradle project (verified):
│       ├── build.gradle             offline build vs ../../sdk/jars, Java 11 release, toolchain
│       ├── settings.gradle
│       └── src/main/java/com/example/template/
│           ├── TemplatePlugin.java      lifecycle + events + injected menu entry + PostMenuSort pin
│           ├── TemplateConfig.java      @ConfigGroup/@ConfigItem with sections
│           └── TemplateOverlay.java     OverlayPanel with LineComponent rows
│       └── src/main/resources/runelite_plugin.json   (1.13.6 plugin discovery format)
└── examples/
    └── construction-spec/           CONSTRUCTION-ALGORITHM.md + construction_skill.md
                                     (the worked example: full breakdown + clean-room port spec)
```

---

## Walkthrough A — build a new plugin from the template

1. **Copy the template**
   ```bash
   cp -r templates/skeleton-plugin plugins-src/my-plugin && cd plugins-src/my-plugin
   ```
2. **Rename the package/class.** `com.example.template` → yours; rename the
   three `Template*.java` files; update `runelite_plugin.json`'s `plugins`
   array; set `group`/`rootProject.name` in the Gradle files. Pick ONE marker
   constant for your menu entry text and use it in all four places
   (injection, de-dup, PostMenuSort match, click match) — the template shows
   the pattern (`MENU_MARKER`).
3. **Read `docs/plugin-development/01-design-philosophy.md` before writing
   features.** The rules there (one click / client UI layer only /
   server-confirmed progress / every flag has a reset trigger / human-shaped
   input / fail visible) are what separates a helper from a bot — and the
   threat-model section explains exactly why each rule matters observably.
4. **Design the sequence as events.** For each step of your task write down:
   the triggering click, the game event that confirms each step (object spawn,
   container change, widget load, varbit, chat line), and the reset trigger
   for every flag you add. `04-interaction-patterns.md` has ready-made
   skeletons (site queues, inventory accounting, noted/unnoted resolution,
   NPC flows, varbit gates).
5. **Build**
   ```bash
   export JAVA_HOME="C:\Users\sahij\AppData\Local\RuneLite\jre"   # Gradle 9.1 runs on 17
   gradle build --no-daemon        # compiles via the JDK 11 toolchain, fully offline
   ```
   Output: `build/libs/<name>-<version>.jar` (contains your classes +
   `runelite_plugin.json`).
6. **Install & test**: copy the jar to `C:\Users\sahij\.runelite\plugins\`,
   restart the client, enable the plugin in the config panel. Run the
   acceptance checklist from your design notes (the case study's checklist in
   `06-case-study-construction.md` is a good template). If the client runs a
   newer `runelite-api`, run the drift pipeline
   (`python tools/drift/check_drift.py --apply`) — it canary-compiles against
   the newest jars and re-pins the SDK automatically, or blocks with a
   signature diff if the API changed (see `tools/drift/README.md`).
7. **Style/debug help**: `docs/runelite/02-api-cheatsheet.md` (verified
   signatures), `04-gotchas.md` (the classic NPE/timing mistakes),
   `docs/plugin-development/05-overlay-and-stats.md` (visible state).

## Walkthrough B — reverse engineer the next Kuri plugin

Full context in `docs/reverse-engineering-kuri/` (01 = format, 02 = capture,
03 = strings, 04 = transport, 05 = internals, 06 = exact commands). The loop:

1. **Capture keys** (once, at sync time — keys exist only in the load-plan
   response and in memory):
   attach `tools/capture-agent/` per its `USAGE.md`, sync the plugin in the
   Kuri manager, stop. You get `capture.jsonl` (load-plan JSON with per-release
   `contentKey`/`nonce`, KencFile args) and `plaintexts/<sha256>.jar`
   (already-decrypted jars).
2. **Or decrypt from the container**: if you have a cached container
   (`%LOCALAPPDATA%\Kuri\cache\plugins\<sha256>`) plus the captured plan item:
   ```bash
   cd tools
   python kenc_decrypt.py decrypt <container.kenc> plan-item.json out.jar
   # plan-item.json needs: contentKey, nonce, pluginId, version, plaintextSha256
   ```
   (`analyze <file>` works without keys — header parse only.) Format details:
   `docs/reverse-engineering-kuri/01-kenc-container-format.md`.
3. **Decompile**
   ```bash
   java -jar sdk/cfr.jar out.jar --outputdir decompiled --caseinsensitivefs true --outputencoding UTF-8
   ```
   Write a `summary.txt` (one line per class with its inferred role) like the
   ones in the analysis folder — everything downstream keys off it.
4. **Annotate strings** (on a scratch copy):
   `python tools/deob_strings.py <scratch-decompiled-dir>` — CFR comments plus
   the annotations make most sources readable. Layer-2 strings: manual XOR
   recipe in `03-string-deobfuscation.md`.
5. **Dump constants by running the classes** (defeats all string layers and
   key mutation):
   ```bash
   cd tools && javac ConstDump.java EnumDump.java
   java -cp ".;../sdk/jars/*;../sdk/jars/kuri/kuri-runelite-v1.7.64.jar;<decrypted>.jar" ConstDump
   ```
   Edit the two `Class.forName` targets to the new plugin's constants class
   and `MenuConfig` impl (found via the summary). This is how every constant
   in the case study was recovered — exact, not guessed.
6. **Map Kuri APIs → vanilla**: `05-plugin-internals-tour.md` §6 has the full
   port table (Menu facade → `client.getMenu()`, queries → scene-tile walks,
   Inventory → `ItemContainer(93)`, etc.). Produce a clean-room spec like
   `examples/construction-spec/construction_skill.md`, then implement
   starting from `templates/skeleton-plugin`.
7. **Optional: capture the transport layer** for new endpoints/behavior —
   `04-transport-and-capture.md` documents the endpoint set, the strict
   load-plan contract, the heartbeat/revocation lease, and the okhttp vs
   `java.net.http` split (which decides what your hooks can see).

---

## Versions researched (2026-11-03)

| Subject | Version | Source |
|---|---|---|
| RuneLite maven `runelite-api` / `client` | **1.13.6** (1.13.7-SNAPSHOT on master) | repo.runelite.net maven-metadata.xml |
| plugin-hub build pin | 1.13.6 | github.com/runelite/plugin-hub `runelite.version` |
| Java target | 11 (class-file major 55, byte-verified in both local jars) | local javap + example-plugin build.gradle |
| Official template Gradle | wrapper 8.10 pinned | example-plugin gradle-wrapper.properties |
| Local Gradle | 9.1.0 (runs on the Temurin 17 JRE, compiles Java 11 via toolchain) | `sdk/jdk-notes.md` |
| Kuri client framework | kuri-runelite v1.7.64 (v1.7.56/58/65 also present locally) | `~/.runelite/repository2/k/` |
| Kuri launcher / manager | launcher v1.5.6; Manager 0.1.8 (Tauri 2.11.2) | decompiled sources / manager_strings.txt |
| Local JDKs | Temurin 17.0.19 JRE (RuneLite jre), Temurin 11.0.28 JDK (Adoptium) | `sdk/jdk-notes.md` |
| KENC format | v1.1 (major.minor = 1.1) | container headers + decryptor verification |

Every doc states the version its facts apply to; web facts carry their source
URLs inline (primary: repo.runelite.net, github.com/runelite/*,
static.runelite.net javadocs).

## Verification log (all executed inside this folder)

| # | Check | Command | Result |
|---|---|---|---|
| 1 | **Template compiles** | `gradle clean build --no-daemon` (JAVA_HOME = RuneLite 17 JRE) | **PASS** — BUILD SUCCESSFUL; `build/libs/template-plugin-1.0.0.jar` produced with classes + `runelite_plugin.json`. (First run exposed the JRE-has-no-javac toolchain issue → fixed in build.gradle via Java 11 toolchain; `OverlayPriority` deprecation removed.) |
| 2 | **KENC decryptor** | `python tools/kenc_decrypt.py decrypt tools/testdata/test-container.kenc tools/testdata/test-plan.json out.jar` | **PASS** — GCM tag authenticated (proves AAD construction: pluginId `test-plugin`, version `1.0.0`), plaintext SHA-256 = `121ceced2d351e07e1d538d67dd964e96bf07c7d7680637b33cb0601d1bc6424` exactly matches `test-container.info`, plaintext = known `PK\x03\x04 "fake plugin jar payload for KENC validation"` blob (472 B). *Fix applied during verification: the copied decryptor compared the header's hex nonce against the plan's base64 nonce and always failed — `_norm_nonce` now accepts either form.* |
| 3 | **ConstDump/EnumDump pattern** | `java -cp ".;../sdk/jars/*;../sdk/jars/kuri/kuri-runelite-v1.7.64.jar;<analysis decrypted construction jar>" ConstDump` (+ EnumDump) | **PASS** — printed the decrypted constants class (`<col=8532cb>Kuri Click Servant Fetch`, widget ids 30015491/458/24248342/7602207, varbits 2176/6719, servant set {221,223,225,227,229}, bench set {29136,29139}, the 11-material whitelist, braille decoy) and `MENU OPTION TEXT = <col=8532cb>Kuri Click Construction`; EnumDump printed the full servant enum (RICK 221/6/500 … DEMON_BUTLER 229/26/10000). |
| 4 | **Docs pass** | grep for TODO/FIXME/TBD/placeholder-text across `docs/`, `templates/`, `sdk/*.md`, `tools/*.md` | **CLEAN** — no placeholder text; every factual claim traces to the local analysis folder or a cited URL; cited client APIs (`QueuedMessage.builder`, `ChatMessageManager.queue`, `Perspective.getCanvasTilePoly`, `Experience.getXpForLevel`, config annotations) re-verified with javap during the pass. |
| 5 | **Drift pipeline: in-sync** | `python tools/drift/check_drift.py --quick` then full run | **PASS** — exit 0 both; report written; real pin stays `ok 1.13.6` |
| 6 | **Drift pipeline: signature diff** | `api_diff.py runelite-api-1.13.5-runtime.jar runelite-api-1.13.6-runtime.jar` | **PASS** — 244→244 classes, 1 changed class found (`net.runelite.api.Quest` +2 constants) — a real release change, correctly extracted |
| 7 | **Drift pipeline: drift + auto-fix (sandboxed)** | fixture repo with fake `client-1.13.7` + temp sdk/pin copies, full run `--apply` | **PASS** — exit 1 (`FIXED`): canary compiled vs 1.13.7 overlay, temp sdk synced (old versions removed, `versions.txt` regenerated), pin → `auto-fixed 1.13.7`; real sdk/pin untouched |
| 8 | **Drift pipeline: quick signal** | quick run vs fixture repo + temp pin | **PASS** — exit 2, temp pin → `needs_human` with drift reason; real pin untouched |
| 9 | **Gradle guard (block leg)** | `gradle build` with: plain / fixture repo / fixture + `-PallowStaleSdk=true` / pin `status=blocked` | **PASS** — plain: builds; fixture repo: **FAILED** with actionable message; allowStaleSdk: builds with loud warnings; blocked status: **FAILED**; restore: builds |

## Scope & responsible-use notes

- The RuneLite plugins described here use **only public `net.runelite.*`
  APIs**, operate through the client's own UI layer, and never craft packets
  or touch memory. The `sdk/jars/kuri/` jar and the capture agent are
  analysis instruments for content you are licensed to use — neither belongs
  in any distributed plugin, and `capture.jsonl` output contains account
  secrets.
- Anti-cheat considerations are covered in
  `docs/plugin-development/01-design-philosophy.md` as a *behavioral
  ordinary-ness* argument (why this design's observable traffic looks like
  ordinary play), not as evasion guidance; account-level risk from running
  any automation is the user's decision, not something the code can remove.
- Kuri's format/APIs can change per release; each RE doc lists its
  maintenance points (where to re-check per framework version).
