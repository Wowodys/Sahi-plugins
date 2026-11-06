---
name: construction-plugin
description: >-
  Clean-room specification for building a portable, standalone RuneLite plugin
  that replicates the behavior of "Kuri Click Construction" (one-click
  Construction training) without any Kuri-platform dependencies. Use when the
  agent is asked to create, modify, or debug this plugin.
---

# construction_skill.md — Portable One-Click Construction Plugin for RuneLite

## 0. Scope & ground rules

Build a **standalone RuneLite external plugin** ("Click Construction") that
reimplements the behavior of the analyzed Kuri plugin, as a normal open plugin:
standard `net.runelite.*` APIs only. **Explicitly excluded** (do not port):

- Kuri plugin platform (`com.kuri.*` classes, `KuriPluginMetadata`, Kuri injector,
  Kuri `Menu`/`Inventory`/query APIs, `MenuConfig`)
- Kuri licensing/telemetry (`api.kuriplugins.com`: register, heartbeat, licenses,
  load-plan, KENC containers, rlicn native, hook catalogs)
- Obfuscation layers (string XOR/LCG, control-flow flattening, junk classes)

Everything the plugin does against the **game** is preserved; everything it did
against the **Kuri platform** is dropped.

## 1. Feature spec (what the user gets)

1. A custom, always-available right-click menu entry —
   `<col=8532cb>Click Construction</col>` — injected on menu targets, kept pinned
   at the bottom of the context menu.
2. Clicking it on/for a furniture build spot runs the full training loop
   autonomously: **Build → (fetch materials if missing) → wait for build →
   Remove → rebuild**, indefinitely until materials run out or a reset trigger fires.
3. Material logistics two ways:
   - **Phials** (Rimmington unnoter NPC): "use noted item on Phials" flow.
   - **Servant** (player-owned-house servant NPC): "Fetch from bank" flow incl.
     paying wages, with per-servant capacities and costs.
4. Optional: `doubleBenches` mode (build/remove two benches per cycle) and
   `fillWateringCans` mode (fill empty watering cans at the Rimmington well).
5. A stats overlay: session timer, XP gained, XP to next level, XP/hour,
   actions/hour, materials consumed, current furniture icon.
6. Hotkeys `1`–`4` mirror the build interface's material options.

## 2. Project setup (RuneLite external plugin)

```gradle
plugins { id 'java'; id 'idea' }
group 'com.example.clickconstruction'
version '1.0.0'

repositories {
    mavenCentral()
    maven { url 'https://repo.runelite.net' }   // RuneLite artifacts
}

dependencies {
    compileOnly group: 'net.runelite', name: 'client',       version: '1.10.+'  // match installed client
    compileOnly group: 'net.runelite', name: 'runelite-api', version: '1.10.+'
    compileOnly group: 'com.google.inject',  name: 'guice',  version: '4.1.0', classifier: 'no_aop'
    compileOnly group: 'org.slf4j', name: 'slf4j-api', version: '1.7.25'
    annotationProcessor group: 'org.projectlombok', name: 'lombok', version: '1.18.30'
}
```

- Target Java 11 (RuneLite client JVM).
- Package layout:

```
com.example.clickconstruction/
├── ClickConstructionPlugin.java      // @PluginDescriptor entry, event wiring
├── ClickConstructionConfig.java      // @ConfigGroup("clickconstruction")
├── engine/BuildEngine.java           // core state machine + material logic
├── engine/Requirement.java           // (name, qty) record; parses build UI text
├── engine/BuildSite.java             // (TileObject, WorldPoint/scene Point)
├── engine/MaterialManager.java       // inventory accounting, Phials flow
├── engine/ServantManager.java        // servant NPC, fetch/pay, wages
├── engine/MenuInjector.java          // custom menu entry creation/sorting
├── stats/SessionStats.java           // xp/hour, actions/hour, timer, materials used
├── overlay/StatsOverlay.java         // OverlayPanel
└── hotkey/BuildHotkeyListener.java   // keys 1-4 → build interface options
```

- Plugin entry in the client: standard `@PluginDescriptor` (name
  `"Click Construction"`, description `"One click anywhere to train construction"`,
  `enabledByDefault = false`, tags `{"construction","skilling"}`).
