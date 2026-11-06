# 04 — Gotchas: the Things That Bite

> Collected from the case-study analysis, the porting work, and the API
> verification pass. Each item is one of: a rule (client thread), a trap
> (silent nulls), or a version trap (renamed APIs).

## 1. The client-thread rule

- `@Subscribe` game-event handlers run on the client thread. **Key listeners,
  `MenuEntry.onClick` consumers, executors, and Swing do not.** Any code that
  touches `Client` from those must be wrapped in `clientThread.invokeLater(...)`.
  The case-study plugin funneled *every* game mutation through a single
  executor that hopped to the client thread — a pattern worth copying when
  several sources converge.
- `clientThread.invoke(runnable)` runs **inline** if you're already on the
  client thread, otherwise enqueues; `invoke(BooleanSupplier)` re-runs until
  it returns true. `invokeLater` always enqueues. `invokeAtTickEnd` defers to
  the end of the current tick.
- Never block on the client thread (no sleeps, no network). The client's HTTP
  layer literally throws if used from the client thread/EDT ("Blocking network
  calls are not allowed on the client thread").

## 2. Null player / not-logged-in traps

- `client.getLocalPlayer()` is null outside `LOGGED_IN` (login screen,
  hopping, loading). Every handler starts with the two-line guard.
- During hops, events keep firing with a stale/absent player; wipe session
  state on `LOGIN_SCREEN` and again on `CONNECTION_LOST`/`HOPPING` if your
  state is location-dependent.
- Widgets: `client.getWidget(...)` returns null for not-loaded groups, and a
  non-null widget can still be `isHidden()` — check both before reading text
  or children.

## 3. Scene coordinates and instances

- Three coordinate systems in play: scene (tile indices 0–103), local
  (`LocalPoint`, 128 units per tile), world (`WorldPoint`). Mixing them is the
  #1 geometry bug. Convert only through the API helpers — never shift raw
  ints.
- **Player-owned houses are instances**: the *world* coordinate of your
  furniture differs from other players' views. Use
  `WorldPoint.toLocalInstance(worldView, wp)` / `fromLocalInstance(...)` when
  comparing POH positions, exactly as the case study does. Spawn-event tile
  comparisons inside a house should use **scene-local** points
  (`getSceneMinLocation()` for game objects; the tile's `getSceneLocation()`
  for wall/decorative objects) — comparing world points across instance
  chunks silently fails.
- TileObject.getHash() packs worldView/plane/scene x/y/id — decode with the
  documented bit layout if you need it, but prefer `getSceneMinLocation()`.

## 4. Menu timing

- The context menu is rebuilt every tick — injected entries must be re-added
  per `ClientTick` with a de-dup guard, not once.
- `PostMenuSort` fires only **while the menu is not open**; it decides the
  left-click default. Re-pinning on every sort (as the case study does) keeps
  the entry stable whether or not the menu ever opens.
- `MenuOptionClicked` fires before the action executes; `consume()` blocks it.
  Don't consume clicks you didn't create.
- When constructing a real-action entry (`GAME_OBJECT_*`, `NPC_*`), params
  differ per family: world actions take scene coords in param0/param1 and the
  entity id in identifier; component actions take child index / packed widget
  id. Copy from an entry the client itself produced (log
  `MenuOptionClicked.getMenuEntry()` during a manual play) rather than
  guessing — this is exactly how the case-study constants were validated.

## 5. Version traps (1.13.6 specifics)

- `invokeMenuAction` does not exist — the method is
  `client.menuAction(int,int,MenuAction,int,int,String,String)` (since 1.10.30).
- `GAME_OBJECT_FIFTH_OPTION` has id **1001**, not 5; RUNELITE family is
  **1500–1506** (`RUNELITE_OVERLAY`=1501); `CC_OP`=57. Don't hardcode ids —
  use the enum — but know them when reading captures/logs.
- `GameObjectChanged` event no longer exists (a changed tile = Despawned +
  Spawned pair). It's `NpcSpawned` (lowercase 'pc'), not `NPCSpawned`.
- Items: `getNote()` returns **799 when the composition is a noted variant,
  -1 otherwise**; `getLinkedNoteId()` gives the partner. `Item` has no
  slot field — slot = index into `getItems()`.
- Widgets: `WidgetInfo`/`WidgetID` are deprecated (and banned from plugin-hub
  builds) — use `InterfaceID`/`ComponentID` (or `gameval.InterfaceID`). Packed
  id = `group << 16 | child`.
- There is no `PanelComponent.setTextLines` — add `LineComponent.builder()` children.
- `OverlayPriority` is deprecated — use float `setPriority(float)`.
- External plugin packaging: `runelite_plugin.json` resource
  (`{"plugins": ["fq.clazz.Names"]}`), not a manifest attribute.

## 6. Plugin lifecycle traps

- Unregister everything you register (`EventBus`, `KeyManager`, overlays,
  executors) in `shutDown()`, or plugin toggling leaks duplicate handlers.
- `startUp`/`shutDown` are invoked on the **Event Dispatch Thread** — do not
  touch `Client` directly there except through `clientThread.invokeLater`.
- Config default methods: changing a `@ConfigItem`'s `keyName` leaves the old
  value orphaned in profiles — rename carefully.
- Event-bus method naming is enforced at registration: the method must be
  named exactly `on` + event simple name (`onGameObjectSpawned`, not
  `onSpawned`).

## 7. External-plugin loading quirks

- External jars load child-first; they must NOT bundle `net.runelite.**`
  classes. Shared libs (guava/guice/okhttp/slf4j) come from the client.
- Resources: load via `getClass().getResourceAsStream("/...")` — jars stay
  packed.
- Plugin jars go in `~/.runelite/plugins`; developer side-loading needs
  `--developer-mode`.
- `@PluginDependency` chains share child injectors — a missing dependency
  fails instantiation of the dependent plugin, not the whole client.

## 8. Behavioral traps (philosophy restated in gotcha form)

- A wall-clock timer that fires actions is the single most detectable thing
  you can build. If you wrote `Thread.sleep` between actions, delete the
  feature and redesign around a game event.
- Retrying an action on anomaly desyncs you from the server — abort-and-reset
  instead (the case study has eleven reset triggers and zero retries).
- Never send an action for a state you haven't observed (no speculative
  clicks: confirm the interface loaded before "selecting" an option).
- One click = one bounded sequence: if your sequence can run without any
  click, you've written a bot, not a helper.
