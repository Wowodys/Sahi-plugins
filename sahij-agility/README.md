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
  movement (bounded at 10 ticks), every further click is refused visibly.
  Clicks are strictly serial; spam can never become action spam.
- **No autonomous path** — no scheduler, no loop, no retry. The sequence
  advances only on the two-condition rule below, and the Z-plane fallback
  (plane → 0 during steps 3–5) resets to step 1 immediately.

## Step transitions (world bounds + plane — chunk-blind)

Step progression never consults chunk ids. Chunks on this course change
mid-obstacle (right between the step-2 wall climb and the step-3 gap), so a
chunk-matched gate strands the machine — chunk ids exist on the step
definitions as metadata only (the once-per-login identity check reports on
the definition and gates nothing).

A step is current while the player stands in its **arrival area**: the
bounding box of the step's documented tiles widened by
`ARRIVAL_MARGIN_TILES` (2) on each side, matched on the step's plane — pure
world coordinates + Z level. Obstacle landings routinely fall a tile or two
outside a documented tile set; the margin absorbs that (verified: all seven
arrival areas stay disjoint per plane at margin 2).

A **forward transition** (step k → its successor) requires BOTH:

1. a click for step k was dispatched (`recordClick` while k was current), and
2. the player's server-reported location entered the successor's arrival
   area on its plane.

Any other placement change (manual walk-back, login mid-course, recovery
from an unwatched step-6/7 fall) is a **positional resync** — visibly
labeled, never counted as a confirmed step, never a dispatch, and never
stuck. The world-layer obstacle scan uses the same widened bounds (exact
object-id match, no substitutions), so the click path cannot strand on the
same geometry the transition logic no longer trusts.

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
       ├─ consume() — the vanilla pipeline never acts on it
       ├─ engine.clickGateReason() — 1:1 tick gate + armed gate (refuse
       │   visibly; nothing dispatched)
       ├─ zone resolve — MouseInfo X → ScreenZoneMapper.zoneIndex → tile
       ├─ validate — logged in, plane agreement, (strict mode: on-tile),
       │   tile in scene; obstacle steps: exact object id on the step's
       │   tiles (nearest instance to the chosen tile), MenuAction variant
       │   from the object's own composition actions
       └─ ONE dispatch on the client thread:
            walk step   → WorldView.dispatchDirectWalk(sceneX, sceneY)
                          (the client's own walk processor, same trail as a
                          real tile click — hover-tile identity requires this)
            obstacle    → client.menuAction(objSceneX, objSceneY, variant,
                          objectId, -1, action, target)  (same packet trail
                          as clicking the object by hand)
            └─ engine.recordClick(...) — bounded movement observation;
               position sync advances the step; Z-plane fallback guards 3–5
```

## Why the entry is client-local (not a pre-parametered real entry)

The menu entry is built at menu-build time, but the step's tile is a
function of the cursor position **at click time**. A pre-parametered
`GAME_OBJECT_*` entry would freeze the tile choice a tick early and break
the human mapping. The indirection (consume → resolve → one real dispatch)
is what keeps both the 1:1 discipline and the zone mapping exact.

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
- Pure-engine harness (101 checks, 0 failures): course shape vs the spec
  table (tile counts 1/1/12/1/3/3/2, object ids, chunk ids, plane-watch
  flags), region/chunk arithmetic, zone-boundary math (2560/12 → first
  pixel of bracket 1 is 214, clamps, N=1), arrival-area matching (margin
  accept/reject, wrong-plane rejection), **the step-2→3 bug scenario**
  (dispatched climb + landing outside the documented tiles advances;
  a landing in a *different chunk* advances identically — chunk-blind),
  dispatch-gated forward vs labeled positional resync, full-lap walkthrough
  with a dispatched click per obstacle (including the mid-rope hold and the
  step-7 lap close, 6/6 movement confirmations), the plane-drop fallback
  (armed and unarmed, steps 3–5 only — step 2 correctly exempt), and the
  1:1 gates (tick gate, armed gate, same-tick double click, dead-click
  bounded abort, refusal while a dispatch is pending).

## Config

| Item | Default | Meaning |
|---|---|---|
| `monitorWidth` | 2560 | Primary monitor NATIVE px width — the bracket domain. With Windows display scaling ≠ 100%, verify the right screen edge selects the last tile and adjust |
| `regionGate` | true | Entry only while the player is in region 10806 |
| `pinEntryToBottom` | true | Stable entry position (needed for background-clicker safe-zone clicking); NOT a left-click hijack |
| `requireStepTile` | false | Strict: refuse clicks unless standing on the step's own tiles |
| `showOverlay` | true | The fail-visible status panel |
