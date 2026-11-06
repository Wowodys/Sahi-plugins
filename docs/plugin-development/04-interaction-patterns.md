# 04 — Interaction Patterns: Build/Remove Loops, Spawn Tracking, Inventory, NPC Flows

> Reusable implementations of the patterns the case-study plugin uses, in
> vanilla-API form (1.13.6 signatures). Each pattern names the events it
> consumes and its reset triggers — the two things that decide whether a
> plugin behaves like a player (see
> [01-design-philosophy.md](01-design-philosophy.md)).

## 1. Build → wait → remove → rebuild loop

The canonical one-click loop. Data model: two FIFO queues of *sites*
(`TileObject` + scene `Point`), a pending-requirements list, and one "armed"
flag.

```java
// capture intent — the ONE real click
@Subscribe
private void onMenuOptionClicked(MenuOptionClicked event)
{
    if (event.getMenuOption().equals("Build")            // vanilla "Build" op
        || event.getMenuOption().contains(MARKER))       // our injected entry
    {
        chosenObjectId = event.getMenuEntry().getIdentifier();  // the spot
        armed = true;   // reset triggers: see §1.3
    }
}

// confirm the click landed — the interface appeared (server-driven UI)
@Subscribe
private void onWidgetLoaded(WidgetLoaded event)
{
    if (event.getGroupId() == BUILD_GROUP)   // 458 "What would you like to build?"
    {
        pendingSites.clear();
        pendingSites.addAll(findSites(chosenObjectId));      // §1.1
        requirements = parseRequirements(client.getWidget(458, 3).getText()); // §1.2
    }
}
```

### 1.1 Finding sites — scene iteration (1.13.6 has no query API)

Objects live in the scene tiles; iterate all three kinds and keep the nearest:

```java
WorldView wv = client.getTopLevelWorldView();
Tile[][][] tiles = wv.getScene().getTiles();
Player me = client.getLocalPlayer();

for (int plane = 0; plane < tiles.length; plane++)
  for (int x = 0; x < tiles[plane].length; x++)
    for (int y = 0; y < tiles[plane][x].length; y++)
    {
        Tile t = tiles[plane][x][y];
        if (t == null) continue;
        for (GameObject g : t.getGameObjects())
            if (g != null && g.getId() == objectId) candidates.add(g);
        WallObject w = t.getWallObject();       // singular per tile
        DecorativeObject d = t.getDecorativeObject();
        // … same id check; compare via t.getSceneLocation() / getWorldLocation()
    }
// nearest: candidates.min(comparing(o -> o.getWorldLocation().distanceTo(me.getWorldLocation())))
```

