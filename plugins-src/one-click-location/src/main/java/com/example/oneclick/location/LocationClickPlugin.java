package com.example.oneclick.location;

import com.google.inject.Inject;
import com.example.oneclick.framework.ClickState;
import com.example.oneclick.framework.OneClickEngine;
import com.example.oneclick.framework.RsCoords;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.ChatMessageType;

/**
 * One-Click Location - a 1:1 click at a fixed world tile.
 *
 * Target (documented reference): tile (2800, 9568) plane 3,
 * chunk 717996, region 11157.
 *
 * The plugin injects ONE real-action context-menu entry ("Walk" via
 * MenuAction.WALK with the target's scene coordinates). Clicking it makes the
 * client perform the ordinary walk-here action exactly as if the player had
 * clicked the tile themselves: one click in, one ordinary game action out,
 * the standard UI trail - nothing synthetic follows (philosophy rule 2).
 *
 * The framework's role is only to validate at click time and to confirm from
 * the server's own reactions afterwards; see OneClickEngine.
 */
@PluginDescriptor(
	name = "One-Click Location",
	description = "One-click, one walk: a real WALK menu entry at a fixed tile, "
		+ "click-time validation, server-confirmed, no retries",
	tags = {"oneclick", "walk", "tile"},
	enabledByDefault = false
)
public class LocationClickPlugin extends Plugin
{
	// ONE marker constant used in: injection option text, the de-dup guard,
	// the PostMenuSort finder and the click matcher (docs 03 §3).
	private static final String MENU_MARKER = "One-Click";
	private static final String MENU_OPTION = "<col=8532cb>" + MENU_MARKER + "</col> Walk";

	// The documented identity of the default target - verified against the
	// configured tile on first tick (config typos surface, not silently click).
	static final int REFERENCE_REGION_ID = 11157;
	static final int REFERENCE_CHUNK_ID = 717996;
	static final int REFERENCE_X = 2800;
	static final int REFERENCE_Y = 9568;
	static final int REFERENCE_PLANE = 3;

	// Scene bounds (docs/runelite/04-gotchas.md §3: scene tile indices 0-103).
	private static final int SCENE_MAX = 103;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private LocationClickConfig config;

	@Inject
	private LocationClickOverlay overlay;

	private final OneClickEngine engine = new OneClickEngine();

	// Transient state - every flag documents its reset trigger(s) (rule 4).
	private boolean identityChecked;   // reset: shutDown(), LOGIN_SCREEN
	private String injectedReason = ""; // why the entry is absent right now; reset: re-evaluated every ClientTick

	@Override
	protected void startUp() throws Exception
	{
		engine.reset("plugin startUp");
		identityChecked = false;
		overlayManager.add(overlay);
		eventBus.register(this);
	}

	@Override
	protected void shutDown() throws Exception
	{
		eventBus.unregister(this);
		overlayManager.remove(overlay);
		engine.reset("plugin shutDown");
		identityChecked = false;
	}

	/**
	 * Login clears everything (rule 4 global reset): session state and the
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
	 * Target-identity check (rule 6 - fatal config problems surface once):
	 * the configured tile must map to the documented chunk 717996 / region
	 * 11157. One chat line, ever, per login; the overlay shows it permanently.
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
				.value("One-Click Location: configured tile (" + target.getX() + ", "
					+ target.getY() + ") maps to region " + region + " / chunk "
					+ chunk + ", expected region " + REFERENCE_REGION_ID
					+ " / chunk " + REFERENCE_CHUNK_ID + " - check the config")
				.build());
		}
	}

	WorldPoint targetPoint()
	{
		return new WorldPoint(config.targetX(), config.targetY(), config.targetPlane());
	}

	/**
	 * Menu injection, per ClientTick with de-dup guard (the menu rebuilds
	 * every tick - docs 03 §3). Gates, in order:
	 *   1. logged in + local player,
	 *   2. region gate (config): player inside the target's region,
	 *   3. target tile in the loaded scene with a real tile under it.
	 * A real WALK entry can only be honest for a tile the scene can see.
	 */
	@Subscribe
	private void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		checkIdentityOnce();

		injectedReason = "";
		WorldPoint target = targetPoint();
		Player me = client.getLocalPlayer();

		if (config.regionGate()
			&& me.getWorldLocation().getRegionID() != RsCoords.regionId(target.getX(), target.getY()))
		{
			injectedReason = "outside target region";
			return;
		}

		int[] scene = targetSceneCoords(target);
		if (scene == null)
		{
			injectedReason = "target tile not in scene";
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length > 0 && entries[entries.length - 1].getOption().contains(MENU_MARKER))
		{
			return; // already present this tick
		}

		client.getMenu().createMenuEntry(-1)
			.setOption(MENU_OPTION)
			.setTarget("")
			.setType(MenuAction.WALK)            // id 23: param0/param1 = scene x/y
			.setIdentifier(-1)
			.setParam0(scene[0])
			.setParam1(scene[1])
			.setForceLeftClick(false);
		// No onClick consumer: the client dispatches the WALK itself on this
		// one click - the definition of the 1:1 pattern.
	}

	/**
	 * World -> scene coords through the API helpers only (gotchas §3:
	 * never shift raw ints). Returns null when the target is outside the
	 * loaded scene or has no tile (blocked).
	 */
	private int[] targetSceneCoords(WorldPoint target)
	{
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
		if (local == null)
		{
			return null;
		}
		int sx = local.getSceneX();
		int sy = local.getSceneY();
		if (sx < 0 || sy < 0 || sx > SCENE_MAX || sy > SCENE_MAX)
		{
			return null;
		}
		Tile tile = client.getScene().getTiles()[target.getPlane()][sx][sy];
		if (tile == null)
		{
			return null;
		}
		return new int[]{sx, sy};
	}

	/**
	 * Click capture. The click IS the walk (real entry - the client dispatches
	 * it natively right after this event). The engine validates preconditions;
	 * on refusal the click is consumed - an unvalidated state never sends
	 * (gotchas §8).
	 */
	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.WALK
			|| !event.getMenuOption().contains(MENU_MARKER))
		{
			return;
		}

		boolean armed = engine.requestArm(new WalkToTileAction(targetPoint()), client);
		if (!armed)
		{
			event.consume();
		}
	}

	/** The confirmation metronome: exactly one server-observation per tick. */
	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		engine.onGameTick(client);
	}

	/**
	 * Unexpected interface = the world changed under armed intent; reset
	 * instead of letting stale intent confirm later (rule 4; the case study
	 * clears click states on dialog groups the same way).
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
	 * Optional pinning (config, default OFF). Pinned = topmost = default
	 * left-click - for a real WALK entry that would turn every ordinary
	 * left-click into a walk to the fixed target, so this must stay off
	 * unless the user really wants the hijack.
	 */
	@Subscribe
	private void onPostMenuSort(PostMenuSort event)
	{
		if (!config.pinEntryToBottom())
		{
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int idx = -1;
		for (int i = 0; i < entries.length; i++)
		{
			if (entries[i].getOption().contains(MENU_MARKER))
			{
				idx = i;
				break;
			}
		}
		if (idx == -1 || idx == entries.length - 1)
		{
			return;
		}

		MenuEntry ours = entries[idx];
		entries[idx] = entries[entries.length - 1];
		entries[entries.length - 1] = ours;
		client.getMenu().setMenuEntries(entries);
	}

	// ----- overlay accessors (package-private) -----

	OneClickEngine getEngine()
	{
		return engine;
	}

	String getInjectedReason()
	{
		return injectedReason;
	}

	ClickState getLastState()
	{
		return engine.getLastState();
	}
}
