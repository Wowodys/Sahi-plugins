# 05 — Inside a Kuri Plugin: Architecture, API Surface, Obfuscation

> Distilled from the decompiled sources of the three decrypted plugins
> (`plugin-src/{construction,vorkath,settings}/` — 66 classes) and the framework
> jar. Read this before porting any Kuri plugin to vanilla RuneLite.

## 1. The platform contract (what "being a Kuri plugin" means)

A Kuri plugin is compiled against a **patched RuneLite**: `Client` grows a
`getMenu()` returning the Kuri facade `com.kuri.api.menu.Menu`, and the plugin
base class is `com.kuri.client.plugin.Plugin` — a drop-in replacement for
`net.runelite.client.plugins.Plugin` with the same `startUp()`/`shutDown()`,
the same `configure(Binder)`/`Module` mechanics, plus `getInjector()` and a
`version` field stamped from the load plan. `@PluginDescriptor` stays vanilla;
Kuri adds `@KuriPluginMetadata(category = KuriPluginCategory.SKILLING|PVM_PVP|UTILITY…)`
alongside it.

At runtime the Kuri PluginManager (a fork of vanilla `PluginManager`) does the
usual dance: decrypt plan items → load classes from the decrypted archive →
keep classes with `@PluginDescriptor` extending `Plugin` → topologically sort
`@PluginDependency` edges → create **Guice child injectors** per plugin (chain
them for dependency groups) → `startUp()` gated on the heartbeat authorization
(see [04](04-transport-and-capture.md) §5) → register on EventBus + scheduler.

What plugins can `@Inject`:

- everything vanilla RuneLite injects (`Client`, `ClientThread`, `EventBus`,
  `OverlayManager`, `ConfigManager`, `KeyManager`, `ItemManager`, `Notifier`, …),
- the Kuri extras: `com.kuri.api.menu.Menu` (via the patched `Client.getMenu()`),
  `com.kuri.api.Inventory`, plus `KuriServices` (combat services: target
  tracker, effect timers, PID detector, spec tracker, prayer tracker).

No plugin in the analyzed set calls any licensing/HTTP code — the platform
boundary is exactly: base class + `Menu` facade + query/Inventory facades.

## 2. Package layout of a plugin jar

All three plugins follow one scheme:

```
com.kuri.plugin.<product>.           ← product segment, e.g. kuriclickconstruction
    KuriClickConstructionPlugin.java ← entry (@PluginDescriptor + @KuriPluginMetadata)
    KuriClickConstructionConfig.java ← plain vanilla @Config/@ConfigGroup
    a/  … MenuConfig impl, enums (Servant), string-pool decryptors
    b/  … the engine: click/state tracker, requirement parser, servant manager
    c/  … overlay (c.a) + session stats (c.b)
    d/  … state classes (d.a dispatcher, d.b…d.g states)
    e/  … constants class (e.a) + font/format utils (e.b)
<16 random letters>.                 ← junk package, one 16-letter junk class inside
    qqrnfthdchipcafl                 ← opaque-predicate helper (see §5)
```

Single-letter class and member names (`a`, `b`, `c` — fields overloaded by
type only, CFR warns "Duplicate member names"). The junk class exposes methods
like `vkgridvuyyuvyqbi(n)` = rotate-left-3 and `tjeghifwejddwsgu(n)` —
constant-foldable math used purely to baffle decompilers. Every jar also
carries a braille-art `nothing_to_see_here` string array in its constants
class — a Kuri logo watermark, not data.

`mainClass` in the load plan (validated fully-qualified name) is what the
framework loads first; entry points are filtered for `@PluginDescriptor`.

## 3. The Menu facade and MenuConfig — how the "one click" is built

The facade wraps RuneLite's menu system and adds three conveniences:

- `menu.newEntry()` → builds and returns a vanilla `net.runelite.api.MenuEntry`
  (chained setters are the standard API);
- `menu.waitEntry()` → a no-op entry that swallows one click harmlessly (used
  as the default "waiting" step in state machines);
- `Menu.setTemp(entry)` → marks a framework-injected entry for cleanup.

Sub-facades (`widget()`, `object()`, `npc()`, `inventory()`, `walk()`,
`dialog()`, `spellbook()`, `prayer()`) wrap common invocation idioms, e.g.
`widget().interact(10485780)` = click the quick-prayer orb;
`dialog().continueWithOption("Exchange All")`; `spellbook().castOn(14286881, actor)`.

