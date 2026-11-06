# 03 — Menus and Widgets: Injection, Sorting, Clicks, Dialogs

> Signatures verified against 1.13.6 via `javap` on the sdk jars; numeric ids
> extracted from the `MenuAction` class bytecode. Sources:
> `sdk/jars/runelite-api-1.13.6-runtime.jar` (net.runelite.api),
> `sdk/jars/client-1.13.6.jar` (net.runelite.client).

## 1. The modern Menu API (1.13.6)

`client.getMenu()` returns `net.runelite.api.Menu`:

```java
public interface Menu {
    MenuEntry createMenuEntry(int idx);            // -1 = append at the end
    MenuEntry[] getMenuEntries();
    void setMenuEntries(MenuEntry[] entries);      // replaces the whole array
    void removeMenuEntry(MenuEntry entry);
    int getMenuX/Y/Width/Height();
}
```

(The older `client.getMenuEntries()`/`setMenuEntries()` pair still exists on
`Client` and is equivalent; the `Menu` interface is the current entry point —
it is also what Kuri's patched client re-used for its facade, which makes
ports straightforward.)

`MenuEntry` (all setters return the entry for chaining):

```java
setOption(String)      // left text  ("Build", "Use", "<col=..>Kuri Click …")
setTarget(String)      // right text ("Oak plank (3)", "@npc@Phials" — @ tags colorize)
setIdentifier(int)     // entity id (object id / npc index / widget item index)
setType(MenuAction)    // the action variant — see table
setParam0(int)         // sceneX for world actions; widget child id for CC_*
setParam1(int)         // sceneY for world actions; widget group<<16|child for CC_*
setForceLeftClick(boolean)
setDeprioritized(boolean)      // pushes entry toward the bottom/left-click fallback
setWorldViewId(int)
onClick(Consumer<MenuEntry>)   // client-local click callback — never a packet
// getters: getOption/getTarget/getIdentifier/getType/getParam0/getParam1,
// getItemId/getItemOp/getWidget/getNpc/getPlayer/getActor/getSubMenu
```

## 2. MenuAction — the full id table (1.13.6, extracted from bytecode)

| id | constant | typical params |
|---|---|---|
| -1 | `UNKNOWN` | plugin-local entries |
| 1 | `ITEM_USE_ON_GAME_OBJECT` | param0=item slot, param1=item id |
| 2 | `WIDGET_TARGET_ON_GAME_OBJECT` | |
| 3–6 | `GAME_OBJECT_FIRST..FOURTH_OPTION` | param0=sceneX, param1=sceneY, id=object id |
| 7 | `ITEM_USE_ON_NPC` | id=npc index |
| 8 | `WIDGET_TARGET_ON_NPC` | |
| 9–13 | `NPC_FIRST..FIFTH_OPTION` | id=npc index |
| 14–17 | `ITEM_USE_ON_PLAYER`, `WIDGET_TARGET_ON_PLAYER`, `ITEM_USE_ON_GROUND_ITEM`, `WIDGET_TARGET_ON_GROUND_ITEM` | |
| 18–22 | `GROUND_ITEM_FIRST..FIFTH_OPTION` | |
| 23 | `WALK` | param0/1 = scene x/y — **identity only** (1.13.x): the vanilla walk arm follows the mouse hover tile; steering a fixed tile needs `WorldView#dispatchDirectWalk` (scene walk state) |
| 24 | `WIDGET_TYPE_1` | |
| 25 | `WIDGET_TARGET` | |
| 26 | `WIDGET_CLOSE` | |
| 28–29 | `WIDGET_TYPE_4`, `WIDGET_TYPE_5` | |
| 30 | `WIDGET_CONTINUE` | param0=1 (or op), param1=packed widget id — dialog advance |
| 31–32 | `ITEM_USE_ON_ITEM`, `WIDGET_USE_ON_ITEM` | |
| 33–38 | `ITEM_FIRST..FIFTH_OPTION`, `ITEM_USE` | param0=item slot, param1=packed inv widget |
| 39–43 | `WIDGET_FIRST..FIFTH_OPTION` | param0=child, param1=packed widget id |
| 44–51 | `PLAYER_FIRST..EIGHTH_OPTION` | |
| 57 | `CC_OP` | generic component op: param0=op(-100 base), param1=packed widget |
| 58 | `WIDGET_TARGET_ON_WIDGET` | |
| 60 | `SET_HEADING` | |
| 63–67 | `WORLD_ENTITY_FIRST..FIFTH_OPTION` | |
| 998 | `RUNELITE_WIDGET` | |
| 999 | `RUNELITE_HIGH_PRIORITY` | |
| 1001 | `GAME_OBJECT_FIFTH_OPTION` | ← note: NOT 7, see 3–6 above |
| 1002–1006 | `EXAMINE_OBJECT/NPC/ITEM_GROUND/ITEM`, `CANCEL` | |
| 1007 | `CC_OP_LOW_PRIORITY` | |
| 1013 | `EXAMINE_WORLD_ENTITY` | |
| 1500–1506 | `RUNELITE`, `RUNELITE_OVERLAY`, `RUNELITE_OVERLAY_CONFIG`, `RUNELITE_PLAYER`, `RUNELITE_INFOBOX`, `RUNELITE_LOW_PRIORITY` | client-local only |