- Guice: RuneLite injects `Client`, `ClientThread`, `KeyManager`, `OverlayManager`,
  `EventBus`, `ItemManager`, `ConfigManager` into plugins automatically — no
  custom injector needed (this replaces Kuri's `this.injector.getInstance(...)`).

## 3. Verified constant tables (exact values from the original)

### 3.1 Widgets

| id / group | Meaning |
|---|---|
| group **458** | Build interface ("What would you like to build?") |
| `(458,3)` = packed **30015491** | visibility probe: build interface option row is open |
| `(458, 4..7)` | material option slots for hotkeys 1–4; each option widget has child **3** = requirements text, child **4** = item icon |
| group **229**, child 1 | chat dialog; contains "You don't have the right materials" |
| group **231**, child 6 | servant dialog text (payment/fetch confirmations) |
| group **219** | "Remove" confirmation dialog |
| dialog group set {219, 229, 231, 233, 370, 458} | any of these loading = a UI step completed (clear transient flags) |

### 3.2 Varbits

| varbit | Meaning |
|---|---|
| **2176** | 1 = inside the player-owned house (gate for all spawn-event logic); 0 = left house (reset) |
| **6719** | servant state; 0 → reset servant tracking |

### 3.3 NPCs

| id | Entity |
|---|---|
| **1614** | Phials (Rimmington item unnoter) — spawn resets Phials fetch state |
| **221 / 223 / 225 / 227 / 229** | Servants: Rick / Maid / Cook / Butler / Demon butler |

Servant table (`ServantTier` enum — values verified by running the original):

| Tier | NPC id | Greeting | Items per trip | Wage (coins) |
|---|---|---|---|---|
| RICK | 221 | "Yes, guv?" | 6 | 500 |
| MAID | 223 | "Yes?" | 10 | 1,000 |
| COOK | 225 | "What now?" | 16 | 3,000 |
| BUTLER | 227 | "Yes, madam?" | 20 | 5,000 |
| DEMON_BUTLER | 229 | "I am at thy command, my master." | 26 | 10,000 |

### 3.4 Items & objects

| id(s) | Meaning |
|---|---|
| container **93** | player inventory |
| **995** | coins (wage payment) |
| **5331** | empty watering can |
| **5333–5340** | watering cans with water |
| **1540** | the well (wall object) at WorldPoint(2962, 3213, 0) — Rimmington |
| **{29136, 29139}** | the two double-bench furniture pieces |
| (2950, 3214, 0) / (2966, 3209, 0) | Rimmington POH-area reference points |

### 3.5 Material whitelist (servant fetch / accounting applies to these)

`Plank, Oak plank, Teak plank, Mahogany plank, Steel bar, Limestone brick,
Marble block, Gold leaf, Magic stone, Soft clay, Bolt of cloth`

### 3.6 User-facing strings

- Menu option: `<col=8532cb>Click Construction</col>` (original used the "Kuri"
  prefix; keep a "Kuri"-free name but retain: any internal check for "our entry"
  must match the same constant used at injection).
- Dialog detection: `"You don't have the right materials"` (229,1);
  `"You need a watering can with water in order to plant garden plants"` (chat).

## 4. Runtime architecture & algorithms

All game mutations happen on the client thread: use
`clientThread.invokeLater(Runnable)` for everything (this replaces the original's
single-thread `ExecutorService` + `ClientThread.invoke`).

### 4.1 MenuInjector (the "one click")

Every `ClientTick` (logged in only):

1. Read `client.getMenu().getMenuEntries()`.
2. If the last entry's option already contains our marker substring
   ("Click Construction"), do nothing.
3. Otherwise create our entry appended to the menu:
   `MenuEntry e = client.getMenu().createMenuEntry(-1);`
   `e.setOption("<col=8532cb>Click Construction</col>");`
   `e.setTarget("");` and mark it as a **plugin-local** action — vanilla-safe
   choices: `MenuAction.RUNELITE_OVERLAY` (routes the click back to
   `MenuOptionClicked` without emitting a game packet).
4. On `PostMenuSort`: find our entry and **swap it with the last index** of the
   array, then `client.getMenu().setMenuEntries(arr)` (pins it to the bottom,
   exactly like the original's swap-to-last).

### 4.2 Click capture (`MenuOptionClicked`)

- Option equals `Build` → store `getMenuEntry().getIdentifier()` = the furniture
  spot id; when the build interface loads, parse requirements.
- Option contains our marker (and the action is `RUNELITE_OVERLAY`) → arm a cycle:
  the entry's target/identifier (whatever object the menu was opened on) is the
  intended build site; also accept `MenuAction.UNKNOWN` as the original did.

### 4.3 Build interface handling (`WidgetLoaded` group 458)

1. Clear the build-site queue, then locate the site(s):
   - search the scene (`client.getTopLevelWorldView().gameObjects()` /
     `.wallObjects()` / `.decorativeObjects()`) for the captured object id,
     nearest to `client.getLocalPlayer()`;
   - `doubleBenches` mode: queue **both** bench objects (ids 29136/29139) within
     2 tiles of the player.
2. Parse requirements from the option widget (458, 4+n).child(3) text:
   ```
   text = text.replace("<br>(", " (").replace(")<br>", ") ")
   for line in text.split("<br>"):
       parts = line.split(":"); if len(parts)==2:
           name = strip whitespace + parenthesised suffixes from parts[0]
           qty  = int(parts[1])
   ```
3. Read option child(4) `getItemId()` → current furniture icon for the overlay.

### 4.4 Hotkeys 1–4 (`KeyListener` via `KeyManager`)

On `keyPressed` (forward onto the client thread): if widget (458,0) is visible
and the key char is '1'..'4', resolve widget `(458, 4 + digit)` and treat it as
the selected material option — identical to the original's handler.

### 4.5 Material accounting (`ItemContainerChanged`, container 93)

Given pending `Requirement{name, qty}`:

- **Phials path (useServant=false)**: count unnoted `name` items in inventory.
  - If `count >= qty` → build.
  - Else → find the **noted** variant in inventory; if its quantity ≥ remaining
    need → run the Phials flow (below) and re-account when the inventory changes.
- **Servant path (useServant=true)**:
  - The material must be in the whitelist (§3.5), else abort the cycle.
  - Compare needed qty against servant capacity × trips (original rule: an
    item whose per-build qty ≥ 8 allows 2 pending builds, else 3); if the noted
    stock can't cover it → mark fetch-needed; servant carries it from the bank.
  - Items arrive via `ItemContainerChanged(93)` → re-account.
- On "You don't have the right materials" (widget 229,1): re-run accounting.

### 4.6 Phials flow

1. Find noted stack in inventory (linked note id: for a noted item,
   `itemComposition.getLinkedNoteId()`; for an unnoted one, `getNote() != -1`).
2. Issue menu action "Use" noted item → target Phials (NPC 1614), i.e. a normal
   `Use ... -> Phials` interaction (via `invokeMenuAction` or a constructed
   MenuEntry the player clicks — pick one style and stay consistent).
3. Dialog completes → unnoted materials appear in inventory → `ItemContainerChanged`
   re-accounts. `NpcSpawned(1614)` resets any pending Phials state.

### 4.7 Servant flow (`ServantManager`)

1. Track the servant NPC by id ∈ {221,223,225,227,229} (spawn events + nearest-NPC
   query fallback each GameTick).
2. Inject two extra menu entries on the servant: **"Servant: Fetch from bank"**
   and **"Servant: Pay wage"** (same injection mechanism as §4.1).
3. On fetch click: servant walks to bank and returns with items; the payment
   dialog (231,6) appears — verify its text contains the tier's wage formatted
   with `String.format(Locale.ENGLISH, "%,d", wage)`; if `inventory.coins < wage`
   → abort cycle (and optionally surface a chat message).
4. Pay → items arrive → §4.5 re-accounts.
5. Reset servant state on: varbit 6719→0, servant NPC despawn/respawn, widget
   group ∈ {221,223,225,227,229} loading.

### 4.8 Build → remove → rebuild state machine

States: `IDLE → ARMED(site) → INTERFACE_OPEN(requirements) → PLANNED →
[BUILDING] → BUILT(site) → REMOVING → (loops to ARMED)`.

- After selecting the material, the game starts construction; on completion the
  built furniture **spawns** as a new object.
- Gate all spawn handling on **varbit 2176 == 1** (inside house) and
  `object.getClickbox() != null`.
- Maintain two FIFO queues of `BuildSite`:
  - *removeQueue*: on spawn, if the new object matches a pending site (same id,
    or same tile via scene location), enqueue for removal; `doubleBenches`: when
    exactly one bench exists and removeQueue is empty, queue the matching second
    bench within 2 tiles.
  - *rebuildQueue*: spawned objects that match rebuildQueue sites flip back into
    removeQueue (this alternation is the build→remove→rebuild cycle).
- For each site in removeQueue: issue the `Remove` menu action on it (object op),
  handle the group 219 confirm dialog, wait for despawn, then issue `Build` again
  on the same spot; loop.

### 4.9 Reset/abort triggers (all of them — match exactly)

| Trigger | Reset |
|---|---|
| `NpcSpawned` id 1614 | clear Phials fetch-pending |
| `WidgetLoaded` group ∈ {219,229,231,233,370,458} | clear click-cycle flags |
| `WidgetClosed` group 219 | clear click-cycle flags |
| `VarbitChanged` 6719 → 0 | clear servant state |
| `VarbitChanged` 2176 → 0 (left house) | clear fetch/build state |
| Inventory: outside house (2176==0) and no empty can (5331) | clear can-fill state |
| Chat message contains "You need a watering can with water in order to plant garden plants" | clear can-fill state |
| Materials exhausted (Phials) or servant cannot fetch | stop cycle, clear queues |

Full reset = clear both queues, all pending ids/widgets/flags, counters.

### 4.10 Watering-can mode (`fillWateringCans`)

Each GameTick while enabled: locate wall object **1540** at WorldPoint(2962,
3213, 0) (Rimmington well); count empty (5331) vs full (5333–5340) cans; drive
fill interactions until the cans are full; the same reset triggers apply (chat
message, leaving the house area).

### 4.11 Stats & overlay

`SessionStats` (subscribe to `StatChanged` / `ItemContainerChanged(93)` /
`GameStateChanged`):

- On login (+1.2s delay): snapshot construction level, XP.
- On each Construction XP increase: `delta = xp - lastXp`; timestamp the pair of
  drops → session duration; `xpToNext = Experience.getXpForLevel(level+1) - xp`;
  `actionsPerHour = 1.0 / (secondsBetweenDrops / 3600.0) * delta`.
- Materials used: track a high-water mark of the current material count; each
  drop from the mark adds to a running total.
- 1 Hz `ScheduledExecutor` formats `HH:MM:SS`.

`StatsOverlay` (`OverlayPanel`, position `BOTTOM_LEFT`, layer `ABOVE_WIDGETS`,
config-gated): render timer, XP gained, XP/hr, actions/hr, materials used, and
the current furniture icon via `itemManager.getImage(itemId)`.

## 5. Vanilla-API mapping (what replaced the Kuri internals)

| Original (Kuri) | Portable replacement |
|---|---|
| `KuriPluginMetadata` / `Plugin` | `@PluginDescriptor` + `net.runelite.client.plugins.Plugin` |
| Kuri injector (`injector.getInstance`) | RuneLite Guice constructor injection |
| Kuri `Menu` / `MenuConfig` / `Menu.setTemp` | `client.getMenu().createMenuEntry(-1)` + `PostMenuSort` reordering |
| Kuri `Inventory` | `client.getItemContainer(InventoryID.INVENTORY)` (93) |
| Kuri `WallObjectQuery`/`GameObjectQuery`/`DecorativeObjectQuery` | iterate `client.getTopLevelWorldView().wallObjects()/gameObjects()/decorativeObjects()` with id/location filters |
| Kuri `NPCQuery` | `client.getTopLevelWorldView().npcs()` |
| Kuri `ClientThread.invoke` | `clientThread.invokeLater` |
| Kuri `IdentifiedItem` | `Item` + `client.getItemDefinition(id)` (note id: `getLinkedNoteId()`) |
| Kuri hooks catalog (obfuscated client offsets) | **not needed** — RuneLite API already abstracts the game client |
| Kuri licensing/KENC/heartbeat | **not needed** |

## 6. Acceptance checklist (in-game test plan)

1. Rimmington POH, inventory with noted planks + coins; enable plugin.
2. Right-click a building spot → `<col=8532cb>Click Construction</col>` is present
   and pinned to the bottom; clicking it opens the build interface and picks the
   option matching inventory materials.
3. With insufficient unnoted planks: noted planks are used on Phials; unnoted
   planks return; build proceeds.
4. With a servant: fetch requested, wage dialog verified and paid, items arrive,
   build proceeds.
5. Built furniture is removed and rebuilt continuously; double-bench mode builds
   both benches.
6. Stats overlay counts XP, XP/h, actions/h, timer, materials used; icon updates.
7. Keys 1–4 select build options while the interface is open.
8. Leaving the house / servant dismissal / running out of materials cleanly stops
   the loop (no stuck state); re-entering starts fresh.
9. Watering-can mode fills cans at the Rimmington well.

## 7. Known subtleties (do not skip)

- The original checks "our entry contains 'Kuri'" in three places (skip-if-last,
  PostMenuSort finder, MenuOptionClicked matcher) — use one constant (e.g.
  "Click Construction") consistently for all three.
- Build requirement text uses `<br>` separators and parenthesised qualifiers
  (e.g. "Oak plank (3)") — strip exactly as in §4.3 before `split(":")`.
- The noted↔unnoted conversion uses `getLinkedNoteId()` / `getNote() == -1`
  semantics; test with both directions.
- The spawn-handler tile comparison uses **scene-local** points
  (`getSceneMinLocation()` for game objects, `tile.getSceneLocation()` for
  wall/decorative) — compare consistently.
- `n >= 8 ? 2 : 3` multiplier (pending-build allowance) in servant accounting is
  empirical from the original — keep it unless the user asks to change it.
- All event handlers must no-op safely when `client.getLocalPlayer() == null` or
  `GameState != LOGGED_IN`.