`com.kuri.api.menu.MenuConfig` is a one-method interface —
`getMenuOptionText()` — whose implementation supplies the injected menu label
(`<col=8532cb>Kuri Click Construction`). It exists so the label is decrypted
once by the string-decrypt machinery and shared (see §5). Porting: replace it
with a `public static final String`.

Typical emission from the construction plugin (state `d.a`):

```java
menu.newEntry().setIdentifier(spot.getId())
    .setParam0(spot.getX()).setParam1(spot.getY())
    .setType(MenuAction.GAME_OBJECT_FIFTH_OPTION);   // the real in-game "Build op5"
```

The critical observation: **the injected entries carry real game action types
and real targets**. Clicking them makes the client itself emit the ordinary
outbound action — the plugin never sends a packet, it only curates the menu.
For purely client-local entries they use `MenuAction.UNKNOWN` /
`WIDGET_CONTINUE` / `CC_OP` shaped entries instead. Either way the observable
server behavior is identical to a normal player click.

## 4. The query API and Inventory facade

Fluent queries mirror RuneLite's retired `QueryRunner` API:

```java
GameObject g = new GameObjectQuery().idEquals(new int[]{chosenId})
        .result(client).nearestTo(client.getLocalPlayer());
NPC n = new NPCQuery().idEquals((Collection) servantIds).result(client).first();
WallObject w = new WallObjectQuery().idEquals(new int[]{1540}).result(client)
        .stream().filter(x -> x.getWorldLocation().equals(new WorldPoint(2962,3213,0)))
        .findFirst().orElse(null);
QueryResults<Item> r = inventory.search().withName("Oak plank").isUnnoted().result(client);
IdentifiedItem coins = inventory.findItem(995);
IdentifiedItem food = inventory.findItemWithAction("Eat");
```

Predicates seen: `withName/withId/withIds/withAction/filter/isNoted/isUnnoted`;
results offer `first()/nearestTo(actor)/stream()/size()/get(i)/isEmpty()`.
`IdentifiedItem` = `getId()/getQuantity()/getItemComposition()`.

Porting notes:

- queries → walk `client.getTopLevelWorldView().getScene().getTiles()[plane][x][y]`
  (`.getGameObjects()`, `.getWallObject()`, `.getDecorativeObject()`, `.getGroundObject()`)
  and `worldView.npcs()` (an `IndexedObjectSet`) with the same filters;
- `Inventory.search()` → read `client.getItemContainer(InventoryID.INVENTORY)`
  and match with `client.getItemDefinition(id).getName()` / note ids;
- the noted↔unnoted resolution the plugins use is exactly vanilla:
  ```java
  static int unnotedId(IdentifiedItem it) {
      ItemComposition c = it.getItemComposition();
      return c.getNote() == -1 ? it.getId() : c.getLinkedNoteId();
  }
  ```

## 5. Obfuscation layers, exhaustively catalogued

1. **Identifier scrambling** — single letters for classes/fields/methods; one
   16-random-letter package with one junk class per jar.
2. **String encryption layer 1 (LCG-XOR via condy)** — JVM 11 dynamic
   constants bootstrap calling a per-class `$k…(String, int)` that does
   `n = n*1664525+1013904223; c[i] ^= n>>>16`. CFR inlines the decoded value
   as a `/* => "…" */` comment; `deob_strings.py` reproduces the same decoding
   standalone (see [03](03-string-deobfuscation.md)).
3. **String encryption layer 2 (byte-array XOR / string pool)** — long strings
   decrypt from `byte[]` providers: `data[i] ^= Integer.toString(seed).getBytes()[i%len] ^ GLOBAL_KEY[i%len]`
   decoded UTF-16; a pool variant builds one big UTF-16BE string at `<clinit>`
   and substrings by (offset, length) pairs. Same doc, §3.
4. **Constants-table obfuscation** — fields named `a..h` disambiguated only by
   type, each element computed in `<clinit>` through XOR/short-cast chains and
   opaque predicates. **Running the classes** (`ConstDump`/`EnumDump`) recovers
   the exact values, which is how the constant tables in the case study were
   produced.
5. **Runtime key mutation** — seeded `Random` (fixed seed) producing a static
   int key that gates decoy branches: `key = 0x70BC8A1F ^ new Random(-4664956034449471628L).nextInt()`,
   re-XORed in constructors. Deterministic per build; defeats static analysis,
   defeated by just running the class.