`MenuAction.of(int)` reverse-maps an id; `getType().getId()` forward-maps.
`RUNELITE_*` ids (1500+) never reach the game server — they are dispatched
inside the client (overlay menus, plugin-local entries).

## 3. Injecting a menu entry (the standard one-click pattern)

```java
@Subscribe
private void onClientTick(ClientTick event)
{
    if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;

    MenuEntry[] entries = client.getMenu().getMenuEntries();
    // de-dup guard: the menu is rebuilt every tick; only add if we're gone
    if (entries.length > 0 && entries[entries.length - 1].getOption().contains(MARKER)) return;

    client.getMenu().createMenuEntry(-1)
          .setOption("<col=8532cb>My Action</col>")
          .setTarget("")                       // or a real target name
          .setType(MenuAction.RUNELITE_OVERLAY) // client-local; OR a real op type
          .setIdentifier(0).setParam0(0).setParam1(0)
          .onClick(e -> clientThread.invokeLater(this::doThing));
}
```

Two flavors, pick per intent:

1. **Client-local entry** (`RUNELITE_OVERLAY` / `UNKNOWN` + `onClick`):
   click is visible in `MenuOptionClicked`, never sent to the server. Use for
   arming cycles, toggles, overlays-of-control.
2. **Real-action entry** (e.g. `GAME_OBJECT_FIFTH_OPTION` with a real object
   id and scene coords): clicking it makes the client perform the ordinary
   game action — exactly what the case-study plugin does so that "click our
   entry" = "click Build on the spot". Same packet, same UI trail, as normal
   play.

Rules of thumb (matching the case study):

- check `client.isMenuOpen()`/`getMenuEntries()` before mutating;
- use ONE marker constant for: the de-dup check, the PostMenuSort finder, and
  the click matcher (three places — one constant, or they drift);
- never edit entries you did not create (swappers aside); append yours and
  reposition (§4);
- entries added per-tick must be re-added whenever the menu rebuilds — the
  de-dup guard makes that idempotent.

## 4. PostMenuSort — pinning position

`PostMenuSort` fires after the client sorts the context menu and before clicks
are processed. Per the official javadoc it is *only fired while the menu is
not open* and "shouldn't be used as a general purpose client tick event" —
which is exactly why the case study re-pins its entry on **every** sort: the
periodic sort decides the left-click default action, and re-pinning there
keeps the injected entry in a stable slot whether or not the player ever
opens the full menu. This is the sanctioned moment to reorder:

```java
@Subscribe
private void onPostMenuSort(PostMenuSort event)
{
    MenuEntry[] entries = client.getMenu().getMenuEntries();
    int idx = indexOfMarker(entries);
    if (idx == -1 || idx == entries.length - 1) return;
    MenuEntry tmp = entries[idx];
    entries[idx] = entries[entries.length - 1];
    entries[entries.length - 1] = tmp;
    client.getMenu().setMenuEntries(entries);
}
```

