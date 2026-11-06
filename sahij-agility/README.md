# sahij-agility — Sahij Agility

Standalone, fully decoupled RuneLite plugin implementing a strict 7-step,
1:1 human-mapped agility sequence for the documented course (region 10806).

**Decoupling contract:** like its siblings (`../kuri-oneclick`,
`../kuri-backgroundclicker`), this module owns its state and references no
other plugin. It meets the background clicker only at the client's vanilla
input pipeline: a hardware click anywhere on the desktop that the background
clicker forwards becomes an ordinary `MenuOptionClicked`, exactly like a
focused-window click — this plugin intercepts that event and performs
**one** real game action for it. `kuri-oneclick` is untouched.

---

## The documented sequence (spec-locked, in `engine/AgilityCourse`)

| # | Action | Object | Tiles | Plane | Chunk | Plane-drop reset |
|---|--------|--------|-------|-------|-------|------------------|
| 1 | Walk here | — | `2729, 3489` (single) | 0 | 698804 | — |
| 2 | Climb-up | `14927` Wall | `2729, 3489` (single) | 0 | 698804 | — |
| 3 | Jump | `14928` Gap | `2720..2723 x 3491..3493` (12) | 3 | 696756 | **yes** |
| 4 | Cross | `14932` Tightrope | `2710, 3489` (single) | 2 | 692660 | **yes** |
| 5 | Jump | `14929` Gap | `2709..2711 x 3476` (3) | 2 | 692658 | **yes** |
| 6 | Jump | `14930` Gap | `2701..2703 x 3469` (3) | 3 | 690609 | — |
| 7 | Jump | `14931` Edge | `2703 x 3464..3465` (2) | 2 | 690609 | — |

Region `10806` throughout. Steps 1 and 2 share the start tile on purpose:
the walk delivers the player to the wall; standing on the tile means the
climb is next (the engine's "last matching step" rule).

## Primary-monitor screen-zone mapping (the human randomization)

For steps with N tiles (3, 5, 6, 7), the primary monitor's absolute
horizontal width (config, default 2560 physical px) is divided into **N
equal brackets**. At click time the absolute cursor X (java.awt.MouseInfo)
selects the bracket; the bracket index IS the tile-array index. Where your
hand is on the main screen determines the tile — no RNG anywhere. Tiles
expand x-outer/y-inner ascending (`2720,3491`, `2720,3492`, …, `2723,3493`
for step 3's 12 tiles = 12 brackets of ~213 px). Out-of-domain X (cursor on
a monitor left of the primary) clamps to the edge bracket, never wraps.

## 1:1 click discipline (mechanically enforced, in the engine)

Every dispatched action is the consequence of exactly one user click:

- **Consume-first interception** — the injected entry is client-local
  (`MenuAction.RUNELITE`); a click on it never dispatches anything by
  itself. The plugin performs the single real dispatch in its handler.
- **TICK gate** — at most ONE dispatched click between two GameTicks. Two
  physical clicks in one tick produce at most one action.
- **ARMED gate** — while a dispatched click still awaits server-confirmed
  movement (bounded at 3 ticks, ~1.8s), every further click is refused visibly.
  Clicks are strictly serial; spam can never become action spam.
- **No autonomous path** — no scheduler, no loop, no retry. The sequence
  advances only on the two-condition rule below, and the Z-plane fallback
  (plane → 0 during steps 3–5) resets to step 1 immediately.

## Step transitions (explicit arrival points — strict and chunk-blind)

Step progression never consults chunk ids. Chunks on this course change
mid-obstacle (right between the step-2 wall climb and the step-3 gap), so a
chunk-matched gate strands the machine — chunk ids exist on the step
definitions as metadata only (the once-per-login identity check verifies
them against coordinate arithmetic, target tiles *and* arrival points, and
gates nothing).

Every step carries one **explicit, deterministic arrival tile** (x, y AND
plane) — the exact documented position its action lands on. The engine
advances **strictly on equality** against it: no margins, no bounding
boxes, no auto-calculated ranges, no plane heuristics. Vertical obstacles
are solved by the data itself — step 2's arrival `(2729, 3491, 3)` *is* the
plane-3 wall-top landing of the plane-0 climb, so the 0 → 3 transition is
an ordinary strict match.

| Step | Arrival (x, y, z) | Chunk (metadata) |
|---|---|---|
| 1 Walk | `2729, 3489, 0` | 698804 |
| 2 Climb-up Wall `14927` | `2729, 3491, 3` ← plane 0 → 3 | 698804 |
| 3 Jump Gap `14928` | `2713, 3494, 2` | 694708 |
| 4 Cross Tightrope `14932` | `2710, 3480, 2` | 692659 |
| 5 Jump Gap `14929` | `2710, 3472, 3` | 692658 |
| 6 Jump Gap `14930` | `2702, 3465, 2` | 690609 |
| 7 Jump Edge `14931` | `2704, 3464, 0` ← closes the lap | 692657 |

A **forward transition** (step k → its successor) requires BOTH:

1. a click for step k was dispatched (`recordClick` while k was current), and
2. the player's server-reported location is **exactly** step k's arrival
   tile — all three coordinates equal.

Standing exactly on some other step's arrival point (manual walk-back,
login mid-course, recovery from an unwatched step-6/7 fall) is a
**positional resync** to that arrival's successor — visibly labeled, never
counted as a confirmed step, never a dispatch, never stuck. On no arrival
point at all (mid-obstacle, mid-climb, between platforms) the current step
holds. The world-layer obstacle scan uses exactly the step's documented
tiles (exact object-id match, no substitutions, no widened searches).

