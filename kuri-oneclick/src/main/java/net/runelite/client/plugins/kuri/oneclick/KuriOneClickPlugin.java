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
package net.runelite.client.plugins.kuri.oneclick;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.kuri.oneclick.engine.OneClickEngine;
import net.runelite.client.plugins.kuri.oneclick.engine.PlayerSnapshot;
import net.runelite.client.plugins.kuri.oneclick.location.WalkToTileAction;
import net.runelite.client.plugins.kuri.oneclick.menu.WalkMenuEntries;
import net.runelite.client.plugins.kuri.oneclick.world.RsCoords;
import net.runelite.client.plugins.kuri.oneclick.world.TileCoordinateValidator;
import net.runelite.client.plugins.kuri.oneclick.overlay.LocationClickOverlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-Click Location - a 1:1 click at a fixed world tile.
 *
 * Target (documented reference): tile (2800, 9568) plane 3,
 * chunk 717996, region 11157.
 *
 * The plugin injects ONE real-action context-menu entry ("Walk" via
 * MenuAction.WALK with the target's scene coordinates in param0/param1).
 * The entry click is consumed and the target's scene coordinates are
 * dispatched straight into the scene walk state
 * ({@link WorldView#dispatchDirectWalk(int, int)}): the client's own walk
 * processor then performs the walk exactly as it would for a real tile
 * click - one click in, one ordinary game action out, nothing synthetic
 * follows. Bypassing the native dispatch is required because the vanilla
 * opcode-23 arm sources its destination from the mouse hover tile, not
 * from the entry's parameters: an unconsumed WALK entry click would walk
 * to whatever tile the cursor happens to be over.
 *
 * The framework's role is only to validate at click time (world package) and
 * to confirm from the server's own reactions afterwards (engine package);
 * all menu-array work lives in the menu package. See OneClickEngine.
 *
 * This class is deliberately thin: every @Subscribe hook here only
 * translates a RuneLite event into a call into one of the three layers -
 * it holds no game logic itself.
 */
@PluginDescriptor(
	name = "Kuri One-Click Location",
	description = "One-click, one walk: a real WALK menu entry at a fixed tile, "
		+ "click-time validation, server-confirmed, no retries",
	tags = {"oneclick", "walk", "tile"},
	enabledByDefault = false
)
public class KuriOneClickPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(KuriOneClickPlugin.class);

	// The documented identity of the default target - verified against the
	// configured tile on first tick (config typos surface, not silently click).
	public static final int REFERENCE_REGION_ID = 11157;
	public static final int REFERENCE_CHUNK_ID = 717996;
	public static final int REFERENCE_X = 2800;
	public static final int REFERENCE_Y = 9568;
	public static final int REFERENCE_PLANE = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private KuriOneClickConfig config;

	@Inject
	private LocationClickOverlay overlay;

	private final OneClickEngine engine = new OneClickEngine();

	// Transient state - every flag documents its reset trigger(s).
	private boolean identityChecked;    // reset: shutDown(), LOGIN_SCREEN
	private String injectedReason = ""; // why the entry is absent right now; re-evaluated on every menu build

	@Override
	protected void startUp() throws Exception
	{
		engine.reset("plugin startUp");
		identityChecked = false;
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		engine.reset("plugin shutDown");
		identityChecked = false;
	}

	/**
	 * The plugin is installed as a Guice module (PluginModuleFactory), so its
	 * @Provides methods supply the child injector - this is how modern
	 * RuneLite binds a plugin's config interface.
	 */
	@Provides
	KuriOneClickConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KuriOneClickConfig.class);
	}

	/**
	 * Login clears everything (global reset): session state and the one-time
	 * identity check are stale after any world change.
	 */
	@Subscribe
	private void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			engine.reset("login screen");
			engine.clearStats();
			identityChecked = false;
		}
	}

	/**
	 * Target-identity check (fail visible - fatal config problems surface
	 * once): the configured tile must map to the documented chunk 717996 /
	 * region 11157. One chat line, ever, per login; the overlay shows it
	 * permanently.
	 */
	private void checkIdentityOnce()
	{
		if (identityChecked)
		{
			return;
		}
		identityChecked = true;

		WorldPoint target = targetPoint();
		int region = RsCoords.regionId(target.getX(), target.getY());
		int chunk = RsCoords.chunkId(target.getX(), target.getY());
		if (region != REFERENCE_REGION_ID || chunk != REFERENCE_CHUNK_ID)
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value("Kuri One-Click: configured tile (" + target.getX() + ", "
					+ target.getY() + ") maps to region " + region + " / chunk "
					+ chunk + ", expected region " + REFERENCE_REGION_ID
					+ " / chunk " + REFERENCE_CHUNK_ID + " - check the config")
				.build());
		}
	}

	public WorldPoint targetPoint()
	{
		return new WorldPoint(config.targetX(), config.targetY(), config.targetPlane());
	}

	PlayerSnapshot targetSnapshot()
	{
		return new PlayerSnapshot(config.targetX(), config.targetY(), config.targetPlane());
	}

	private PlayerSnapshot playerSnapshot()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldPoint loc = me.getWorldLocation();
		return new PlayerSnapshot(loc.getX(), loc.getY(), loc.getPlane());
	}

	/**
	 * Menu injection hook: the menu rebuilds every game tick, and
	 * MenuEntryAdded fires as it is being built. Gates, in order:
	 *   1. logged in + local player,
	 *   2. region gate (config): player inside the target's region,
	 *   3. target tile in the loaded scene with a real tile under it.
	 * A real WALK entry can only be honest for a tile the scene can see.
	 * The dedup guard keeps exactly one entry per build.
	 */
	@Subscribe
	private void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			injectedReason = "not logged in";
			return;
		}
		checkIdentityOnce();

		WorldPoint target = targetPoint();

		if (config.regionGate()
			&& !TileCoordinateValidator.playerInTargetRegion(client, target))
		{
			injectedReason = "outside target region";
			return;
		}

		int[] scene = TileCoordinateValidator.toSceneCoords(client, target);
		if (scene == null)
		{
			injectedReason = "target tile not in scene";
			return;
		}

		injectedReason = "";

		// Menu manipulation is confined to the menu package.
		if (WalkMenuEntries.isPresent(client.getMenu().getMenuEntries()))
		{
			return; // already present on this build
		}
		WalkMenuEntries.inject(client.getMenu(), scene[0], scene[1]);
	}

	/**
	 * Click capture. The entry click is CONSUMED immediately: the vanilla
	 * opcode-23 dispatch arm sources its destination from the scene's mouse
	 * hover latch, so an unconsumed WALK entry click would walk to the tile
	 * under the cursor instead of the entry's target. The click's
	 * param0/param1 (the target's scene coordinates, injected at menu build
	 * time) are instead dispatched straight into the scene walk state
	 * (WorldView#dispatchDirectWalk) on the client thread - the client's own
	 * walk processor then sends the ordinary walk packet for the target tile.
	 *
	 * Guardrails (world package) run before the dispatch: logged-in state,
	 * the target tile being in-scene on the active plane, and the click's
	 * coordinates agreeing with the configured target. On refusal the click
	 * is consumed (it already is), a structured warning is logged, and no
	 * state is armed - an unvalidated request never walks.
	 */
	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!WalkMenuEntries.matchesClick(event))
		{
			return;
		}

		// Consume first: the vanilla opcode-23 arm must never see this click.
		event.consume();

		int sceneX = event.getParam0();
		int sceneY = event.getParam1();

		String failure = clickTimeFailure(sceneX, sceneY);
		if (failure != null)
		{
			engine.refuseArm(failure);
			log.warn("One-Click Walk refused: reason=\"{}\" target={} clickScene=({}, {}) activePlane={}",
				failure, targetPoint(), sceneX, sceneY, client.getTopLevelWorldView().getPlane());
			return;
		}

		// Dispatch on the client thread. MenuOptionClicked is delivered on it,
		// so ClientThread.invoke runs this inline, within the same cycle the
		// walk processor samples the walk state in.
		clientThread.invoke(() ->
		{
			if (client.getTopLevelWorldView().dispatchDirectWalk(sceneX, sceneY))
			{
				// Armed: the client's walk processor performs the walk from
				// the injected state - its own packet, pathing and marker.
				engine.requestArm(new WalkToTileAction(targetSnapshot()), playerSnapshot());
			}
			else
			{
				engine.refuseArm("direct walk dispatch unavailable in this client build");
			}
			return true; // never re-run
		});
	}

	/** Click-time validation, world package. Null = safe to dispatch. */
	private String clickTimeFailure(int sceneX, int sceneY)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return "no local player at click time";
		}

		WorldPoint target = targetPoint();
		WorldView worldview = client.getTopLevelWorldView();

		// The walk state carries scene coordinates only; the client walks on
		// the active plane, so a target on another plane cannot be honored.
		if (target.getPlane() != worldview.getPlane())
		{
			return "target plane " + target.getPlane()
				+ " differs from the active plane " + worldview.getPlane();
		}

		int[] scene = TileCoordinateValidator.toSceneCoords(client, target);
		if (scene == null)
		{
			return "target tile not in scene at click time";
		}

		// The click's coordinates are the injected payload; they must still
		// agree with the target's fresh scene coordinates (scene rebases and
		// stale menus would otherwise walk somewhere else).
		if (scene[0] != sceneX || scene[1] != sceneY)
		{
			return "click scene coordinates (" + sceneX + ", " + sceneY
				+ ") do not match the target's (" + scene[0] + ", " + scene[1] + ")";
		}

		return null;
	}

	/** The confirmation metronome: exactly one server-observation per tick. */
	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		engine.onGameTick(playerSnapshot());
	}

	/**
	 * Unexpected interface = the world changed under armed intent; reset
	 * instead of letting stale intent confirm later.
	 */
	@Subscribe
	private void onWidgetLoaded(WidgetLoaded event)
	{
		if (engine.isArmed())
		{
			engine.reset("interface group " + event.getGroupId() + " opened mid-cycle");
		}
	}

	/**
	 * Optional pinning (config, default OFF): our entry is swapped into the
	 * bottom slot of every menu build for a stable position - away from the
	 * left-click default.
	 */
	@Subscribe
	private void onPostMenuSort(PostMenuSort event)
	{
		if (!config.pinEntryToBottom())
		{
			return;
		}
		WalkMenuEntries.pinToBottom(client.getMenu());
	}

	// ----- overlay accessors -----

	public OneClickEngine getEngine()
	{
		return engine;
	}

	public String getInjectedReason()
	{
		return injectedReason;
	}
}
