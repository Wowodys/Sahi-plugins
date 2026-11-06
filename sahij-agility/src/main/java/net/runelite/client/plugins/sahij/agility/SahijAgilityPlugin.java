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
 *     -&gt; the client's menu processing fires MenuOptionClicked for the
 *        injected step entry
 *     -&gt; the click is consumed; the engine's 1:1 GATES run first (one
 *        dispatched click per game tick, none while the previous dispatch
 *        still awaits server-confirmed movement - click spam can never
 *        become action spam), then the CURRENT step (position-derived, from
 *        the engine's per-tick server observations) resolves its target tile
 *        from the PRIMARY-MONITOR cursor X: N tiles = N equal screen zones,
 *        cursor bracket = tile index (world/ScreenZoneMapper) - the human's
 *        hand position is the selector, never machine RNG
 *     -&gt; exactly ONE real dispatch for that click: the walk step goes
 *        through the scene walk state (the client's own walk processor, the
 *        same path a real tile click takes), obstacle steps go through the
 *        client's object menu action with the obstacle's scene coordinates
 *        (the same packet trail as clicking the object by hand)
 *     -&gt; the engine arms a bounded movement observation; the sequence
 *        advances only when the SERVER moves the player into the NEXT
 *        step's arrival area (world-bounds + plane match, widened by the
 *        arrival margin, with the dispatched-click fact verified - chunk
 *        ids are never consulted for progression), and the Z-plane safety
 *        fallback (steps 3-5, plane drops to 0) resets to step 1
 *        immediately.
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
	 * subsystem inert outside region 10806. The entry itself is the CURRENT
	 * step's client-local row (menu package) - the dispatch happens at click
	 * time, when the cursor zone is known. The dedup guard keeps exactly one
	 * entry per build.
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
	 * Optional pinning (config, default ON): the entry is swapped into the
	 * bottom slot of every menu build for a stable position - the property
	 * that makes it reliably clickable through the background clicker's
	 * safe-zone mapping.
	 */
	@Subscribe
	private void onPostMenuSort(PostMenuSort event)
	{
		if (!config.pinEntryToBottom())
		{
			return;
		}
		CourseMenuEntries.pinToBottom(client.getMenu());
	}

	/**
	 * Click interception - the 1:1 contract's enforcement point. The click on
	 * the injected entry is CONSUMED immediately (a RUNELITE-type row never
	 * dispatches anything by itself), then the engine's 1:1 gates run FIRST
	 * (one dispatched click per game tick; no second click while the previous
	 * one still awaits server-confirmed movement - spam can never become
	 * action), and only then does exactly one real dispatch run: zone mapping
	 * -&gt; tile -&gt; validation -&gt; walk state or object menu action. On
	 * refusal the click stays consumed, the reason is surfaced through the
	 * engine (fail visible), and nothing is dispatched - an unvalidated or
	 * ungated request never clicks.
	 */
	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!CourseMenuEntries.matchesClick(event))
		{
			return;
		}
		event.consume();

		// Consume-first means the handler below must either dispatch once or
		// refuse visibly; there is no path that lets the vanilla pipeline
		// act on this click.
		handleStepClick();
	}

	private void handleStepClick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			engine.refuseClick("no local player at click time");
			return;
		}

		// 1:1 gates BEFORE any resolution work: one dispatch per game tick,
		// and none while the previous dispatch still awaits server movement.
		// MenuOptionClicked is delivered on the client thread and
		// ClientThread.invoke runs inline there, so the gates see every click
		// in strict arrival order with no deferred-dispatch race.
		String gate = engine.clickGateReason();
		if (gate != null)
		{
			engine.refuseClick(gate);
			return;
		}

		AgilityStep step = engine.getCurrentStep();
		PlayerState player = playerState();

		// 1. Cursor -> zone -> tile. Single-tile steps need no pointer read;
		//    multi-tile steps refuse (never guess) if the pointer is unreadable.
		int mouseX = -1;
		if (step.getTiles().size() > 1)
		{
			mouseX = readPrimaryMouseX();
			if (mouseX < 0)
			{
				engine.refuseClick("primary-monitor pointer position unavailable");
				return;
			}
		}
		int tileIndex = ScreenZoneMapper.zoneIndex(mouseX, config.monitorWidth(), step.getTiles().size());
		StepTile tile = step.getTiles().get(tileIndex);

		// 2. Player-state validation (world package semantics, enforced here
		//    against the engine's pure values).
		if (player == null)
		{
			engine.refuseClick("local player lost at click time");
			return;
		}
		if (tile.getPlane() != player.getPlane())
		{
			engine.refuseClick("step tile plane " + tile.getPlane()
				+ " but player is on plane " + player.getPlane());
			return;
		}
		boolean onStepTiles = step.containsTile(player.getX(), player.getY(), player.getPlane());
		if (!onStepTiles)
		{
			if (config.requireStepTile())
			{
				engine.refuseClick("player at " + player + " is not on step "
					+ step.getNumber() + "'s tiles (strict mode)");
				return;
			}
			// Lenient mode: proceed, but say so - never silently.
			log.info("Sahij Agility: clicking step {} while standing off its tile set at {}",
				step.getNumber(), player);
		}

		// 3. Scene representability - a real action can only be honest for a
		//    tile the loaded scene can see.
		int[] scene = CourseValidator.toSceneCoords(client, tile);
		if (scene == null)
		{
			engine.refuseClick("zone tile " + tile + " not in the loaded scene");
			return;
		}

		// 4. The single real dispatch for this click. MenuOptionClicked is
		//    delivered on the client thread, so ClientThread.invoke runs this
		//    inline, in the same cycle the client samples its action state in.
		final int fTileIndex = tileIndex;
		final int fMouseX = mouseX;
		final PlayerState fPlayer = player;
		if (step.isWalk())
		{
			clientThread.invoke(() ->
			{
				WorldView worldview = client.getTopLevelWorldView();
				if (worldview.dispatchDirectWalk(scene[0], scene[1]))
				{
					// The client's own walk processor performs the walk from
					// the injected state - its own packet, pathing and marker.
					engine.recordClick(engine.getStepIndex(), fTileIndex, fMouseX, fPlayer);
				}
				else
				{
					engine.refuseClick("direct walk dispatch unavailable in this client build");
				}
				return true; // never re-run
			});
			return;
		}

		// Obstacle step: exact-id lookup on the step's own tiles; the
		// zone-selected tile also disambiguates multiple instances (nearest
		// wins), so the cursor stays the sole selector.
		SceneObjectTarget target = CourseValidator.findObjectInStep(client, step, tile);
		if (target == null)
		{
			engine.refuseClick("object " + step.getObjectId() + " ("
				+ step.getTarget() + ") not found on step " + step.getNumber()
				+ "'s tiles in the loaded scene");
			return;
		}

		MenuAction variant = CourseValidator.resolveActionVariant(client, step.getObjectId(), step.getAction());
		final MenuAction dispatchType;
		if (variant == null)
		{
			// Fail visible into the log, dispatch the first variant - agility
			// obstacles expose their verb as the first composition action.
			dispatchType = MenuAction.GAME_OBJECT_FIRST_OPTION;
			log.warn("Sahij Agility: action \"{}\" not found on object {} composition; "
				+ "dispatching first option", step.getAction(), step.getObjectId());
		}
		else
		{
			dispatchType = variant;
		}

		clientThread.invoke(() ->
		{
			// The client's own object action dispatch - the ordinary packet
			// trail of clicking the obstacle by hand, at its scene tile.
			client.menuAction(target.getSceneX(), target.getSceneY(), dispatchType,
				step.getObjectId(), -1, step.getAction(), step.getTarget());
			engine.recordClick(engine.getStepIndex(), fTileIndex, fMouseX, fPlayer);
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
