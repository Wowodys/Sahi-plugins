# 06 — Case Study: One-Click Construction (Kuri Click Construction v1.0.31)

> A distilled walkthrough of the fully reverse-engineered plugin, organized
> around the design rules in
> [01-design-philosophy.md](01-design-philosophy.md). Raw material:
> `examples/construction-spec/CONSTRUCTION-ALGORITHM.md` (full algorithmic
> breakdown) and `construction_skill.md` (the clean-room porting spec). All
> constants below were recovered by **running the plugin's own classes**
> (`tools/ConstDump.java` / `EnumDump.java`), so they are exact.

## What it is

"One click anywhere to train Construction." One injected menu entry; clicking
it runs the training loop autonomously: build furniture → account materials →
unnote/fetch if needed → remove built furniture → rebuild — indefinitely,
with a stats HUD. Config: `enableOverlay`, `useServant`, `doubleBenches`,
`fillWateringCans` (vanilla `@ConfigGroup("kuriclickconstruction")`).

## Architecture at a glance

```
Plugin (entry: wires everything, menu injection + PostMenuSort pin)
├── engine (b.a)        KeyListener 1–4, material accounting, site queues
├── click tracker (b.c) arms/disarms the cycle on click + confirm events
├── requirements (b.b)  parses build-interface text → (name, qty)
├── servant (b.e)       NPC tracking, fetch/pay flow, wage verification
├── stats (c.b)         StatChanged/ItemContainerChanged accounting
├── overlay (c.a)       OverlayPanel ABOVE_WIDGETS bottom-left
├── states (d.a–d.g)    Guice-resolved state classes (see below)
└── constants (e.a)     ids/coords/whitelist (recovered via ConstDump)
```

## Verified constant tables

| Constant | Value |
|---|---|
| Injected menu text | `<col=8532cb>Kuri Click Construction` |
| Servant entries | `<col=8532cb>Kuri Click Servant Fetch` / `<col=8532cb>Kuri Click Pay Servant` |
| Build interface | group **458**; probe widget (458,3) = packed **30015491**; option children (458,4–7) ↔ keys 1–4; requirements text child+3; icon child+4 |
| Dialog groups | {219, 229, 231, 233, 370}; statements (229,1); servant dialog (231,6); options (219,1) |
| Varbits | **2176** = inside player-owned house; **6719** = servant state |
| NPCs | Phials **1614** (Rimmington unnoter); servants **221/223/225/227/229** |
| Servants | Rick 221 ×6/trip 500gp · Maid 223 ×10 1,000 · Cook 225 ×16 3,000 · Butler 227 ×20 5,000 · Demon butler 229 ×26 10,000 |
| Items | inventory container **93**; coins **995**; empty can **5331**; full cans **5333–5340** |
| Objects | well = wall object **1540** @ WorldPoint(2962,3213); trough **9684**; exit **4525**; portal **15478**; double benches {**29136, 29139**} |
| Places | Phials spot (2950,3214); trough spot (2966,3209) — Rimmington |
| Servant materials whitelist | Plank, Oak plank, Teak plank, Mahogany plank, Steel bar, Limestone brick, Marble block, Gold leaf, Magic stone, Soft clay, Bolt of cloth |

## The click, step by step (with the rule each step obeys)

1. **Injection** (every `ClientTick`, logged in only): if the last menu entry
   already contains "Kuri", do nothing; otherwise append the entry and mark
   stale framework entries temp. *(Rule 5 — stable, non-flickering UI.)*
2. **Pin** (`PostMenuSort`): swap the entry to the last array slot so it holds
   a fixed position. *(Rule 5.)*
3. **Arm** (`MenuOptionClicked`): option `Build` → record
   `getMenuEntry().getIdentifier()` (the spot); option containing "Kuri" with
   type UNKNOWN/object-op (and the `(type != CC_OP || param1 != 0)` guard) →
   mark a cycle armed, targeting the clicked widget's object. *(Rule 1 — one
   real click arms one bounded sequence.)*
4. **Interface load** (`WidgetLoaded` group 458): clear site queue; locate
   site(s) by id in the scene (nearest to player; double-bench mode → both
   benches within 2 tiles); parse requirements from (458, child+3); read icon
   id from child+4. *(Rule 3 — the server-driven UI confirms the click
   landed.)*
