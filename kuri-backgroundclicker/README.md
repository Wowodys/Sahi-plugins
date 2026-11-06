# kuri-backgroundclicker — Background Clicker

Standalone, fully decoupled RuneLite plugin that passes global (OS-level)
hardware mouse clicks into the game client's canvas through a relative
physical-to-canvas coordinate mapping.

**Decoupling contract:** this plugin owns no game state and references no
other plugin. It intercepts OS clicks and dispatches synthetic
`java.awt.event.MouseEvent`s onto the RuneLite `Canvas`; the client's own
input pipeline (Callbacks → MouseManager → recorded mouse state →
client-thread menu processing → `MenuOptionClicked`) does the rest. The 1:1
location plugin (`../kuri-oneclick`) intercepts the resulting
`MenuOptionClicked` and routes the walk — it requires **zero modifications**
for this module to feed it. Neither plugin imports the other.

---

## Module layout

```
kuri-backgroundclicker/
├── build.gradle.kts          compileOnly(:client); JNA arrives via :client's api deps
└── src/main/java/net/runelite/client/plugins/kuri/backgroundclicker/
    ├── BackgroundClickerPlugin.java   lifecycle, toggle, dispatcher, EDT dispatch
    ├── BackgroundClickerConfig.java   active toggle, hotkey, safe zone, filtering
    ├── SafeZoneOverlay.java           zone rectangle + last mapped click (calibration)
    ├── StatusOverlay.java             active / hook state / counters panel
    ├── hook/
    │   └── WinMouseHook.java          WH_MOUSE_LL via JNA (jna-platform, already on
    │                                  the client classpath — version 5.9.0)
    └── clicker/
        ├── SafeZoneMapper.java        linear normalization against the monitor's physical extent (no RNG)
        └── CanvasClickInjector.java   MouseEvent PRESSED+RELEASED pair construction
```

## Event pipeline (what happens on one physical click)

```
hardware left click anywhere on the desktop
  └─ WH_MOUSE_LL callback (kuri-bgclicker-os-hook thread, JNA LowLevelMouseProc)
       ├─ filter: LLMHF_INJECTED events dropped IN THE HOOK (unconditional
       │   loop guard - this module never calls SendInput, so only FOREIGN
       │   injected streams could ever appear here)
       ├─ guards: active? not over the client window?
       ├─ swallow decision (config: mirror vs redirect)
       └─ SynchronousQueue.offer(click)          ← non-blocking handoff
            └─ dispatcher thread ("kuri-bgclicker-dispatch")
                 ├─ pair press with release (a click forwards ONCE, on release,
                 │   at the press position; stale pairs use release position)
                 ├─ guard: LOGGED_IN, canvas present
                 ├─ SafeZoneMapper.map(screenX, screenY) → canvas (x, y)
                 │    = zoneMin + clamp(physical / monitorSize) * zoneSize
                 │     (linear across the primary monitor's PHYSICAL extent —
                 │      left edge → zone left, right edge → zone right; no
                 │      Random — jitter is the user's own hand jitter)
                 └─ EventQueue.invokeAndWait → EDT:
                      ├─ canvas.isShowing()? not over client frame?
                      └─ Canvas.dispatchEvent:
                           MOUSE_PRESSED (button mask, x, y, clickCount 1)
                           MOUSE_RELEASED (mask cleared, same x, y)
                                └─ client's canvas listener → Callbacks.mousePressed
                                   → MouseManager (plugins may consume — ours don't)
                                   → client records mouse state at (x, y)
                                   → client thread: menu logic → MenuOptionClicked
                                   → the 1:1 location plugin intercepts, as with a
                                     real mouse click, and dispatchDirectWalk()s
                                     its configured tile. Nothing to change there.
```

Why it works while the client window is **unfocused**: `dispatchEvent()`
feeds the canvas's registered listeners directly, bypassing the native event
queue — the only layer where Windows applies focus routing. No code in the
pipeline checks focus for mouse events (RuneLite's focus gate,
`ClientUI#dispatchWindowKeyEvent`, covers window-level *keyboard* dispatch
only). Verified against the vendored `runelite/` source in this repo.

## Coordinate spaces (and Stretched Mode)

Every coordinate this module exchanges is **canvas space**: relative to the
canvas origin, bounded by canvas width/height. The zone config, the mapper's
output, the injected `MouseEvent` x/y, and the zone overlay are all one
space — with one rendering exception this module now reconciles:

- **Plain (unstretched) or GPU rendering:** overlays draw at canvas
  dimensions; what you see is what clicks land on.
- **Stretched Mode (non-GPU):** the client renders overlays into the game's
  *real-dimension* buffer and then stretches the whole buffer onto the canvas
  (`Hooks#draw`), while dispatched events are translated *down* by the same
  factor (`TranslateMouseListener`, MouseManager position 0). Without
  compensation the zone marker visually sits at `coord × factor` — up-left of
  where the click lands, drifting linearly with position. `SafeZoneOverlay`
  now scales its drawing by `real/stretched`, so the marker's on-screen
  position equals the canvas-space rect the events land on.