The case study swaps its entry to the **last** slot (rendered as the
topmost/default left-click) every sort. The settings plugin demonstrates the
inverse (`priority = -100f` on the subscriber + Shift held → rotate to index 0
to demote). Use `setDeprioritized(true)` for lighter "push down" behavior
without full reordering.

## 5. Capturing clicks — MenuOptionClicked

```java
@Subscribe
private void onMenuOptionClicked(MenuOptionClicked event)
{
    // event.getMenuAction(), getMenuOption(), getMenuTarget(), getId(),
    // getParam0(), getParam1(), getWidget(), getWidgetId(), getItemId(),
    // getItemOp(), getMenuEntry(), isConsumed()/consume()
    if (event.getMenuAction() == MenuAction.RUNELITE_OVERLAY
        && event.getMenuOption().contains(MARKER)) { … }
}
```

This event fires **before** the action executes; `event.consume()` cancels it
entirely (use sparingly — see gotchas). The case-study matcher for its own
real-action entries: option contains the marker, type is `UNKNOWN` or an
object option, and `(type != CC_OP || param1 != 0)` — the param1 guard
excludes the phantom CC_OP dispatch the client synthesizes for widget
internal ops.

`MenuOptionClicked.getMenuEntry()` gives the full originating entry — prefer
reading identifier/target from it over re-deriving from params.

## 6. `invokeMenuAction` on 1.13.6

The direct dispatch method is `Client.menuAction`:

```java
client.menuAction(int param0, int param1, MenuAction menuAction,
                  int id, int slot, String option, String target);
```

(Site note: older javadocs call this `invokeMenuAction(int,int,int,int,int,String,String)`
with a raw int action id — on 1.13.6 the enum overload above is the public
signature, verified via javap.) Use it only when you must dispatch without a
visible entry (e.g. replicating a widget click the user just confirmed).
Everything else should go through menu entries — Rule 2 of the philosophy doc.

## 7. Widgets

Addressing: packed id = `group << 16 | child`. Access:

```java
Widget w = client.getWidget(group, child);   // or client.getWidget(packed)
// verified Widget methods: getId(), getChild(int), getChildren(),
// getDynamicChildren()/getStaticChildren()/getNestedChildren(),
// getText(), getTextLines(), getActions(), getOnOpListener(),
// getItemId(), isHidden()
```

Widget structure: a group has root children plus three sorted layers
(static/dynamic/nested) — when iterating by index, know which layer your
target lives in (`getChildren()` is the raw storage; the build-interface
options in the case study are dynamic children).

Key events:

- `WidgetLoaded { getGroupId() }` — a group rendered (the case study watches
  for 458 = build interface, plus dialog groups 219/229/231/233/370);
- `WidgetClosed { getGroupId(), getModalMode(), isUnload() }` — modal cleanup
  (the case study clears click states on group 219 closing);
- `MenuEntryAdded` — decorate other entries (append "chips" like the settings
  plugin's id display) — do your mutation inside this event only.

**Widget click pattern**: to "click" a widget the way a user would, create a
menu entry with `WIDGET_FIRST..FIFTH_OPTION`/`CC_OP` semantics
(`param0 = child index`, `param1 = packed id`) or `WIDGET_CONTINUE`
(`param0 = 1`, `param1 = packed id`) for dialogs — optionally with an
`onClick` consumer, or dispatch via `client.menuAction(...)` from the client
thread. Verify the widget is non-null and `!w.isHidden()` first; check again
in the handler — widgets reload between ticks.

**Dialog flow** (NPC/dialog boxes): the standard groups are
219 (player options list), 229 (statement), 231 (NPC dialog with head),
233/370 (variants). Read `client.getWidget(219,1).getTextLines()` /
`(231,6)` style children for text; advance via `WIDGET_CONTINUE` on the
continue child or a menu entry targeting the option row. The case study
verifies the servant's wage text (`String.format(Locale.ENGLISH, "%,d", wage)`)
inside (231,6) before paying — text checks are the only reliable dialog state
read.

## 8. Complete worked example

`templates/skeleton-plugin/` contains all of it in compilable form:
injection with de-dup (§3), PostMenuSort pinning (§4), click capture (§5),
and a config-gated overlay. Build it, load it, and right-click anywhere to see
the pinned entry.