6. **Control-flow flattening** — `switch` on opaque-predicate results,
   `while(true)` spin blocks with impossible double-conditions, dead XOR
   statements, unreachable `throw` arms; CFR emits "Unable to fully structure
   code". The real work hides in the reachable `case`.
7. **Condy string-concat recipes** — `StringConcatFactory` bootstraps with
   encrypted recipe strings (vorkath's `Step.toString`).

None of these layers protect anything *behavioral* — they are all reversible
by execution (`ConstDump`/`EnumDump`/CFR-comments). The only real protection
was the KENC key distribution ([01](01-kenc-container-format.md)).

## 6. Port map — Kuri API → vanilla RuneLite (verified signatures, 1.13.6)

| Kuri | Vanilla replacement |
|---|---|
| `com.kuri.client.plugin.Plugin` + `@KuriPluginMetadata` | `net.runelite.client.plugins.Plugin` + `@PluginDescriptor` (drop `getVersion()`, category) |
| Kuri injector (`plugin.injector.getInstance(State.class)`) | Guice field/constructor `@Inject`s provided by RuneLite; for per-state DI use your own plain `new` or a small factory |
| `Client.getMenu()` → `com.kuri.api.menu.Menu` | `client.getMenu()` (vanilla 1.13.6 returns `net.runelite.api.Menu`): `createMenuEntry(-1)`, `getMenuEntries()`, `setMenuEntries(...)` |
| `menu.newEntry()` chain | `client.getMenu().createMenuEntry(-1).setOption(...).setTarget(...).setType(...).setIdentifier(...).setParam0(...).setParam1(...)` (+ optional `.onClick(consumer)`) |
| `menu.waitEntry()` | an entry with `.setOption("").setType(MenuAction.UNKNOWN).onClick(e -> {})` or simply no injected entry while idle |
| `Menu.setTemp(entry)` | track your own injected entries (match on your marker constant) and remove them on shutDown |
| `com.kuri.api.menu.MenuConfig` | a `public static final String` constant |
| `com.kuri.api.Inventory` / `IdentifiedItem` | `client.getItemContainer(InventoryID.INVENTORY)` (id 93), `net.runelite.api.Item` + `client.getItemDefinition(id)`; note ids via `ItemComposition.getNote()/getLinkedNoteId()` |
| `com.kuri.api.query.*Query` | scene-tile walks + `worldView.npcs()` filters (see §4) |
| `QueryResults.nearestTo/first/stream` | trivial hand-rolled list utils (~30 lines) |
| `com.kuri.api.invoke.*Invoke` (`widget().interact(id)` etc.) | construct the equivalent `MenuEntry` (e.g. `WIDGET_CONTINUE` with param0=1/param1=widgetId) or `client.getMenu().createMenuEntry(...)` + onClick — never raw packets |
| `com.kuri.api.PrayerUtil` / `KuriServices` combat trackers | implement yourself from `VarbitChanged`/`AnimationChanged`/`HitsplatApplied` — the data is all in public events |
| Kuri hooks catalog (obfuscated client offsets) | **not needed** for the analyzed plugins — public API suffices; treat any behaviour that seems to need it as a design smell |
| Kuri licensing/KENC/heartbeat/PluginManager | **not needed** — a vanilla external plugin is just a jar in `~/.runelite/plugins` with `runelite_plugin.json` |

## 7. Two state-machine patterns to steal (and their vanilla cost)

**Construction — states as injectable classes.** Each state is a class with
`a()` = "emit the menu entry for this state" and `a(Injector)` = "return the
next state (or this)". One root dispatcher (`d.a`) decides purely from
**event-fed data** (queues, inventory counts, varbits). No wall-clock anywhere.
Vanilla port: keep the classes, replace `injector.getInstance(X.class)` with a
switch instantiating states via constructor injection of the plugin.

**Vorkath — per-tick Step queues.** Each state builds a `Queue<Step>` where a
`Step` = `(condition, entrySupplier, onClickConsumer)`; every GameTick
re-validates conditions, rebuilds if the tick changed, and the *next real
click* executes the next step via composed `MenuEntry.onClick` consumers.
Vanilla port: direct — the pattern needs only `MenuEntry.onClick`.

Both patterns obey the same invariants documented in
[../plugin-development/01-design-philosophy.md](../plugin-development/01-design-philosophy.md):
one click = one bounded sequence, progress only on game-confirmed events, and
every flag has a natural reset (the construction plugin has **eleven** distinct
reset triggers — object spawn confirm, NPC spawn, dialog widget load/close,
two varbit guards, inventory drain, chat message, …).
