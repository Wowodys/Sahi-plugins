# 02 — API Cheatsheet: the Calls You Actually Use

> Every signature below was verified with `javap` against
> `sdk/jars/runelite-api-1.13.6-runtime.jar` and `client-1.13.6.jar`
> (class file major 55). Where public docs historically disagree, the jar wins.
> Cross-checked against the published javadocs (https://static.runelite.net/runelite-api/apidocs/)
> and RuneLite master on 2026-11-03.

## Client — state

```java
GameState getGameState()                    // UNKNOWN/STARTING/LOGIN_SCREEN/…/LOGGED_IN/CONNECTION_LOST/HOPPING
Player getLocalPlayer()                     // null outside LOGGED_IN — always null-check
int getTickCount()                          // server tick counter
int getGameCycle()                          // client cycle, +1 every 20ms
boolean isMenuOpen()
Varbit:  int getVarbitValue(int varbit);  int getVarpValue(int varp);
         int getVarbitValue(int[] varps, int varbit)
Item lookups:
    ItemComposition getItemDefinition(int itemId)      // note the name — not getItemComposition
    NPCComposition getNpcDefinition(int npcId)
Containers:
    ItemContainer getItemContainer(InventoryID)        // deprecated enum form
    ItemContainer getItemContainer(int id)             // plain id — 93 = inventory
```

## Client — menu (see docs/plugin-development/03 for full treatment)

```java
Menu getMenu()                              // .createMenuEntry(-1) / get-/setMenuEntries
MenuEntry createMenuEntry(int idx)          // deprecated alias of getMenu().createMenuEntry
void menuAction(int param0, int param1, MenuAction action,
                int id, int itemId, String option, String target)
                                            // ← the direct dispatch (NO invokeMenuAction in 1.13.x;
                                            //   renamed in 1.10.30, verified in every jar since)
```

## Client — scene / entities

```java
WorldView getTopLevelWorldView()            // since 1.11.0
WorldView getWorldView(int id)              // -1 = top level
Scene getScene()                            // default method = top-level scene
default int getPlane()
Deque<Projectile> getProjectiles()
```

`WorldView` (1.13.6): `npcs()`, `players()`, `worldEntities()` return
`IndexedObjectSet<? extends …>` (`byIndex(int)`, iterable, `.stream()`).
**There are no `gameObjects()`/`wallObjects()`/`decorativeObjects()` maps** —
objects live in tiles: `worldView.getScene().getTiles()[plane][x][y]` →
`Tile.getGameObjects()` (array), `getWallObject()`, `getDecorativeObject()`,
`getGroundObject()`, `getGroundItems()`, `getSceneLocation()`,
`getWorldLocation()`.

Coordinates (`net.runelite.api.coords`):

```java
LOCAL_TILE_SIZE = 128                       // Perspective; scene coord = local >> 7
LocalPoint.fromWorld(WorldView|Client|Scene, WorldPoint | x, y)
LocalPoint.fromScene(int x, int y [, Scene|WorldView])   // 2-arg form adds half-tile centering
LocalPoint.getSceneX()/getSceneY()
WorldPoint.fromLocal(Client|WorldView|Scene, LocalPoint | x, y, plane)
WorldPoint.fromLocalInstance(Client, LocalPoint)         // instances (POH!)
Collection<WorldPoint> WorldPoint.toLocalInstance(WorldView, WorldPoint)
WorldPoint.getRegionID();  wp.distanceTo(WorldPoint|WorldArea)
```

Entities:

```java
TileObject (GameObject/WallObject/DecorativeObject/GroundObject):
    long getHash()      // worldView>>52 | id>>20 | wall>>19 | type>>16 | plane>>14 | sceneY>>7 | sceneX
    int getId()         // already the transformed id — no getTransformedId() in 1.13.x
    WorldPoint getWorldLocation();  LocalPoint getLocalLocation();
    Point getSceneMinLocation()     // GameObject (also on TileObject); wall/deco → use the tile's location
    Shape getClickbox();  int getPlane()
GameObject: int sizeX()/sizeY(), int getOrientation()
NPC: int getId() (transformed), int getIndex(), NPCComposition getComposition()
Actor: getWorldLocation(), getAnimation(), getPoseAnimation()/getIdlePoseAnimation(),
       getInteracting(), getCombatLevel…
```

Object compositions: `ObjectComposition.getId()`, `getImpostorIds()` (multi-loc
transforms), `transform()`.

## Items

```java
ItemContainer: getId(), Item[] getItems(), Item getItem(int slot),
               boolean contains(int itemId), int count(int itemId),
               int size()  // all slots incl. empty
               int count()  // filled slots
               int find(int itemId)                // first slot index
Item: getId(), getQuantity()                        // slot == array index
ItemComposition: getId(), getName(), getNote()      // 799 if noted variant, -1 otherwise
                 getLinkedNoteId()                  // the noted↔unnoted partner
                 getPlaceholderId()                 // != -1 → placeholder (no getIsPlaceholder())
                 isStackable(), getPrice(), getHaPrice(), isTradeable()
InventoryID: @Deprecated enum (INVENTORY=93, EQUIPMENT=94, BANK=95, TRADE=90…)
             canonical constants now in net.runelite.api.gameval.InventoryID (INV=93, WORN=94, BANK=95)
```

## Overlays (client jar)

```java
OverlayPanel extends Overlay: render(Graphics2D) → Dimension; protected panelComponent
Overlay: setPosition(OverlayPosition)          // BOTTOM_LEFT, DYNAMIC, TOOLTIP, …
         setLayer(OverlayLayer)                // ABOVE_SCENE, UNDER_WIDGETS (default), ABOVE_WIDGETS, ALWAYS_ON_TOP
         setPriority(float)                    // 0–1; the OverlayPriority enum is deprecated
         setPreferredSize(Dimension); revalidate()
         drawAfterLayer(int groupId, int childId)
PanelComponent: getChildren() → List<LayoutableRenderableEntity>; setPreferredSize; setGap(Point)
LineComponent.builder().left(s).right(s)[.leftColor/.rightColor/.leftFont/.rightFont].build()
OverlayManager: add(Overlay), remove(Overlay), removeIf(Predicate)
```

## Events — the ones a one-click plugin needs

```java
GameTick                       // once per ~0.6s tick, after packets processed
ClientTick / PostClientTick    // per client frame (~20ms cycle)
MenuOptionClicked              // getMenuAction/getMenuOption/getMenuTarget/getId/getParam0/getParam1
                               // getWidget/getWidgetId/getItemId/getItemOp/getMenuEntry/consume()
PostMenuSort                   // after menu sort; only while menu NOT open (per javadoc)
MenuEntryAdded / MenuOpened    // decorate others' entries / menu displayed
GameObjectSpawned/Despawned    // getTile(), getGameObject() — also WallObject/DecorativeObject/
                               // GroundObject variants; no *Changed events in 1.13.x
NpcSpawned/NpcDespawned/NpcChanged   // (lowercase 'pc' — exactly these names)
ItemContainerChanged           // getContainerId(), getItemContainer()
WidgetLoaded                   // getGroupId()
WidgetClosed                   // getGroupId(), getModalMode(), isUnload()
VarbitChanged                  // getVarbitId()/getVarpId() (one is -1), getValue()
ChatMessage                    // getMessageNode(), getType()
StatChanged                    // getSkill(), getXp(), getLevel(), getBoostedLevel()
GameStateChanged               // getGameState()
ConfigChanged                  // getGroup(), getKey(), getOldValue(), getNewValue()
AnimationChanged, HitsplatApplied, InteractingChanged, ProjectileMoved …
```

## Services (client jar) — injectable

```java
ClientThread: invoke(Runnable) [inline if already on client thread]
              invoke(BooleanSupplier) [rerun until true]
              invokeLater(Runnable) / invokeLater(BooleanSupplier) / invokeAtTickEnd(Runnable)
EventBus: register(Object), unregister(Object), post(Object)   // synchronous; subscribers run on poster's thread
KeyManager: registerKeyListener(KeyListener)    // input.KeyListener; consumption = keyEvent.consume()
HotkeyListener (net.runelite.client.util): ctor Supplier<Keybind>; override hotkeyPressed()
ItemManager: getImage(int) → AsyncBufferedImage; getImage(int,int,boolean); getItemComposition(int)
ConfigManager: getConfiguration(group, key), setConfiguration(...), getConfig(Class)
ChatMessageManager: queue(QueuedMessage.builder()…build())
Notifier: notify(String)
```

## Plugin plumbing

```java
net.runelite.client.plugins.Plugin      // startUp()/shutDown() = empty protected (NOT abstract); implements Module
@PluginDescriptor(name, description, tags[], enabledByDefault, internalName, hidden,
                  conflicts[], loadInSafeMode, developerPlugin, …)
@Subscribe(priority = 0f)               // net.runelite.client.eventbus.Subscribe; float priority
                                        // method must be named on<EventSimpleName>, void, 1 param
@ConfigGroup("group") + @ConfigItem(keyName, name, description, position, section, hidden, warning, secret)
                                        // + @ConfigSection(name, description, position), @Range, @Alpha, @Units
```

## Version-trap quick list (changed names you'll meet in old docs)

| Old doc says | 1.13.6 reality |
|---|---|
| `client.invokeMenuAction(...)` | `client.menuAction(...)` with a `MenuAction` enum param (since 1.10.30) |
| `MenuEntry.setActionParam0/1` | `setParam0/1` |
| `TopLevelWorldView` type | only `WorldView` (interface) + `client.getTopLevelWorldView()` |
| `WorldView.gameObjects()` maps | scene tiles (`getScene().getTiles()`) |
| `GameObjectChanged` event | removed — Despawned+Spawned pair |
| `NPCSpawned` | `NpcSpawned` |
| `Item.getSlot()` | none — slot = index in `getItems()` |
| `getIsPlaceholder()` | `getPlaceholderId() != -1` |
| `TileObject.getTransformedId()` | none — `getId()` already transformed |
| `Varbits` in `net.runelite.api.constants` | `net.runelite.api.Varbits` (deprecated) → `net.runelite.api.gameval.VarbitID` |
| `WidgetInfo` enum | deprecated/banned in hub builds → `InterfaceID`/`ComponentID`/`gameval.InterfaceID` |
| `RuneLite-Plugin` manifest attr | `runelite_plugin.json` resource in the jar |
| `net.runelite.api.plugins.Plugin` | `net.runelite.client.plugins.Plugin` (client jar, since forever) |
| `KeyPressed` event / `InvokePriority` | gone — use `KeyManager`/`HotkeyListener` |
| `runelite.version` in build.gradle | `def runeLiteVersion = 'latest.release'` |
