# 03 — Events Reference for One-Click Plugins

> Event classes live in `net.runelite.api.events` (runelite-api jar, verified
> present in 1.13.6). Fields below verified with `javap`. Handlers: `@Subscribe`
> on a method named `on<EventSimpleName>`, void, exactly one parameter,
> registered via `eventBus.register(this)` in `startUp()`.

## Delivery model

The bus is **synchronous** — `post()` invokes subscribers immediately on the
posting thread. Game events are posted from the client thread in this rough
per-tick order:

```
game packets processed (server state applied)
→ entity spawn/despawn walk  (GameObjectSpawned, NpcSpawned, ItemSpawned, …)
→ per-tick events            (ItemContainerChanged, VarbitChanged, ChatMessage,
                              StatChanged, MenuOptionClicked, …)
→ GameTick
→ PostClientTick
```

ClientTick fires per client frame (~20 ms cycle); GameTick per game tick
(~0.6 s). UI-origin events (ConfigChanged, FocusChanged) post from the EDT.
Write handlers that read state, mutate only your own fields, and hop threads
via `clientThread.invokeLater` when needed.

## The core set for one-click plugins

| Event | Fields (verified) | Use |
|---|---|---|
| `MenuOptionClicked` | `getMenuEntry()`, `getMenuAction()`, `getMenuOption()`, `getMenuTarget()`, `getId()`, `getParam0()`, `getParam1()`, `getWidget()`, `getWidgetId()`, `getItemId()`, `getItemOp()`, `isItemOp()`, `consume()`, `isConsumed()` | capture the one real click; arm cycles; detect any op the player performs |
| `PostMenuSort` | (empty) | reposition your injected entry; fires only while the menu is closed |
| `MenuEntryAdded` | wraps `MenuEntry` (option/target/type/identifier/params) | decorate others' entries (id chips etc.) |
| `MenuOpened` | `getMenuEntries()` (reversed — last = top), `getFirstEntry()` | enrich the open menu |
| `ClientTick` | (empty) | menu injection loops, per-frame polling |
| `GameTick` | (empty) | once-per-tick state machine steps; re-validate conditions |
| `GameObjectSpawned` | `getTile()`, `getGameObject()` | build-completion confirmation; site queues |
| `GameObjectDespawned` | `getTile()`, `getGameObject()` | invalidate queued sites (no *Changed event exists) |
| `DecorativeObjectSpawned` / `WallObjectSpawned` (+ Despawned) | `getTile()`, `getDecorativeObject()` / `getWallObject()` | POH furniture is decorative; wells/walls are wall objects |
| `NpcSpawned` / `NpcDespawned` | `getNpc()` | servant / Phials tracking (spawn = natural reset trigger) |
| `NpcChanged` | `getNpc()`, `getNpcComposition()` | NPC transforms |
| `ItemContainerChanged` | `getContainerId()`, `getItemContainer()` | inventory accounting (filter `== 93`); equipment 94; bank 95 |
| `WidgetLoaded` | `getGroupId()` | build interface (458), dialog groups (219/229/231/233/370) — UI-step confirmations |
| `WidgetClosed` | `getGroupId()`, `getModalMode()`, `isUnload()` | modal cleanup → clear click states |
| `VarbitChanged` | `getVarbitId()`, `getVarpId()` (one is -1), `getValue()`, `getIndex()` (legacy packed) | location/state gates (2176 inside-POH), servant state (6719) |
| `ChatMessage` | `getMessageNode()` (`getValue()`, `getType()`), `getType()` | server text ("You don't have the right materials…") — confirmations and abort triggers |
| `StatChanged` | `getSkill()`, `getXp()`, `getLevel()`, `getBoostedLevel()` | XP drops → xp/hr, actions/hr |
| `GameStateChanged` | `getGameState()` | LOGIN_SCREEN → wipe session state; LOGGED_IN → snapshot xp |
| `ConfigChanged` | `getGroup()`, `getKey()`, `getOldValue()`, `getNewValue()` | react to own config toggles |
| `AnimationChanged` | `getActor()` (read `actor.getAnimation()`) | animation tracking (settings plugin dedupes per actor) |
| `HitsplatApplied` | `getActor()`, `getHitsplat()` | combat bookkeeping |
| `InteractingChanged` | `getSource()`, `getTarget()` | who is attacking whom |
| `ItemSpawned` / `ItemDespawned` / `ItemQuantityChanged` | `getTile()`, `getId()`, `getQuantity()` | ground-item flows |
| `ProjectileMoved` | `getProjectile()` (`getId()`, target, inter-end point) | hazard prediction (vorkath acid/fire) |
| `GraphicsObjectCreated` | `getGraphicsObject()` (`getId()`, `getLocation()`) | tile effects |

