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
package net.runelite.client.plugins.sahij.agility;

import com.google.inject.Provides;
import java.awt.HeadlessException;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.sahij.agility.engine.AgilityCourse;
import net.runelite.client.plugins.sahij.agility.engine.AgilitySequenceEngine;
import net.runelite.client.plugins.sahij.agility.engine.AgilityStep;
import net.runelite.client.plugins.sahij.agility.engine.PlayerState;
import net.runelite.client.plugins.sahij.agility.engine.StepTile;
import net.runelite.client.plugins.sahij.agility.menu.CourseMenuEntries;
import net.runelite.client.plugins.sahij.agility.overlay.AgilityStatusOverlay;
import net.runelite.client.plugins.sahij.agility.world.CourseValidator;
import net.runelite.client.plugins.sahij.agility.world.CourseValidator.SceneObjectTarget;
import net.runelite.client.plugins.sahij.agility.world.ScreenZoneMapper;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sahij Agility - a strict 7-step, 1:1 human-mapped agility sequence for the
 * documented course (region 10806; exact tiles, chunk ids and object ids are
 * spec-locked in {@link AgilityCourse}).
 *
 * Data flow, end to end:
 *
 *   ONE real click (physical mouse, or forwarded by the sibling
 *   kuri-backgroundclicker plugin - this module shares no code and no state
 *   with it and meets it only at the client's own input pipeline)
 *     -&gt; the injected step entry: a REAL action row for obstacle steps
 *        (type/identifier/params copied verbatim from the live scene
 *        object, verified present and verb-bound at menu build - the same
 *        entry shape the client's own menu builder produces), or a
 *        client-local row for the walk step
 *     -&gt; the engine's 1:1 GATES run at click time (one click per game
 *        tick, none while the previous one still awaits server-confirmed
 *        movement - click spam can never become action spam; a gated click
 *        is consumed so the native pipeline cannot run it either)
 *     -&gt; the walk click is consumed and its destination goes through the
 *        scene walk state (the client's own walk processor - the same path
 *        a real tile click takes; the vanilla walk arm would otherwise
 *        follow the mouse hover tile). The obstacle click is left
 *        UNCONSUMED: the client's own object-click pipeline performs it
 *        natively - its approach routing, its packet, its UI trail. NO
 *        synthetic dispatch exists anywhere: the server only ever receives
 *        what a hand click on the obstacle would send, and the cursor's
 *        primary-monitor bracket (N tiles = N equal screen zones,
 *        world/ScreenZoneMapper) records the human correlation without
 *        altering the packet
 *     -&gt; the engine arms a bounded movement observation; the sequence
 *        advances only when the SERVER moves the player EXACTLY onto the
 *        completed step's explicit arrival tile (strict x/y/plane match,
 *        with the dispatched-click fact verified - chunk ids are never
 *        consulted for progression), and the Z-plane safety fallback
 *        (steps 3-5, plane drops to 0) resets to step 1 immediately.
 *
 * Nothing runs without the click: no scheduler, no loop, no retries. The
 * engine holds the only game-state opinion; this class is deliberately thin
 * - every @Subscribe hook translates one RuneLite event into one call into
 * the engine / world / menu layers and holds no game logic itself.
 */