## Module layout

```
sahij-agility/
├── build.gradle.kts          compileOnly(:client); runtimeOnly registration in
│                             runelite's settings.gradle.kts + client build
└── src/main/java/net/runelite/client/plugins/sahij/agility/
    ├── SahijAgilityPlugin.java   lifecycle, event wiring, click interception,
    │                             zone resolution, the single dispatch
    ├── SahijAgilityConfig.java   monitor width, region gate, pinning, overlay
    ├── engine/                   PURE (no RuneLite imports), unit-testable
    │   ├── AgilityCourse.java        the exact 7-step spec data (verbatim)
    │   ├── AgilityStep.java          step definition; tile array = zone array
    │   ├── StepTile.java             immutable x/y/plane value
    │   ├── PlayerState.java          immutable player snapshot per tick
    │   └── AgilitySequenceEngine.java position sync, Z-plane fallback,
    │                                  1:1 click gates, bounded observation
    ├── world/
    │   ├── ScreenZoneMapper.java     2560px → N equal brackets (pure)
    │   ├── RsCoordMath.java          region/chunk arithmetic (pure)
    │   └── CourseValidator.java      scene/region/chunk validation, exact-id
    │                                  obstacle lookup, MenuAction resolution
    ├── menu/
    │   └── CourseMenuEntries.java    inject / dedup / match / pin (one marker)
    └── overlay/
        └── AgilityStatusOverlay.java step, zones, lap/confirm counters, outcome
```

## Event pipeline (one click, end to end)

