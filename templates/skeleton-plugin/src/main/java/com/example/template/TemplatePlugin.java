package com.example.template;

import com.google.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Skeleton "one-click" style plugin demonstrating the four pieces every plugin
 * in this folder needs:
 *
 *   1. lifecycle (startUp/shutDown) with matching register/unregister,
 *   2. event handling (@Subscribe; every transient flag has a natural reset),
 *   3. a custom context-menu entry injected per ClientTick, pinned by
 *      PostMenuSort, whose clicks are captured via MenuOptionClicked,
 *   4. an OverlayPanel line showing plugin state.
 *
 * Everything here uses only public net.runelite.* APIs — no packets, no
 * reflection into client internals. Replace the marked sections with real
 * behaviour (see docs/plugin-development/).
 */
@PluginDescriptor(
	name = "Template",
	description = "Skeleton plugin: lifecycle + events + menu entry + overlay",
	tags = {"template", "skeleton"},
	enabledByDefault = false
)
public class TemplatePlugin extends Plugin
{
	// ONE constant used consistently for injection, de-duplication, PostMenuSort
	// matching and click matching (the case study shows three places drifting =
	// a bug source).
	private static final String MENU_MARKER = "Template";
	private static final String MENU_OPTION = "<col=8532cb>" + MENU_MARKER + "</col>";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TemplateConfig config;

	@Inject
	private TemplateOverlay overlay;

	// Transient state. Each flag documents its reset trigger(s) inline —
	// see docs/plugin-development/01-design-philosophy.md rule 4.
	private int clicks;          // reset: shutDown(), GameStateChanged->LOGIN_SCREEN
	private String lastAction;   // reset: shutDown(), GameStateChanged->LOGIN_SCREEN

	@Override
	protected void startUp() throws Exception
	{
		clicks = 0;
		lastAction = "idle";
		overlayManager.add(overlay);
		eventBus.register(this);
	}

	@Override
	protected void shutDown() throws Exception
	{
		eventBus.unregister(this);
		overlayManager.remove(overlay);
		// State hygiene: toggling the plugin is a natural reset trigger.
		clicks = 0;
		lastAction = "idle";
	}

	/**
	 * Login/logout clears everything: any session state is stale afterwards.
	 */
	@Subscribe
	private void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clicks = 0;
			lastAction = "idle";
		}
	}

	/**
	 * Menu injection. Runs every ClientTick, only while logged in; the guard on
	 * the last entry prevents duplicates (the client rebuilds the menu each
	 * tick, so we append anew whenever our entry is gone).
	 */
	@Subscribe
	private void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length > 0 && entries[entries.length - 1].getOption().contains(MENU_MARKER))
		{
			return; // already present
		}

		client.getMenu().createMenuEntry(-1)
			.setOption(MENU_OPTION)
			.setTarget("")
			.setType(MenuAction.RUNELITE_OVERLAY) // handled by the client itself; no game packet
			.setIdentifier(0)
			.setParam0(0)
			.setParam1(0)
			.onClick(e -> clientThread.invokeLater(this::onTemplateClick));
	}

	/**
	 * Click capture. RUNELITE_OVERLAY clicks are visible on the event bus but
	 * are never sent to the game server — this is the observable "user intent"
	 * event, mirroring how the case-study plugin arms its cycle.
	 */
	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE_OVERLAY)
		{
			return;
		}
		if (!event.getMenuOption().contains(MENU_MARKER))
		{
			return;
		}
		clicks++;
		lastAction = "menu click";
	}

	/**
	 * Pin our entry to the bottom (last slot) of the context menu so it is
	 * always in a fixed, predictable position — same technique the case-study
	 * plugin uses.
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

	/**
	 * The action behind the click. Real plugins replace this body with an
	 * event-driven task (arm a state machine, set pending flags) — NOT a
	 * chain of timed steps. This demo only touches inventory state on the
	 * client thread to show the bridge.
	 */
	private void onTemplateClick()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		lastAction = "action @ tick " + client.getTickCount();
	}

	// ----- overlay accessors (package-private) -----

	int getClicks()
	{
		return clicks;
	}

	String getLastAction()
	{
		return lastAction;
	}
}