Windows DPI scaling does **not** cause marker-vs-click divergence (both
paths run in the same logical AWT space); it only affects the physical
→ zone mapping domain — see *Monitor width / height* in Calibration.

## Toggle guard

- Config panel: **Active** checkbox (`kuribackgroundclicker.active`).
- Hotkey: configurable `Keybind`, flips the flag and syncs the checkbox.
  Hotkeys only fire while the client has keyboard focus (RuneLite gates
  window-level keys) — press it once inside the game window, then go back to
  work. When the flag is off, the hook callback returns immediately and
  every click propagates untouched.

## Mirror vs redirect

| mode | config | physical click at the focused app | game |
|------|--------|-----------------------------------|------|
| mirror (default) | `swallowForwardedClicks = false` | unaffected | gets the mapped copy |
| redirect | `swallowForwardedClicks = true` | click is swallowed | only place the click happens |

If the dispatcher is still busy with the previous click, a new physical
click is **never** queued: it is passed through to the focused app untouched
and counted as `dropped`. A forwarded click is always your live click —
this module cannot ever replay stale clicks.

Right clicks forward too (`forwardRightClicks`, default on) because the
unfocused one-click flow needs them: right click opens the context menu in
the safe zone, left click selects the `<col=8532cb>One-Click</col> Walk`
row (pin it to the bottom of the menu via the location plugin's
`pinEntryToBottom` for a stable row position).

## Calibration

1. Enable the plugin, enable **Show safe zone overlay**.
2. Tune `zoneOffsetX/Y` and `zoneWidth/Height` so the rectangle sits over
   open viewport space (default 256x192 at +128,+128 fits the fixed-mode
   765x503 viewport). Keep it off widgets/chatbox — clicks landing there
   click widgets, and the overlay draws ABOVE_WIDGETS so a misplaced zone
   is visible as a mistake.
3. Check **Monitor width / Monitor height** (zone section, defaults
   2560x1440): these are the normalization domain and must be your primary
   panel's **native** pixel size. The OS hook reports *physical* pixels — if
   Windows display scaling is not 100%, the logical size Java reports
   (e.g. 1707x960 for 2560x1440 at 150%) is the wrong number here.
4. Hold your physical hand still: the mapped point (yellow cross) is stable.
   Your hand's natural jitter translates through — the mapping is linear
   edge-to-edge (monitor left edge → zone left edge, monitor right edge →
   zone right edge, monotonic in between; clicks outside the primary monitor
   clamp to the nearest zone edge). The old modulo fold and its wrap seam
   are gone.
5. Unfocused flow: toggle Active → in another app, right-click then
   left-click at the memorized physical position → menu opens in the zone,
   the row click lands on One-Click Walk, the walk goes to the configured
   tile (the location plugin walks to its config tile regardless of exactly
   where in the zone the row click landed — the row click only selects the
   menu row; the tile rides the entry's param0/param1).

## Build & install (compiled-in route, same as kuri-oneclick)

1. `runelite/settings.gradle.kts`:
   ```kotlin
   include("kuri-backgroundclicker")
   project(":kuri-backgroundclicker").projectDir = file("../kuri-backgroundclicker")
   ```
2. `runelite/runelite-client/build.gradle.kts`:
   ```kotlin
   runtimeOnly(project(":kuri-backgroundclicker"))
   ```
3. From `runelite/`: `./gradlew :client:compileJava` (or `:client:run`).
   PluginManager's core classpath scan discovers
   `net.runelite.client.plugins.kuri.backgroundclicker.BackgroundClickerPlugin`
   at startup; enable it in the config panel. External-jar side-loading is
   deliberately not used for this module: JNA must match the client's copy.

## Platform notes

- Windows only (WH_MOUSE_LL). On other platforms `startUp` still works but
  `WinMouseHook` reports a FAILED hook state in the status overlay and
  forwards nothing (JNA's User32 is Windows-native).
- LL hook callbacks must return fast; the hook thread pumps messages via
  `GetMessage` and only reads the `MSLLHOOKSTRUCT` — every other lane in
  this plugin exists to keep it that way.
- The low-level hook rejects OS-injected events (`LLMHF_INJECTED`)
  unconditionally, inside the hook callback itself: other automation,
  screen recorders, and accessibility tools can never bounce through this
  module. It is the infinite-loop guard, not a preference — this module's
  own synthetic events are AWT-internal (`Canvas.dispatchEvent`) and can
  never appear in the hook stream in the first place.
- Restart the hook freely: `shutDown()` unhooks, posts WM_QUIT to the pump,
  and interrupts the dispatcher — no half-forwarded clicks survive a toggle.
