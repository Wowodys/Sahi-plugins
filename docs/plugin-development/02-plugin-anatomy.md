# 02 — Plugin Anatomy: Lifecycle, Guice, Events, Config

> All signatures below were verified against the pinned jars
> (`sdk/jars/runelite-api-1.13.6-runtime.jar`, `client-1.13.6.jar`, class file
> major 55 / Java 11) with `javap`. Where the web-documented API differs across
> RuneLite versions, this doc reflects **1.13.6** — the version the local
> client runs.

## 1. The minimal file set

```
com.example.myplugin/
├── MyPlugin.java      ← @PluginDescriptor, lifecycle, @Subscribe handlers
├── MyConfig.java      ← @ConfigGroup interface
└── MyOverlay.java     ← OverlayPanel (optional but typical)
src/main/resources/
└── runelite_plugin.json   ← {"plugins": ["com.example.myplugin.MyPlugin"], ...}
```

The `runelite_plugin.json` resource is how RuneLite 1.13.6 discovers plugin
classes in an external jar (the old `RuneLite-Plugin` manifest attribute is
gone in this version — verified by scanning the client jar; the loader
`PluginHubClassLoader` reads the JSON into `Stub{plugins: String[]}`). Optional
fields the JSON accepts (from `PluginHubManifest.DisplayData`): `internalName`,
`displayName`, `version`, `iconHash`, `author`, `description`, `warning`,
`tags[]`, `unavailableReason`. A working skeleton lives in
`templates/skeleton-plugin/` — build it with `gradle build`, drop
`build/libs/template-plugin-1.0.0.jar` into `~/.runelite/plugins`.

## 2. Lifecycle

```java
@PluginDescriptor(
    name = "My Plugin",
    description = "…",
    tags = {"tag1"},
    enabledByDefault = false
)
public class MyPlugin extends Plugin
{
    @Override
    protected void startUp() throws Exception { /* add overlays, register bus */ }

    @Override
    protected void shutDown() throws Exception { /* unregister, remove, reset state */ }
}
```

Verified on 1.13.6: `Plugin` declares `protected void startUp() throws Exception`
and `protected void shutDown() throws Exception` as **concrete empty methods**
(not abstract — overrides must keep `protected`). Also available:
`getName()`, `getInjector()`, `configure(Binder)` (it implements Guice
`Module`), `resetConfiguration()`.

`@PluginDescriptor` fields (verified): `name`, `configName`, `description`,
`tags[]`, `conflicts[]`, `enabledByDefault`, `hidden`, `internalName`,
`legacyDataDirectory`, `developerPlugin`, `loadInSafeMode`.

The golden pairing rule: **everything `startUp` registers, `shutDown`
unregisters** — overlays (`OverlayManager.add/remove`), the event bus
(`eventBus.register/unregister(this)`), key listeners
(`KeyManager.register/unregisterKeyListener`), executors (`shutdown()`).
Miss one and toggling the plugin leaks duplicates.

## 3. Injection (Guice)

RuneLite injects into plugin fields annotated `@Inject` (javax.inject) after
instantiation. What you can inject (all verified present in the client jar):

| Service | Use |
|---|---|
| `net.runelite.api.Client` | all game state reads + menu (§ below) |
| `net.runelite.client.callback.ClientThread` | hop onto the client thread: `invoke(Runnable)`, `invoke(BooleanSupplier)`, `invokeLater(Runnable)`, `invokeLater(BooleanSupplier)`, `invokeAtTickEnd(Runnable)` |
| `net.runelite.client.eventbus.EventBus` | `register(Object)`, `unregister(Object)`, `post(Object)` |
| `net.runelite.client.ui.overlay.OverlayManager` | `add(Overlay)`, `remove(Overlay)` |
| `net.runelite.client.input.KeyManager` | `registerKeyListener(KeyListener)` |
| `net.runelite.client.config.ConfigManager` | raw config access, defaults registration |
| `net.runelite.client.game.ItemManager` | `getImage(int)`, `getImage(int,int,boolean)`, `getItemComposition(int)` |
| `net.runelite.client.chat.ChatMessageManager`, `Notifier`, `PluginManager` | chat queues, notifications, etc. |