5. **Account** (`ItemContainerChanged` 93): Phials path counts unnoted
   material vs requirement; servant path resolves noted variant + trip math
   (`qty >= 8 ? 2 : 3` × capacity) and aborts if the material isn't on the
   whitelist. "You don't have the right materials" (229,1) → re-account; wage
   dialog (231,6) text must contain the tier's `%,d`-formatted wage **and**
   coins ≥ wage, else full reset. *(Rules 3 + 6 — server text is the truth;
   anomalies reset.)*
6. **Act**: the only outbound actions are ordinary client-dispatched ops —
   `Build` (object op 5), `Remove` on built furniture, servant Fetch/Pay NPC
   ops, "use noted item on Phials", widget clicks for build-interface
   selection. *(Rule 2 — client UI layer only.)*
7. **Confirm** (spawn events, gated on varbit 2176 == 1 + non-null clickbox):
   spawned object matches a pending site → move between
   remove/rebuild queues; removal completion feeds the next build. *(Rule 3 —
   no timer ever decides "build done".)*
8. **Reset** (Rule 4 — eleven documented triggers): NpcSpawned(1614);
   WidgetLoaded of any dialog group; WidgetClosed(219); VarbitChanged
   6719→0; VarbitChanged 2176→0; inventory drained outside the house with no
   empty can; the "watering can with water" chat message; materials
   exhausted; servant can't fetch; plus plugin shutDown/login. Full reset
   clears both queues, all pending ids, flags, counters.

## The state machine (how "autonomous" actually works)

States are classes; each has `emit()` (build the menu entry / widget click for
this state) and `next()` (return self or another state). One root dispatcher
re-evaluates **from event-fed data only**: pending builds → BUILD; cans to
fill → FILL; materials needed + !servant → PHIALS; materials needed + servant
→ PAY; paid + not waiting → SERVANT-FETCH; else idle. Transitions fan back
when their precondition clears. There is no scheduler and no retry counter —
the "loop" is just the dispatcher converging on whatever the game state
implies.

## What was NOT ported (and why)

- Kuri platform (`com.kuri.*`: Plugin base, Menu facade, MenuConfig, queries,
  Inventory, injector) → replaced by the vanilla equivalents table in
  [../reverse-engineering-kuri/05-plugin-internals-tour.md](../reverse-engineering-kuri/05-plugin-internals-tour.md) §6.
- Licensing/heartbeat/KENC/rlicn → not needed; a vanilla jar in
  `~/.runelite/plugins` needs nothing of it.
- Obfuscation (string LCG, XOR pools, opaque predicates, junk classes) → not
  ported; constants were recovered once via ConstDump/EnumDump and inlined.

## Known subtleties (from the clean-room spec — don't skip)

- Use ONE marker constant for injection/dedup/sort/click matching.
- Requirement text: strip `<br>(`/`)<br>` before `split(":")`; names carry
  parenthesised qualifiers ("Oak plank (3)").
- Scene-tile comparisons must be consistent: `getSceneMinLocation()` for
  game objects, `tile.getSceneLocation()` for wall/decorative.
- The `qty >= 8 ? 2 : 3` servant multiplier is empirical — keep it.
- Every event handler must no-op safely when
  `client.getLocalPlayer() == null` or `GameState != LOGGED_IN`.

## Acceptance checklist (in-game)

1. Rimmington POH, noted planks + coins; enable plugin; right-click a spot →
   entry present, pinned; click → interface opens, matching option selected.
2. Insufficient unnoted planks → Phials flow unnotes them; build proceeds.
3. Servant mode → fetch requested, wage verified+paid, items arrive, build
   proceeds.
4. Continuous build→remove→rebuild; double-bench builds both.
5. Overlay counts XP/XP-h/actions-h/timer/materials; icon updates.
6. Keys 1–4 select options while the interface is open.
7. Leave house / dismiss servant / run out of materials → clean stop, no
   stuck state; re-enter starts fresh.
8. Can-fill mode fills cans at the Rimmington well.
