/*
 * Copyright (c) 2026, sahij
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.kuri.backgroundclicker;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.kuri.backgroundclicker.clicker.CanvasClickInjector;
import net.runelite.client.plugins.kuri.backgroundclicker.clicker.SafeZoneMapper;
import net.runelite.client.plugins.kuri.backgroundclicker.hook.WinMouseHook;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background Clicker - a standalone OS-to-canvas input forwarder.
 *
 * Scope guard (read before extending): this plugin OWNS NO GAME STATE. It
 * has no engine, no armed cycles, no menu work, no world knowledge. Its
 * entire contract is:
 *
 *   OS hardware click (WH_MOUSE_LL, hook/WinMouseHook)
 *     -&gt; relative safe-zone mapping (clicker/SafeZoneMapper)
 *       -&gt; synthetic MouseEvent pair on the canvas (clicker/CanvasClickInjector)
 *         -&gt; the client's OWN input pipeline (Callbacks -&gt; MouseManager
 *            -&gt; recorded mouse state -&gt; client-thread menu processing)
 *           -&gt; MenuOptionClicked on the event bus
 *
 * It is fully decoupled from the 1:1 location plugin (and every other
 * plugin): they meet only at the vanilla input pipeline. The location
 * plugin intercepts the resulting MenuOptionClicked and routes the walk to
 * its configured tile - no imports, no shared state, no configuration
 * coupling in either direction, and nothing to change in it.
 *
 * Threading model (three lanes, never mixed):
 *
 *   1. OS hook pump thread ("kuri-bgclicker-os-hook") - installs the
 *      WH_MOUSE_LL, receives every hardware click. Does ONE thing: offer
 *      the click into {@link #handoff}. Non-blocking offer or the whole
 *      desktop's cursor latency suffers.
 *   2. Dispatcher thread ("kuri-bgclicker-dispatch") - single consumer.
 *      Pairs press with release, folds the physical point through the safe
 *      zone, and hops to the EDT with the final coordinates.
 *   3. EDT - the only place the canvas is touched: liveness + client-window
 *      bounds checks, then {@link CanvasClickInjector#dispatchClick}.
 *
 * The single-slot handoff is deliberate back-pressure, not an optimization:
 * while one click is still being dispatched, new physical clicks are PASSED
 * THROUGH to the focused application and counted, never queued. A forwarded
 * click is always the user's live click - stale replay would turn 1:1 into
 * an accordion, which is the thing this plugin must never be.
 */
