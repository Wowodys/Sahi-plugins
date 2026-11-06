# Kuri Click Construction v1.0.31 — Algorithmic Breakdown

Fully reverse engineered from the decrypted jar (`decrypted/kuri-click-construction-1.0.31.jar`,
decompiled in `plugin-src/construction/`). All constants below were recovered by
**running the plugin's own classes** (`scripts/ConstDump.java`, `EnumDump.java`), so
they are exact, not guesses.

## 1. What it does

"One click anywhere to train Construction." The player clicks a single custom menu
entry; the plugin then drives the entire train-construction loop autonomously:
build furniture → wait for materials check → unnote/servant-fetch materials →
remove built furniture → rebuild, indefinitely, while tracking XP/session stats
on a custom HUD.

Config options (`KuriClickConstructionConfig`, group `kuriclickconstruction`):
`enableOverlay`, `useServant` (servant instead of Phials), `doubleBenches`,
`fillWateringCans`.

## 2. Component map (deobfuscated roles)

| Obfuscated | Role |
|---|---|
| `KuriClickConstructionPlugin` | Entry point: wires listeners, overlay, executor |
| `b.a` | **Core engine**: KeyListener (hotkeys 1–4), material accounting, build-site queues |
| `b.b` | Parses the build-interface requirements text → `Requirement(name, qty)` list |
| `b.c` | Click/state machine: tracks "am I mid-cycle", watering-can counts |
| `b.d` | POJO: build site = (TileObject, scene Point) |
| `b.e` | Servant subsystem: find servant NPC, fetch/pay flow, wage verification |
| `c.a` | HUD overlay (`OverlayPanel`, `ABOVE_WIDGETS`, bottom-left) |
| `c.b` | Session stats: XP/hr, actions/hr, timer, materials consumed |
| `d.a…d.g` | Data model (e.g. `d.g` = stats model via injector) |
| `e.a` | Constants class (obfuscated): ids, coords, material whitelist |
| `e.b` | Font (`Audiowide-Regular.ttf`) + number formatting utils |
| `a.b` | `MenuConfig` → the injected menu option text |
| `a.c` | Servant enum (greeting, wage, capacity, npc id) |
| `xdjkqfomzkutdtcl.*` | Junk class (bit-shuffle helpers, opaque predicates) |

## 3. Recovered constants (exact, from live class execution)

- Injected menu option: `<col=8532cb>Kuri Click Construction`
- Servant fetch/pay options: `<col=8532cb>Kuri Click Servant Fetch` / `<col=8532cb>Kuri Click Pay Servant`
- Build interface: widget group **458**; slot widget id **30015491** (= 458,3);
  option children 458,4–7 (keys 1–4)
- Dialog groups: {219, 229, 231, 233, 370}; servant dialog children: (229,1) no-materials
  message, (231,6) payment message
- Varbits: **2176** = "inside player-owned house", **6719** = servant state
- Phials NPC id: **1614** (Rimmington item unnoter); servant NPC ids: **221/223/225/227/229**
- Servant table (`a.c`): Rick "Yes, guv?" 6 items/trip 500gp · Maid "Yes?" 10 / 1,000 ·
  Cook "What now?" 16 / 3,000 · Butler "Yes, madam?" 20 / 5,000 ·
  Demon butler "I am at thy command, my master." 26 / 10,000
- Watering cans: empty = **5331**, full = **5333–5340**; well = wall object **1540** at
  WorldPoint(2962, 3213); POH-adjacent coords (2950,3214) / (2966,3209) → Rimmington
- Double-bench furniture ids: {29136, 29139}
- Trackable materials whitelist: Plank, Oak plank, Teak plank, Mahogany plank,
  Steel bar, Limestone brick, Marble block, Gold leaf, Magic stone, Soft clay, Bolt of cloth
- Inventory container id: **93**

## 4. Startup (`startUp`)

1. Register the engine as a `KeyListener` (KeyManager).
2. Construct the HUD overlay, add to `OverlayManager`.
3. Instantiate the stats model through the Kuri plugin injector.
4. Register 4 event subscribers on the EventBus: engine (`b.a`), click tracker (`b.c`),
   servant handler (`b.e`), stats tracker (`c.b`).
5. Start a single-threaded `ExecutorService` (all game mutations are funneled onto
   the client thread through it).

## 5. The core loop (per game tick / event, step by step)

**Phase A — menu injection (every ClientTick):**
1. Bail if not `LOGGED_IN`.
2. Read the current context-menu entries from the client.
3. If the **last** entry already contains "Kuri", do nothing (prevents duplicates).
4. Otherwise regenerate the custom entry (`MenuConfig.getMenuOptionText()` →
   `<col=8532cb>Kuri Click Construction`) via the Kuri framework `Menu` API, and
   `Menu.setTemp` it.
5. On `PostMenuSort`, find the entry whose option contains "Kuri" with type
   `GAME_OBJECT_FIFTH_OPTION` or `UNKNOWN`, and **swap it to the end** of the
   entry array (bottom of the context menu) so it's always in a fixed spot.

**Phase B — capturing intent (MenuOptionClicked):**
- If the clicked option is exactly `Build`: the engine records the clicked building
  spot (`getMenuEntry().getIdentifier()`), and once the build interface appears,
  reads the requirements.
- If the clicked option contains "Kuri" (type `UNKNOWN`, `param1 != 0`): the click
  tracker marks a cycle active; the clicked widget (the furniture spot's menu entry
  widget) determines what will be built.

**Phase C — reading the build interface (WidgetLoaded group 458 / keys 1–4):**
1. `WidgetLoaded(458)` → clear build-site queue, then locate the spot to build:
   nearest `GameObject`/`DecorativeObject`/`WallObject` with the captured id
   (via Kuri `*Query` APIs); for `doubleBenches`, both bench objects within 2 tiles.