Remember the object kinds differ: `GameObject` (multi-tile, use
`getSceneMinLocation()`), `WallObject`/`DecorativeObject` (singular per tile,
compare via the tile's own `getSceneLocation()`). Pick the kind by what the
in-game object actually is (spot = decorative in Rimmington POH; the well =
wall object).

### 1.2 Parsing the build requirements text

Widget (458, 3)-family text is `<br>`-separated `Name: qty` lines with
parenthesised level qualifiers:

```java
static List<Requirement> parse(String text)
{
    List<Requirement> out = new ArrayList<>();
    text = text.replace("<br>(", " (").replace(")<br>", ") ");
    for (String line : text.split("<br>"))
    {
        String[] parts = line.split(":");
        if (parts.length == 2)
            out.add(new Requirement(parts[0].replaceAll("\\(.*\\)", "").trim(),
                                    Integer.parseInt(parts[1].trim())));
    }
    return out;
}
```

Grab the furniture icon from the option widget's `getItemId()` (child 4) for
the overlay.

### 1.3 The spawn-confirmation trick

After the build click, the furniture appears as a **spawned object** — that is
the server's confirmation, and the same event drives the remove step:

```java
@Subscribe
private void onGameObjectSpawned(GameObjectSpawned event)   // also Decorative/Wall variants
{
    if (client.getVarbitValue(INSIDE_POH_VARBIT) != 1) return;   // 2176 gate
    TileObject obj = event.getGameObject();
    Point scene = obj.getSceneMinLocation();
    if (obj.getClickbox() == null) return;

    if (builtQueue.removeIf(site -> site.matches(obj))) {       // same id or same tile
        removeQueue.add(new BuildSite(obj, scene));             // built → now remove it
    } else if (pendingQueue.removeIf(site -> site.matches(obj))) {
        builtQueue.add(new BuildSite(obj, scene));
    }
    // double-mode: when exactly one bench exists and removeQueue empty,
    // scan for the twin within 2 tiles and queue it too
}
```

`matches(obj)` = same id **or** same scene tile — identity flips as the game
respawns the spot content. Each removal completion feeds the next build; the
loop runs until materials run out (→ Rule 6: stop and surface) or a reset
trigger fires. **No timers anywhere**: the pacing is the server's build time,
delivered via the spawn event.

## 2. Inventory accounting (container 93)

```java
private static final int INV = InventoryID.INVENTORY.getId();   // 93

@Subscribe
private void onItemContainerChanged(ItemContainerChanged event)
{
    if (event.getContainerId() != INV) return;
    ItemContainer inv = event.getItemContainer();   // fresh snapshot — use it, not client.getItemContainer
    int have = inv.count(unnotedMaterialId);
    // … compare against requirements, decide build / fetch-needed
}
```

Noted/unnoted semantics (verified on 1.13.6): `ItemComposition.getNote()` is
`799` if the composition **is** a noted variant, `-1` otherwise;
`getLinkedNoteId()` returns the linked (noted ↔ unnoted) partner id. So:

```java
static int unnotedId(int id)
{
    ItemComposition c = client.getItemDefinition(id);
    return c.getNote() == -1 ? id : c.getLinkedNoteId();
}
static boolean isNoted(int id) { return client.getItemDefinition(id).getNote() != -1; }
```

The servant-trip heuristic from the case study (keep unless you have a reason
to change it): an item whose per-build quantity ≥ 8 allows 2 pending builds,
otherwise 3 — `qty >= 8 ? 2 : 3` trips against servant capacity. Abort the
cycle if the material isn't in your fetch whitelist. The Phials path counts
the unnoted stack; if short, find the noted variant and "Use" it on Phials
(NPC 1614) — a normal `ITEM_USE_ON_NPC`-family interaction via menu entry.
`NpcSpawned(1614)` resets fetch-pending state.

## 3. NPC flows (servant fetch + pay)

1. Track the servant by id set {221, 223, 225, 227, 229}: cache on
   `NpcSpawned`, lazily re-query `worldView.npcs()` each GameTick only while
   the cache is null; clear on `NpcDespawned`.
2. Inject two extra menu entries on the NPC (fetch/pay) — same injection
   pattern as the main entry, real `NPC_*_OPTION` types or client-local types
   that then emit ordinary ops.
3. The pay step **verifies the dialog text** before paying: widget (231,6)
   must contain the tier's wage formatted `String.format(Locale.ENGLISH, "%,d", wage)`
   — and inventory coins (`inv.count(995)`) must cover it, else full reset.
   Never pay on dialog presence alone; read what the servant actually said.
4. Dialog text child for options messages: (229,1) is the generic "You don't
   have the right materials"-class statement — treat it as "re-run
   accounting", not as an error loop.

## 4. Varbit gating

Varbits are the cheapest reliable state signal — flip events are cheap and
exact:

```java
@Subscribe
private void onVarbitChanged(VarbitChanged event)
{
    if (event.getVarbitId() == INSIDE_POH && event.getValue() == 0) fullReset(); // left the house
    if (event.getVarbitId() == SERVANT_STATE && event.getValue() == 0) servantReset();
}
// reads: client.getVarbitValue(2176)
```

Notes: `VarbitChanged` carries `getVarbitId()`/`getVarpId()` (exactly one is
set, the other -1) and also fires for client-side pre-emptive changes — treat
reads as truth but don't treat one event as a command. The case study gates
*all* spawn handling on varbit 2176 == 1 (inside POH): one varbit read makes
the entire object-tracking subsystem inert outside the house. Find varbit ids
from other plugins' source or by observation (Settings-style plugins display
varbit changes live); `net.runelite.api.gameval.VarbitID` has the canonical
names in 1.13.x.

## 5. Widget/dialog state resets

Dialog groups loading/closing are natural reset triggers (Rule 4): keep the
set of groups your flow touches and clear the armed/awaiting flags when any of
them loads, and again when the modal closes (`WidgetClosed` group 219). This
is what makes an unexpected dialog *safe*: it resets the cycle instead of
letting stale intent fire later.

## 6. Object spawn vs changed: which events to subscribe

- `GameObjectSpawned` / `DecorativeObjectSpawned` / `WallObjectSpawned` (+ Despawned):
  the main confirmation signals for build loops.
- There is no `*Changed` event in 1.13.x (removed years ago) — a changed tile
  surfaces as Despawned+Spawned pair. Subscribe to both kinds you care about
  and treat Despawn of a queued site as "step invalidated" (reset that site).
- `NpcSpawned/Despawned` for servant/Phials tracking (by id; by name only when
  ids vary — the case-study vorkath plugin tracks "Zombified Spawn" by name).

## 7. Abort surfaces (one-liners)

- Overlay line: the plugin's own overlay shows `ABORTED: reason` — cheapest
  and always visible (Rule 6).
- Chat line: `client.addChatMessage(...)`-equivalent via
  `ChatMessageManager.queue(QueuedMessage.builder()...build())` with type
  CONSOLE — use sparingly, one line per abort.
- Never abort silently and never abort-and-retry silently: log-once, reset,
  wait for the next human click.
