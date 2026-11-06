# 05 — Overlays and Session Stats

> Verified against the pinned 1.13.6 jars (`javap` on `client-1.13.6.jar`).
> Note: `OverlayPriority` is **deprecated** in 1.13.x — use the float
> `setPriority(float)` (0–1) or skip priorities entirely.

## 1. Overlay basics

```java
public class StatsOverlay extends OverlayPanel
{
    private final MyPlugin plugin;
    private final MyConfig config;

    @Inject
    private StatsOverlay(MyPlugin plugin, MyConfig config)
    {
        super(plugin);                       // wires the "Configure" header option
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.BOTTOM_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay()) return null;      // return null = render nothing
        panelComponent.getChildren().add(LineComponent.builder()
            .left("State:").right(plugin.stateText()).build());
        return super.render(graphics);               // sizes + draws the panel
    }
}
```

Verified API shapes:

- `OverlayPanel` constructors: `()` and `(Plugin)`; `render(Graphics2D)` returns
  `Dimension`; children live in the protected `panelComponent`
  (`net.runelite.client.ui.overlay.components.PanelComponent`,
  `getChildren()` add/remove, `setPreferredSize(Dimension)`, `setGap(Point)`).
- `LineComponent.builder()` → `.left(String)`, `.right(String)`,
  `.leftColor/.rightColor(Color)`, `.leftFont/.rightFont(Font)`, `.build()`.
  (There is no `setTextLines`; lines are `LineComponent` children.)
- `Overlay` positioning: `setPosition(OverlayPosition)` —
  `BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT/CENTER/RIGHT, ABOVE_CHATBOX_RIGHT,
  CANVAS_TOP_RIGHT, DETACHED, DYNAMIC, TOOLTIP`; layers
  `OverlayLayer.MANUAL/ABOVE_SCENE/UNDER_WIDGETS/ABOVE_WIDGETS/ALWAYS_ON_TOP`
  via `setLayer(...)` (default `UNDER_WIDGETS`).
- Register/unregister with `overlayManager.add/remove` in startUp/shutDown.
- World-space overlays (tile highlights etc.): extend `Overlay` directly and
  render polygons from
  `Perspective.getCanvasTilePoly(graphics, client.getLocalDestinationLocation()…)`-style
  helpers + `getCanvasTextLocation` — that is what the vorkath plugin's
  `DYNAMIC`/`ABOVE_SCENE` overlay does ("dodge"/"attack"/"safe" tile labels).

Config-gate everything (`if (!config.showX()) return null;`) and keep the
overlay informative-but-small: state line, one or two counters. Rule 6 of the
philosophy — the overlay is also your abort surface.

## 2. XP/hr and actions/hr — the math from the case study

Subscribe to `StatChanged` (fields: `getSkill()`, `getXp()`, `getLevel()`,
`getBoostedLevel()`):

```java
@Subscribe
private void onStatChanged(StatChanged event)
{
    if (event.getSkill() != Skill.CONSTRUCTION) return;
    int delta = event.getXp() - lastXp;
    lastXp = event.getXp();
    if (delta <= 0) return;

    Instant now = Instant.now();
    if (lastXpTime != null)
    {
        double seconds = (now.toEpochMilli() - lastXpTime.toEpochMilli()) / 1000.0;
        actionsPerHour = seconds > 0 ? 3600.0 / seconds : 0;   // one action per xp drop
    }
    lastXpTime = now;
    xpGained += delta;
}
```

Key property: **xp/hr and actions/hr are measured between real xp drops** —
there is no plugin-side clock driving anything. The inter-drop interval is
the server-paced activity rate; the plugin only observes it. XP to next
level: `Experience.getXpForLevel(level + 1) - xp` (`net.runelite.api.Experience`).

## 3. Materials-consumed counter (high-water mark)

```java
@Subscribe
private void onItemContainerChanged(ItemContainerChanged event)
{
    if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;
    int n = event.getItemContainer().count(materialId);
    if (n < highWaterMark) { materialsUsed += highWaterMark - n; }
    highWaterMark = n;   // restock raises the mark; each drop below it = consumption
}
```

(The case-study implementation is exactly this running-max/rollback scheme on
container 93.)

## 4. Session timer

One low-frequency scheduled task only formats and stores the string — it does
not touch the client:

```java
scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() ->
    sessionText = String.format("%02d:%02d:%02d",
        dur.toHours(), dur.toMinutesPart(), dur.toSecondsPart()),
    1, 1, TimeUnit.SECONDS);
// shutdown() in shutDown(); reset on GameStateChanged → LOGIN_SCREEN
```

## 5. Reset and lifecycle rules for stats

- Reset on `GameStateChanged` → `LOGIN_SCREEN` (and re-snapshot xp after
  login — the case study sleeps ~1.2 s after `LOGGED_IN` before snapshotting,
  because xp is not reliably readable before the first stat packet; a
  `clientThread.invokeLater` check is the vanilla-safe variant).
- Every counter zeroes in `shutDown()` too (plugin toggle = fresh session).
- Never let the overlay read stale plugin state: getters should be plain field
  reads; the overlay renders on the client thread, your writers are event
  handlers on the client thread — no locking needed as long as you don't
  write from executor threads (the timer above only writes a string field —
  fine).

## 6. Images and fonts

- Item icons: `itemManager.getImage(itemId)` → `AsyncBufferedImage`
  (or `getImage(itemId, quantity, boolean)`); safe to draw once loaded — the
  case study pastes the furniture image into its skinned panel at a fixed
  pixel offset.
- Custom fonts: load from the jar resource
  (`Font.createFont(TRUETYPE_FONT, getClass().getResourceAsStream("/Font.ttf"))`),
  fall back to a standard font on failure. Remember: resources load via
  `getResourceAsStream` only — plugin jars stay packed on the classpath.
- For panels with custom art, draw your own background in `render` before
  calling `super.render` (the case study draws a bundled PNG), or skip
  `OverlayPanel` and extend `Overlay` with a custom `Dimension`.