2. Parse requirements from widget (458, child+3).text with `b.b.a()`:
   strip `<br>`, split `Name: qty` lines → `Requirement{name, count}`.
3. Read the item icon from widget child+4 (`getItemId()`) for the HUD.
4. Independently, the KeyListener mirrors **keys 1–4**: if widget 458 is open and the
   user (or the plugin) presses 1–4, it resolves widget (458, 4+key) and treats that
   as the material selection.

**Phase D — material accounting (ItemContainerChanged, container 93):**
1. Parse the requirement list of the pending build.
2. **No-servant path (Phials)**: count unnoted required material in inventory;
   `Requirement.count` vs available → decide `needsFetch` / `isComplete`.
3. **Servant path**: find the noted variant (`isNoted` search); if the servant's
   per-trip capacity can cover it (`qty >= 8 ? 2 : 3` multiplier check against
   capacity), mark fetch-needed; if the servant whitelist (`e.a.f`, the plank/bar
   materials) doesn't contain the material, abort the cycle.
4. On "You don't have the right materials" (widget 229,1): re-run accounting.
5. On payment dialog (231,6): verify the servant's chat text contains the wage
   (formatted `%,d`) for the current servant tier from `a.c`; if coins (item 995)
   < wage → reset everything.

**Phase E — the actual game actions (what it tells the client):**
The plugin never sends raw packets. It manipulates the client's own menu/widget
layer so the **client itself** emits the normal outbound actions:
- Injects temp menu entries ("Kuri Click Construction" on any target; servant
  fetch/pay entries on the servant NPC).
- Simulates/forwards widget clicks on build-interface children (select material)
  — either via `MenuOptionClicked` `CC_OP` or keys 1–4 through `ClientThread.invoke`.
- Issues normal menu actions: `Build` (object op 5 / UNKNOWN), `Remove` on built
  furniture, `Fetch` / "Pay servant" NPC ops on the servant, "Use noted item on Phials".
- Reads state only via queries/vars: object queries, varbits 2176/6719, inventory 93.

**Phase F — the build→remove→rebuild state machine (object spawn events):**
The engine keeps two queues of build sites (`GameObjectSpawned` /
`DecorativeObjectSpawned` / `WallObjectSpawned`, gated on varbit 2176 = inside house):
1. On furniture spawn: if it matches a site pending removal (same id or same tile),
   enqueue it for **removal**; the built object is pushed between the
   "remove-queue" and "rebuild-queue".
2. `doubleBenches`: when exactly one bench exists and the remove queue is empty,
   the matching second bench within 2 tiles is queued too.
3. Each removal completion feeds the next build — the loop runs until materials
   run out (Phials path) or the servant can no longer fetch.

**Phase G — exit conditions (`b.c` state resets):**
- NpcSpawned(1614 = Phials) → clear fetch-pending.
- WidgetLoaded of dialog groups / WidgetClosed(219) → clear click states.
- VarbitChanged 6719→0 or 2176→0 (left house / servant gone) → clear.
- Inventory: outside house with no empty watering can (5331) → clear.
- ChatMessage "You need a watering can with water in order to plant garden plants" → clear.

## 6. Watering-can side feature (`fillWateringCans`)

Every GameTick while enabled: locate wall object **1540** (the well) at
(2962, 3213) in Rimmington; the click tracker counts empty cans (5331) vs full
cans (5333–5340) per tick and drives fill clicks when the player is in the
garden (this is the "garden plants" hook visible in the reset conditions).

## 7. Stats & HUD (`c.b`, `c.a`)

- `StatChanged(CONSTRUCTION)`: on each XP increase, measures the delta between
  consecutive XP drops with `Instant` timestamps → total session time,
  XP gained, XP to next level, and **actions/hr** = `1 / (elapsedSeconds/3600) * xpGained`.
- `ItemContainerChanged(93)`: counts consumed materials via running max/rollback
  (`h` high-water mark vs current count → `i` total used).
- A 1 Hz scheduled task formats a `HH:MM:SS` session timer.
- Overlay (`OverlayPanel`, ABOVE_WIDGETS, bottom-left): draws a bundled PNG
  (`/overlay.png`), the current furniture item image (`ItemManager.getImage`,
  pasted at pixel (323,115)), the timer/XP/actions/materials texts at fixed pixel
  coordinates, using the bundled Audiowide font with black-outlined glyph fills.
  (The braille-art `nothing_to_see_here` arrays in `e.a`/`a.b` are decoys — the
  real artwork is the PNG resource.)

## 8. Obfuscation layers observed

1. Class/method identifier scrambling (all `a`/`b`/`c`).
2. String encryption, two layers:
   - LCG-XOR (same `n*1664525+1013904223; c ^= n>>>16` scheme as the launcher,
     per-class seeds) for short constants;
   - second layer for long strings: `data[i] ^= str(seedInt).getBytes()[i%len] ^ keyBytes[i%len]`,
     decoded as UTF-16 **big-endian**, where `seedInt` is itself hidden behind
     `Integer.parseInt(obfuscatedString)` and mutated through opaque predicates.
3. Control-flow flattening: `switch` on opaque predicate results
   (`qqrnfthdchipcafl.vkgridvuyyuvyqbi(n)`), `while(true)` spin blocks,
   dead XOR mutations — designed to break decompilers (CFR emits "Unable to
   fully structure code").
4. Junk classes (`xdjkqfomzkutdtcl.qqrnfthdchipcafl` bit-shuffle math) and decoy
   field names (`nothing_to_see_here`).