@PluginDescriptor(
	name = "Sahij Agility",
	description = "1:1 human-mapped 7-step agility sequence: the current step "
		+ "is derived from your server-confirmed position, each click picks "
		+ "its tile from your primary-monitor cursor zone, and one click "
		+ "performs one ordinary game action",
	tags = {"agility", "sahij", "oneclick", "course"},
	enabledByDefault = false
)
public class SahijAgilityPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(SahijAgilityPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private SahijAgilityConfig config;

	@Inject
	private AgilityStatusOverlay overlay;

	private final AgilityCourse course = AgilityCourse.create();
	private final AgilitySequenceEngine engine = new AgilitySequenceEngine(course);

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
	SahijAgilityConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SahijAgilityConfig.class);
	}

	/**
	 * Login clears everything (global reset): session state, stats and the
	 * one-time identity check are stale after any world change.
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
	 * Course-identity check (fail visible - definition problems surface
	 * once): every step tile must agree with its documented chunk/region id
	 * under coordinate arithmetic. One chat line, ever, per login; the
	 * overlay keeps showing the mismatch until it is fixed.
	 */
	private void checkIdentityOnce()
	{
		if (identityChecked)
		{
			return;
		}
		identityChecked = true;

		String failure = CourseValidator.courseIdentityFailure(course);
		if (failure != null)
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value("Sahij Agility: course definition mismatch - " + failure)
				.build());
			log.error("Sahij Agility: course definition mismatch: {}", failure);
		}
	}

	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// The single per-tick server observation that drives every step
		// transition, the Z-plane safety fallback and click confirmation.
		engine.observe(playerState());
	}

	/**
	 * Menu injection hook: the menu rebuilds every game tick, and
	 * MenuEntryAdded fires as it is being built. Gates, in order: logged-in
	 * with a local player, then the region gate (config) keeping the whole
	 * subsystem inert outside region 10806. The entry is the current step's
	 * client-local VISUAL row (a RUNELITE row carries no game packet of its
	 * own); execution happens at click time - the walk via the scene walk
	 * state, obstacles via the swap &amp; execute path that dispatches the
	 * client's OWN native menu entry parameters. The dedup guard keeps
	 * exactly one entry per build.
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

		if (config.regionGate() && !CourseValidator.playerInCourseRegion(client))
		{
			injectedReason = "outside course region";
			return;
		}

		injectedReason = "";

		// Menu manipulation is confined to the menu package.
		if (CourseMenuEntries.isPresent(client.getMenu().getMenuEntries()))
		{
			return; // already present on this build
		}
		CourseMenuEntries.inject(client.getMenu(), engine.getCurrentStep());
	}

	/**
	 * Pinning (config, default ON): the entry is swapped into the FIRST slot
	 * of every menu build - the top of the rendered menu for visibility, and
	 * by the client's ordering rules also the LEFT-CLICK DEFAULT action, so
	 * a plain forwarded click executes the current step. This is a
	 * deliberate left-click redirect while the plugin is enabled; disabling
	 * the pin (or the plugin) restores the game's own default action.
	 */
	@Subscribe
	private void onPostMenuSort(PostMenuSort event)
	{
		if (!config.pinEntryToTop())
		{
			return;
		}
		CourseMenuEntries.pinToTop(client.getMenu());
	}

	/**
	 * Click interception - the 1:1 contract's enforcement point. Both step
	 * flavors arrive as clicks on the injected RUNELITE row and branch by
	 * the engine's current step:
	 *
	 *   WALK: consumed, destination dispatched into the scene walk state
	 *   (the client's own walk processor - the vanilla walk arm would
	 *   otherwise follow the mouse hover tile).
	 *
	 *   OBSTACLE: SWAP &amp; EXECUTE. The client's own native obstacle row
	 *   for the step's object is located in the LIVE menu buffer and its
	 *   exact parameters (type, identifier, param0/param1, option, target)
	 *   are dispatched VERBATIM through the client's standard action method
	 *   - the server receives the same interaction a manual click on the
	 *   native row produces, and the client's standard range/pathfinding
	 *   handling applies because the dispatch is the client's own. The
	 *   click event itself stays unconsumed (a RUNELITE row has no native
	 *   arm to leak); nothing hand-built is ever sent.
	 *
	 * The engine's 1:1 gates run first in both flavors (one click per game
	 * tick; none while the previous one still awaits server-confirmed
	 * movement).
	 */
	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!CourseMenuEntries.matchesStepClick(event))
		{
			return;
		}

		if (engine.getCurrentStep().isWalk())
		{
			// Consume-first: the vanilla walk arm must never see this click.
			event.consume();
			handleWalkClick();
			return;
		}

		handleObstacleClick(event);
	}

	private void handleWalkClick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			engine.refuseClick("no local player at click time");
			return;
		}

		// 1:1 gates BEFORE any resolution work: one dispatch per game tick,
		// and none while the previous dispatch still awaits server movement.
		String gate = engine.clickGateReason();
		if (gate != null)
		{
			engine.refuseClick(gate);
			return;
		}

		AgilityStep step = engine.getCurrentStep();
		PlayerState player = playerState();
		if (player == null)
		{
			engine.refuseClick("local player lost at click time");
			return;
		}

		// The walk step is single-tile: no pointer read, the tile is the
		// destination.
		StepTile tile = step.soleTile();
		if (tile.getPlane() != player.getPlane())
		{
			engine.refuseClick("walk tile plane " + tile.getPlane()
				+ " but player is on plane " + player.getPlane());
			return;
		}

		int[] scene = CourseValidator.toSceneCoords(client, tile);
		if (scene == null)
		{
			engine.refuseClick("walk tile " + tile + " not in the loaded scene");
			return;
		}

		// MenuOptionClicked is delivered on the client thread, so
		// ClientThread.invoke runs this inline, in the same cycle the client
		// samples its walk state in.
		clientThread.invoke(() ->
		{
			WorldView worldview = client.getTopLevelWorldView();
			if (worldview.dispatchDirectWalk(scene[0], scene[1]))
			{
				// The client's own walk processor performs the walk from the
				// injected state - its own packet, pathing and marker.
				engine.recordClick(engine.getStepIndex(), 0, -1, player);
			}
			else
			{
				engine.refuseClick("direct walk dispatch unavailable in this client build");
			}
			return true; // never re-run
		});
	}

	private void handleObstacleClick(MenuOptionClicked event)
	{
		// 1:1 gates FIRST. A gated click is CONSUMED - the native pipeline
		// must not run it either, or the gate would be cosmetic.
		String gate = engine.clickGateReason();
		if (gate != null)
		{
			event.consume();
			engine.refuseClick(gate);
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			event.consume();
			engine.refuseClick("no local player at click time");
			return;
		}

		AgilityStep step = engine.getCurrentStep();

		// SWAP & EXECUTE, primary path: the client's OWN native obstacle row
		// for this object, live in the current menu build. Its parameters
		// are exactly what the client assembled for a manual click - copying
		// them verbatim is what makes the dispatch byte-identical.
		MenuEntry nativeEntry = CourseMenuEntries.findNativeObstacleEntry(
			client.getMenu().getMenuEntries(), step.getObjectId());

		// Cursor-bracket telemetry: records the human correlation and the
		// selected tile index; never alters what is dispatched.
		int mouseX = readPrimaryMouseX();
		int tileIndex = mouseX >= 0
			? ScreenZoneMapper.zoneIndex(mouseX, config.monitorWidth(), step.getTiles().size())
			: 0;

		if (nativeEntry != null)
		{
			// Dispatch through the client's standard action method with the
			// native row's own fields. MenuOptionClicked is delivered on the
			// client thread, so the invoke runs inline - the same cycle the
			// client processes its own actions in, range/pathfinding rules
			// included. The click event stays unconsumed: a RUNELITE row has
			// no native arm, so exactly ONE game action follows.
			clientThread.invoke(() ->
			{
				client.menuAction(nativeEntry.getParam0(), nativeEntry.getParam1(),
					nativeEntry.getType(), nativeEntry.getIdentifier(), -1,
					nativeEntry.getOption(), nativeEntry.getTarget());
				engine.recordClick(engine.getStepIndex(), tileIndex, mouseX, playerState());
				return true; // never re-run
			});
			return;
		}

		// Fallback when the current menu build carries no native row for the
		// object (the click opened away from the obstacle): resolve the live
		// object and the composition-bound verb - still the client's own
		// action method, still verified parameters, never a guess.
		SceneObjectTarget target = CourseValidator.findObjectInStep(client, step, step.soleTile());
		MenuAction variant = target == null
			? null
			: CourseValidator.resolveActionVariant(client, step.getObjectId(), step.getAction());
		if (target == null || variant == null)
		{
			event.consume();
			engine.refuseClick("no native menu entry for object " + step.getObjectId()
				+ " and no verifiable fallback ("
				+ (target == null
					? "object not on step " + step.getNumber() + "'s tiles"
					: "\"" + step.getAction() + "\" not bindable - offers "
						+ CourseValidator.describeActions(client, step.getObjectId()))
				+ ")");
			return;
		}

		clientThread.invoke(() ->
		{
			client.menuAction(target.getSceneX(), target.getSceneY(), variant,
				step.getObjectId(), -1, step.getAction(), step.getTarget());
			engine.recordClick(engine.getStepIndex(), tileIndex, mouseX, playerState());
			return true; // never re-run
		});
	}

	/**
	 * The primary-monitor cursor X at click time, in the same physical-pixel
	 * space {@code monitorWidth} declares. Negative return = unreadable. A
	 * cursor on a monitor left of the primary reports negative virtual-screen
	 * X; the zone mapper clamps it to the first zone (documented in
	 * ScreenZoneMapper).
	 */
	private int readPrimaryMouseX()
	{
		try
		{
			PointerInfo info = MouseInfo.getPointerInfo();
			return info != null ? info.getLocation().x : -1;
		}
		catch (HeadlessException | SecurityException ex)
		{
			return -1;
		}
	}

	private PlayerState playerState()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldPoint loc = me.getWorldLocation();
		return new PlayerState(loc.getX(), loc.getY(), loc.getPlane());
	}

	// ----- overlay accessors -----

	public AgilitySequenceEngine getEngine()
	{
		return engine;
	}

	public String getInjectedReason()
	{
		return injectedReason;
	}
}