```
hardware click (real mouse, or background-clicker forwarded)
  └─ client's own input pipeline → MenuOptionClicked on our injected entry
       ├─ entry flavor:
       │    walk (step 1)     client-local row — consumed at click, destination
       │                       dispatched into the scene walk state (the client's
       │                       own walk processor; this client's walk arm sources
       │                       its destination from the mouse hover tile)
       │    obstacle (2–7)    REAL GAME_OBJECT action row — a valid
       │                       LEFT-CLICK DEFAULT candidate, so forwarded clicks
       │                       execute it WITHOUT the cursor hovering the tile
       │                       (a RUNELITE-typed row is never a default
       │                       candidate; with one pinned, left-clicks silently
       │                       ran the native entry under the cursor instead —
       │                       the hover-dependence bug)
       ├─ parameters (the client's own, never hand-built):
       │    native row present in the live build (cursor over the object) →
       │      copied VERBATIM: type, identifier, param0/param1
       │    otherwise (hover-independent) → the LIVE object's own
       │      getSceneMinLocation() tile + the composition-bound action
       │      variant (the min-location tile is what the client's rows
       │      address actions to; addressing a multi-tile object's standing
       │      tile instead gets dropped server-side as a silent no-op —
       │      the step 6/7 timeout). Unresolvable → entry ABSENT, overlay
       │      says why
       ├─ pin (default on): swapped into the LAST array slot — the
       │   TOP-rendered, LEFT-CLICK DEFAULT slot in this client
       │   (first-party MenuEntrySwapper promotes entries with exactly this
       │   swap; index 0 renders at the BOTTOM — swapping there pins to the
       │   bottom, the inversion observed in the field)
       ├─ engine.clickGateReason() — 1:1 tick gate + armed gate; a gated
       │   click is consumed so the native arm cannot run it either
       ├─ ungated obstacle click stays UNCONSUMED: the client's native
       │   object arm executes the real entry after the event — its
       │   approach routing, its packet, its UI trail. ZERO synthetic
       │   dispatches (client.menuAction) exist in this module
       ├─ cursor bracket (MouseInfo X → zone index) records the human
       │   correlation and selected tile — telemetry only; it never alters
       │   the packet
       └─ engine.recordClick(...) — bounded movement observation; strict
          arrival-point match advances the step; Z-plane fallback guards 3–5
```

## Build & install

Registered exactly like the sibling modules (see `runelite/settings.gradle.kts`
and `runelite/runelite-client/build.gradle.kts`):

```
cd ../runelite
./gradlew.bat :sahij-agility:build --no-daemon     # Java 11 classes, checkstyle
```

Run the client from the runelite repo (`:client:run`) — the plugin appears
in the config panel as **Sahij Agility** (disabled by default).

## Verification

- `:sahij-agility:build` — clean; checkstyle 0 violations; class files
  major 55 (Java 11).
- Pure-engine harness (116 checks, 0 failures): course data vs the spec
  (tile counts 1/1/12/1/3/3/2, object ids, chunk ids, plane-watch flags,
  **all seven explicit arrival tiles and their documented chunks**),
  region/chunk arithmetic, zone-boundary math (2560/12 → first pixel of
  bracket 1 is 214, clamps, N=1), strict arrival matching (exact accept;
  one-tile-off X / one-tile-off Y / wrong plane all rejected), the full-lap
  walkthrough with dispatched click + exact arrival per step (including the
  **plane 0→3 climb transition via step 2's arrival data**, mid-climb and
  mid-rope holds, and the step-7 ground arrival closing the lap, 7/7
  movement confirmations), near-miss non-transitions, positional resync
  without dispatch, unwatched-fall recovery via the documented start,
  plane-drop fallback (armed and unarmed, steps 3–5 only), and the 1:1
  gates (tick gate, armed gate, same-tick double click, dead-click bounded
  abort, refusal while a dispatch is pending).

## Config

| Item | Default | Meaning |
|---|---|---|
| `monitorWidth` | 2560 | Primary monitor NATIVE px width — the bracket domain. With Windows display scaling ≠ 100%, verify the right screen edge selects the last tile and adjust |
| `regionGate` | true | Entry only while the player is in region 10806 |
| `pinEntryToTop` | true | Entry swapped into the LAST array slot — **top-rendered and the left-click default** in this client (first-party swapper semantics; index 0 = bottom). Every ordinary left-click executes the current step while enabled |
| `requireStepTile` | false | Strict: refuse clicks unless standing on the step's own tiles |
| `showOverlay` | true | The fail-visible status panel |