@PluginDescriptor(
	name = "Kuri Background Clicker",
	description = "Standalone OS-level click forwarder: maps global hardware "
		+ "clicks into the canvas safe zone and dispatches them through the "
		+ "client's own input pipeline. Decoupled from every other plugin.",
	tags = {"background", "clicker", "input", "os"},
	enabledByDefault = false
)
public class BackgroundClickerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(BackgroundClickerPlugin.class);

	static final String CONFIG_GROUP = "kuribackgroundclicker";

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BackgroundClickerConfig config;

	@Inject
	private SafeZoneOverlay zoneOverlay;

	@Inject
	private StatusOverlay statusOverlay;

	/**
	 * The toggle guard ({@code isForwarderActive}): effective master switch.
	 * Seeded from config on startUp; kept in sync with the panel checkbox by
	 * the hotkey (persist=true) and by {@link #onConfigChanged}
	 * (persist=false). When false, the hook callback returns immediately and
	 * every OS click propagates untouched. volatile: read from the OS hook
	 * thread, written from the EDT.
	 */
	private volatile boolean forwarderActive;

	private volatile boolean running;

	private final WinMouseHook hook = new WinMouseHook(this::onOsMouseButton);

	/**
	 * Single-slot OS-to-dispatcher handoff. offer() fails when the
	 * dispatcher is still busy with the previous click - that is the
	 * back-pressure contract, see class javadoc.
	 */
	private final SynchronousQueue<PhysicalClick> handoff = new SynchronousQueue<>();

	private ExecutorService dispatcher;

	// Dispatcher-thread-only state: press halves awaiting their release.
	private PhysicalClick pendingLeftPress;
	private PhysicalClick pendingRightPress;

	// Counters + last positions: written by hook/dispatcher/EDT lanes, read
	// by the overlay (EDT) - atomics and volatiles, nothing fancier.
	private final AtomicInteger forwardedCount = new AtomicInteger();
	private final AtomicInteger droppedCount = new AtomicInteger();
	private final AtomicInteger skippedCount = new AtomicInteger();
	private volatile Point lastPhysical;
	private volatile Point lastMapped;

	/**
	 * Snapshot of the client window's screen bounds, refreshed on the EDT
	 * on every dispatch attempt. The hook-thread pre-guard reads this so it
	 * never has to touch Swing; null until the first EDT pass (the EDT-side
	 * guard remains authoritative).
	 */
	private volatile Rectangle cachedClientWindowBounds;

	/**
	 * The hotkey only fires while the client window has keyboard focus
	 * (RuneLite dispatches window-level keys through a focus gate) - that is
	 * the correct shape for a toggle you press once before going back to
	 * work, not a runtime control.
	 *
	 * Created in startUp(), NOT as a field initializer: field initializers
	 * run during construction, before Guice injects {@link #config}, and
	 * evaluating {@code config::hotkeyToggle} with a null receiver throws
	 * NPE inside the constructor - PluginManager logs
	 * "Error instantiating plugin class" and silently drops the plugin from
	 * the config panel. (The lesson this bug teaches: injected fields may be
	 * READ in lifecycle methods, never captured eagerly at construction.)
	 */
	private HotkeyListener toggleListener;

	@Override
	protected void startUp()
	{
		forwarderActive = config.active();
		running = true;

		toggleListener = new HotkeyListener(config::hotkeyToggle)
		{
			@Override
			public void hotkeyPressed()
			{
				setForwarderActive(!isForwarderActive(), true);
			}
		};

		dispatcher = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "kuri-bgclicker-dispatch");
			thread.setDaemon(true);
			return thread;
		});
		dispatcher.execute(this::dispatchLoop);

		overlayManager.add(zoneOverlay);
		overlayManager.add(statusOverlay);
		keyManager.registerKeyListener(toggleListener);

		hook.start();
		log.info("Background clicker startUp: forwarderActive={}, zone={}x{}+{},+{}",
			forwarderActive, config.zoneWidth(), config.zoneHeight(), config.zoneOffsetX(), config.zoneOffsetY());
	}

	@Override
	protected void shutDown() throws InterruptedException
	{
		forwarderActive = false;
		running = false;

		if (toggleListener != null)
		{
			keyManager.unregisterKeyListener(toggleListener);
			toggleListener = null;
		}
		overlayManager.remove(zoneOverlay);
		overlayManager.remove(statusOverlay);

		hook.stop();

		ExecutorService service = dispatcher;
		dispatcher = null;
		if (service != null)
		{
			// interrupt() breaks the dispatcher's handoff poll - no click is
			// mid-flight after hook.stop(), so nothing is left half-forwarded.
			service.shutdownNow();
		}
		pendingLeftPress = null;
		pendingRightPress = null;
		log.info("Background clicker shutDown");
	}

	/**
	 * The plugin is installed as a Guice module (PluginModuleFactory), so
	 * its @Provides methods supply the child injector - this is how modern
	 * RuneLite binds a plugin's config interface.
	 */
	@Provides
	BackgroundClickerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BackgroundClickerConfig.class);
	}

	/**
	 * Panel checkbox -&gt; runtime flag. The hotkey writes the config item
	 * through {@link ConfigManager#setConfiguration}, which fires this
	 * event; the flag converges without a persist loop.
	 */
	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"active".equals(event.getKey()))
		{
			return;
		}
		setForwarderActive(Boolean.parseBoolean(event.getNewValue()), false);
	}

	// ----- OS hook lane (hook pump thread) -----

	/**
	 * Forwarder callback for {@link WinMouseHook}: the cheap filter chain
	 * and the handoff. Runs on the OS hook pump thread - MUST NOT block.
	 * Returns the swallow decision (redirect mode). OS-injected events never
	 * reach this method: the hook drops them before the forwarder (see
	 * {@link WinMouseHook} - unconditional infinite-loop guard).
	 */
	private boolean onOsMouseButton(boolean pressed, boolean leftButton, int screenX, int screenY)
	{
		if (!forwarderActive)
		{
			return false;
		}

		// Left is the spec'd core; right is the opt-in that lets the
		// unfocused flow open the context menu (see config item javadoc).
		if (!leftButton && !config.forwardRightClicks())
		{
			return false;
		}

		// Cheap pre-guard, not the authoritative one: clicks over the
		// client's own window belong to the real window. The EDT-side check
		// below is the definitive filter; this one keeps the decision cheap
		// and keeps redirect mode from eating config-panel clicks.
		if (config.ignoreClicksInClientWindow() && inClientWindow(screenX, screenY))
		{
			return false;
		}

		PhysicalClick click = new PhysicalClick(pressed, leftButton, screenX, screenY);
		if (!handoff.offer(click))
		{
			// Dispatcher busy: pass the physical click through untouched -
			// a forwarded click that was dropped must never also disappear
			// from the application the user is actually in.
			droppedCount.incrementAndGet();
			return false;
		}

		return config.swallowForwardedClicks();
	}

	/**
	 * Cheap hook-thread containment check against the cached window bounds
	 * (volatile read; the authoritative EDT-side check below re-derives the
	 * window every time). Unknown bounds = no pre-guard, EDT decides.
	 */
	private boolean inClientWindow(int screenX, int screenY)
	{
		Rectangle bounds = cachedClientWindowBounds;
		return bounds != null && bounds.contains(screenX, screenY);
	}

	// ----- Dispatcher lane ("kuri-bgclicker-dispatch" thread) -----

	private void dispatchLoop()
	{
		while (running)
		{
			PhysicalClick click;
			try
			{
				click = handoff.poll(400, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}

			if (click == null || !forwarderActive)
			{
				continue;
			}

			try
			{
				forward(click);
			}
			catch (Exception ex)
			{
				log.warn("Background click forward failed", ex);
				skippedCount.incrementAndGet();
			}
		}
	}

	/**
	 * Pairs the click halves, normalizes the physical point across the
	 * primary monitor's extent into the safe zone, and hops to the EDT for
	 * the dispatch. A physical click forwards once, on release, at the
	 * press's position (the release's position if the press half was never
	 * seen - forwarding was toggled mid-click).
	 */
	private void forward(PhysicalClick click) throws InvocationTargetException, InterruptedException
	{
		if (click.pressed)
		{
			if (click.leftButton)
			{
				pendingLeftPress = click;
			}
			else
			{
				pendingRightPress = click;
			}
			return;
		}

		PhysicalClick press = click.leftButton ? pendingLeftPress : pendingRightPress;
		if (click.leftButton)
		{
			pendingLeftPress = null;
		}
		else
		{
			pendingRightPress = null;
		}
		if (press != null && click.atMs - press.atMs > SafeZoneMapper.PAIR_STALE_MS)
		{
			press = null; // stale pair: the release position is the truth
		}

		int screenX = press != null ? press.screenX : click.screenX;
		int screenY = press != null ? press.screenY : click.screenY;

		// Read-only client state, safe off-thread; the EDT block re-checks
		// everything that can change under us.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			skippedCount.incrementAndGet();
			return;
		}
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			skippedCount.incrementAndGet();
			return;
		}

		// Linear normalization across the primary monitor's physical extent,
		// scaled into the safe zone - physical left/right edges land on the
		// zone's left/right edges (SafeZoneMapper javadoc: why modulo was
		// replaced).
		Point mapped = SafeZoneMapper.map(screenX, screenY,
			config.monitorWidth(), config.monitorHeight(),
			config.zoneOffsetX(), config.zoneOffsetY(),
			config.zoneWidth(), config.zoneHeight(),
			canvas.getWidth(), canvas.getHeight());

		lastPhysical = new Point(screenX, screenY);
		lastMapped = mapped;

		int button = click.leftButton ? MouseEvent.BUTTON1 : MouseEvent.BUTTON3;
		int pressModifiers = BackgroundClickerConfig.pressModifiersFor(click.leftButton);
		int mappedX = mapped.x;
		int mappedY = mapped.y;

		EventQueue.invokeAndWait(() ->
		{
			Canvas liveCanvas = client.getCanvas();
			if (liveCanvas == null || !liveCanvas.isShowing())
			{
				skippedCount.incrementAndGet();
				return;
			}

			// Refresh the hook-thread pre-guard's snapshot while we are on
			// the EDT (the only thread allowed to walk the AWT hierarchy).
			Window window = SwingUtilities.getWindowAncestor(liveCanvas);
			cachedClientWindowBounds = window != null ? window.getBounds() : null;

			// The definitive client-window guard (EDT-side). Clicks over
			// the game's own window are the real window's business - the
			// user configuring THIS plugin's panel must not also walk.
			if (config.ignoreClicksInClientWindow()
				&& window != null
				&& window.getBounds().contains(screenX, screenY))
			{
				skippedCount.incrementAndGet();
				return;
			}

			CanvasClickInjector.dispatchClick(liveCanvas, button, pressModifiers, mappedX, mappedY);

			// Counted only when a dispatch actually ran - skipped clicks are
			// counted as skipped above, never as forwarded AND skipped.
			forwardedCount.incrementAndGet();
		});
	}

	// ----- toggle plumbing -----

	private void setForwarderActive(boolean value, boolean persist)
	{
		forwarderActive = value;
		if (persist)
		{
			// Fires onConfigChanged, which re-applies the same value with
			// persist=false - converges, no loop. The config key stays
			// "active" so previously stored toggles survive the rename.
			configManager.setConfiguration(CONFIG_GROUP, "active", String.valueOf(value));
		}
	}

	// ----- overlay accessors -----

	public boolean isForwarderActive()
	{
		return forwarderActive;
	}

	/**
	 * The OS hook's fate, read straight from the hook: volatile inside
	 * {@link WinMouseHook}, written on the pump thread, read on the EDT.
	 */
	public boolean isHookInstalled()
	{
		return hook.isInstalled();
	}

	public String getHookError()
	{
		return hook.getInstallError();
	}

	public int getForwardedCount()
	{
		return forwardedCount.get();
	}

	public int getDroppedCount()
	{
		return droppedCount.get();
	}

	public int getSkippedCount()
	{
		return skippedCount.get();
	}

	public Point getLastPhysical()
	{
		return lastPhysical;
	}

	public Point getLastMapped()
	{
		return lastMapped;
	}

	/**
	 * One half of a hardware click, handed from the OS hook lane to the
	 * dispatcher lane. Immutable; nothing in it references the client.
	 */
	private static final class PhysicalClick
	{
		final boolean pressed;
		final boolean leftButton;
		final int screenX;
		final int screenY;
		final long atMs;

		PhysicalClick(boolean pressed, boolean leftButton, int screenX, int screenY)
		{
			this.pressed = pressed;
			this.leftButton = leftButton;
			this.screenX = screenX;
			this.screenY = screenY;
			this.atMs = System.currentTimeMillis();
		}
	}
}