## Rules for handlers

1. **Guard at the top**: `client.getGameState() != GameState.LOGGED_IN` or
   `client.getLocalPlayer() == null` → return. Most NPEs in plugins are a
   missing one of these two lines.
2. **Filter first**: one `if` for the event's identity field (container id,
   widget group, npc id, varbit id) before any work.
3. **No ordering assumptions between events** beyond the per-tick order above;
   never assume two events arrive in the same tick pair deliberately — pair
   steps via explicit flags instead.
4. **One pending outbound action at a time** (philosophy Rule 5): handlers set
   intent; a single point (usually the GameTick/ClientTick step) converts
   intent to a menu entry.
5. `MenuOptionClicked.consume()` cancels the action — with great power comes
   "your plugin just ate the player's click"; the case-study plugins never
   consume.
6. `VarbitChanged` also fires for client-side pre-emptive changes (per the
   javadoc) — treat reads (`client.getVarbitValue`) as truth, events as hints.
7. Don't unregister mid-event or post re-entrant events from the same event —
   the bus is a plain synchronous loop.

## Events deliberately NOT used here (and why)

- `ChatInput`-family / typed-text events — not needed for one-click flows.
- `ScriptPreFired/ScriptPostFired`, `ScriptCallbackEvent` — client-script
  hooks; powerful but easy to misuse; nothing in the case study needs them.
- `FakeXpDrop` — ignore for stats (use real `StatChanged`).
- `GrandExchangeOfferChanged` etc. — unrelated domain.

Complete class list (1.13.6 jar listing): AccountHashChanged, ActorDeath,
AmbientSoundEffectCreated, AnimationChanged, AreaSoundEffectPlayed,
BeforeMenuRender, BeforeRender, CanvasSizeChanged, ChatMessage,
ClanChannelChanged, ClanMemberJoined/Left, ClientTick, CommandExecuted,
DecorativeObjectDespawned/Spawned, FakeXpDrop, FocusChanged,
FriendsChatMemberJoined/Left/Sync, GameObjectDespawned/Spawned, GameStateChanged,
GameTick, GrandExchangeOfferChanged/Searched, GraphicChanged,
GraphicsObjectCreated, GroundObjectDespawned/Spawned, HitsplatApplied,
InteractingChanged, ItemContainerChanged, ItemDespawned/QuantityChanged/Spawned,
MenuEntryAdded, MenuOpened, MenuOptionClicked, MenuShouldLeftClick,
NameableNameChanged, NpcChanged/Despawned/Spawned, OverheadTextChanged,
PlayerChanged/Despawned/MenuOptionsChanged/Spawned, PostAnimation,
PostClientTick, PostHealthBarConfig, PostItemComposition, PostMenuSort,
PostObjectComposition, PostStructComposition, PreMapLoad, ProjectileMoved,
RemovedFriend, ResizeableChanged, ScriptCallbackEvent, ScriptPostFired/
PreFired, SoundEffectPlayed, StatChanged, UsernameChanged,
VarClientIntChanged/StrChanged, VarbitChanged, VolumeChanged,
WallObjectDespawned/Spawned, WidgetClosed/Drag/Loaded, WorldChanged,
WorldEntityDespawned/Spawned, WorldListLoad.
