# One-Click Location — a 1:1 click framework

A standalone RuneLite plugin built on a minimal reusable framework
(`com.example.oneclick.framework`) that performs **one click → one ordinary
game action** at a fixed world tile, in the one-click style documented in
`docs/plugin-development/01-design-philosophy.md`.

**Reference target (defaults):** tile **(2800, 9568) plane 3** —
chunk **717996** (chunkX 350, chunkY 1196, chunks are 8×8, plane rides
separately), region **11157** (`(2800>>6)<<8 | 9568>>6`). The plugin checks
the configured tile against these identifiers on first tick and surfaces any
mismatch (one chat line + permanent overlay line).

## What "1:1" means here

The plugin injects **one real-action menu entry** — `MenuAction.WALK` with
the target's scene coordinates (the id-23 form: param0/param1 = scene x/y).
Clicking it makes the client perform the ordinary walk-here dispatch exactly
as if the player had clicked the tile by hand: same packet, same UI trail,
no `onClick` handler, no second action, no dispatch by the plugin. The
framework adds **zero** outbound traffic of its own; it only validates
before the click and confirms from the server's reactions after it.

## Layout

```
src/main/java/com/example/oneclick/
├── framework/                      the reusable 1:1 engine
│   ├── ClickState.java             the whole state machine: IDLE / ARMED
│   ├── ConfirmResult.java          PENDING / CONFIRMED / ABORTED verdicts
│   ├── BoundedAction.java          canArm() + confirm() contract
│   ├── RsCoords.java               region/chunk arithmetic (verified numerically)
│   └── OneClickEngine.java         the rules as code
└── location/                       the concrete plugin
    ├── LocationClickConfig.java    target coords, region gate, pin (off), overlay
    ├── WalkToTileAction.java       the BoundedAction: position-delta confirmations
    ├── LocationClickPlugin.java    injection + gates + click capture + reset wiring
    └── LocationClickOverlay.java   state / outcome / target identity / counters
```

Build (from the kuri-plugin-dev root, same offline SDK as the template):

```bash
export JAVA_HOME="C:\Users\sahij\AppData\Local\RuneLite\jre"
gradle :... # or: cd plugins-src/one-click-location && gradle build --no-daemon
```

Output: `build/libs/one-click-location-1.0.0.jar`.

**How side-loading actually works on RuneLite 1.13.6** (verified by
decompiling `client-1.13.6.jar` with `sdk/cfr.jar` on 2026-11-03):
`~/.runelite/plugins\` is the plugin-hub download folder and is never
scanned for arbitrary jars. `PluginManager.loadSideLoadPlugins()` instead
scans **`~/.runelite/sideloaded-plugins\`**, and only in developer mode.
So:

1. `cp build/libs/one-click-location-1.0.0.jar "C:\Users\sahij\.runelite\sideloaded-plugins\"`
2. Launch with `--developer-mode`:
   `"C:\Users\sahij\AppData\Local\RuneLite\RuneLite.exe" --developer-mode`
3. Enable "One-Click Location" in the config panel. The client log shows
   `Side-loading plugin <jar>` when it picks the jar up.

Notes from the decompiled loader: it does not read `runelite_plugin.json`
at all (that file is only used by plugin-hub installs) — `ClassPath` scans
every class in the jar, and anything without a `@PluginDescriptor` that
doesn't extend `Plugin` is skipped silently, so bundling the framework
package in the same jar is fine. The `--developer-mode` flag is needed on
every session you want the plugin (make a shortcut with the argument).
(The drift guard from the template applies unchanged.)

## Philosophy rule → implementation map

| Rule | Where |
|---|---|
| 1. one click = one bounded sequence | the click's own WALK dispatch is the only action; `OneClickEngine.onGameTick` evaluates exactly one confirmation per tick; the cycle ends at CONFIRMED/ABORTED the same tick — no timers, no chains, no retry path exists in the engine |
| 2. client UI layer only | a real `MenuAction.WALK` entry on a scene-verified tile; the framework never calls `client.menuAction(...)` and never crafts anything; rejection = `event.consume()` (refusing a click is client-local) |
| 3. server-confirmed progress | `WalkToTileAction.confirm`: world-location change since the click (server moving the player) or arrival at the target; silence past the 3-tick observation horizon is an abort, not a retry |
| 4. state hygiene | every flag has a documented reset (table below); `reset()` never dispatches; stats clear on login |
| 5. human-shaped input | the entry is one stable, pinned-optional entry appended like any menu entry; the outbound rate is the player's own clicking rate — the plugin cannot act without a click |
| 6. fail visible | the overlay always shows state, last outcome + reason, target identity (✓/MISMATCH), distance, why the entry is absent when a gate withholds it; fatal config mismatch surfaces one chat line per login |

## Reset-trigger table (rule 4)

| Flag | Natural reset trigger(s) |
|---|---|
| armed intent (`ARMED`) | server confirmation (position delta / arrival); confirmation horizon elapsed (3 ticks); any `WidgetLoaded` mid-cycle (world changed under the intent); `GameStateChanged → LOGIN_SCREEN`; plugin `shutDown()`; re-arm (player clicking again replaces the intent) |
| session counters | `GameStateChanged → LOGIN_SCREEN` (engine.clearStats) |
| identity-checked flag | login, plugin shutdown |
| `injectedReason` | recomputed every ClientTick (never persists) |

## Behavioral notes

- The **region gate** (config, default on) mirrors the case study's varbit
  gate: outside region 11157 the entry is never injected and the whole
  subsystem is inert.
- **Pin-to-top is off by default** on purpose: this is a real WALK entry, so
  pinning would make it the default left-click everywhere and hijack every
  ordinary click. The config description warns about this.
- Entry injection only happens when the target tile is in the loaded scene
  with a real tile under it — a real-action entry must never target
  coordinates the scene can't see (no speculative clicks, gotchas §8).

## Scope

One click, one walk, confirmed, done — a helper, not a bot: nothing here can
run without the player's click. Same responsible-use caveats as the dev
folder README apply (public APIs only, no packets, no reflection; account
risk is the user's decision).