Your own plugin classes (overlay, state objects) are injectable too —
constructor injection for the overlay is the standard shape (see
`TemplateOverlay`).

**Client-thread rule**: event handlers already run on the client thread
(§4), but anything scheduled from elsewhere (key listeners, `onClick`
consumers, executor threads) must wrap in `clientThread.invokeLater(...)`.
Never call `Client` getters from other threads.

## 4. The event bus

```java
@Subscribe
private void onGameTick(GameTick event) { … }
```

- `@Subscribe` lives in `net.runelite.client.eventbus.Subscribe`; optional
  `float priority()` for ordering multiple subscribers of the same event.
- Register/unregister in startUp/shutDown (§2).
- **Delivery thread**: RuneLite posts game events on the client thread
  (they are pumped from the client's game loop, one batch per tick in
  deterministic order); UI-adjacent events (config changes, plugin
  notifications) post from wherever they happen. Write handlers that assume
  nothing else and you are safe; if you need to hop threads, use
  `clientThread.invokeLater`.
- Handler privacy: `private` works; the bus reads the annotation. Return
  values are ignored; methods take exactly one event parameter.
- Ordering per tick (client thread, approx.): ClientTick → game packet
  processing → entity spawns/despawns → events pumped (MenuOptionClicked,
  ItemContainerChanged, …) → GameTick → PostClientTick. Do not build logic
  that depends on finer ordering than that.

## 5. Config

```java
@ConfigGroup("myplugin")
public interface MyConfig extends Config
{
    @ConfigItem(keyName = "enableOverlay", name = "Show overlay",
                description = "Toggle the status panel", position = 0)
    default boolean enableOverlay() { return true; }
}
```

Verified annotations (exact element lists from the 1.13.6 jar):
`@ConfigGroup(value)`,
`@ConfigItem(keyName, name, description, position, hidden, warning, secret,
section)`, `@ConfigSection(name, description, position, closedByDefault)`,
plus `@Range`, `@Alpha`, `@Units`. The interface gets implemented by Guice against
`ConfigManager` storage; default methods provide defaults. React to changes:

```java
@Subscribe
private void onConfigChanged(ConfigChanged event)
{
    if (!event.getGroup().equals("myplugin")) return;
    // event.getKey(), event.getNewValue()
}
```

The config panel is generated automatically — no UI code. Kuri plugins did
exactly this (their configs were plain vanilla `@Config` interfaces).

## 6. Key listeners

```java
// via KeyManager (raw access to pressed/released/typed)
keyManager.registerKeyListener(new KeyListener() { … });
// or the convenience wrapper for single hotkeys (net.runelite.client.util):
HotkeyListener l = new HotkeyListener(() -> new Keybind(KeyEvent.VK_1, 0)) {
    @Override public void hotkeyPressed() { clientThread.invokeLater(...); }
};
keyManager.registerKeyListener(l);
```

Verified: `net.runelite.client.util.HotkeyListener` (extends
`net.runelite.client.input.KeyListener`, constructor takes
`Supplier<Keybind>`); the input `KeyListener` interface adds
`isEnabledOnLoginScreen()` / `focusLost()` defaults over the AWT listener.
The case-study plugin used a raw `KeyListener` to mirror digits 1–4 while the
build interface is open.

## 7. Packaging and loading

- Build: Gradle `java` plugin, `options.release = 11`, compile-only deps on
  `net.runelite:runelite-api` + `net.runelite:client` (+ guice/slf4j if not
  provided by the local fileTree) — exact working setup in
  `templates/skeleton-plugin/build.gradle` and
  [../runelite/01-environment-setup.md](../runelite/01-environment-setup.md).
- Install: drop the jar into `~/.runelite/plugins/` (must contain
  `runelite_plugin.json`), restart the client, enable in the config panel.
- Class-loading: external plugins load in a child-first `PluginHubClassLoader`
  that delegates `net.runelite.**` to the client — your jar must NOT bundle
  RuneLite classes.
- Dependencies between your own plugins: `@PluginDependency(OtherPlugin.class)`
  on the descriptor; the manager topologically sorts and shares child injectors
  (same mechanism Kuri's fork uses).
