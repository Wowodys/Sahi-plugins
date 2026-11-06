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
package net.runelite.client.plugins.sahij.agility.menu;

import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.plugins.sahij.agility.engine.AgilityStep;

/**
 * All menu-array work for the course's step entry, isolated from the state
 * machine (engine package) and the coordinate work (world package).
 *
 * The injected row is a client-local VISUAL entry ({@code MenuAction.RUNELITE}):
 * it renders in the ordinary menu and is pinned to the TOP slot (index 0) for
 * visibility - which also makes it the left-click default, so a plain click
 * (hand or background-clicker forwarded) executes the current step. A
 * RUNELITE-type row never emits a game packet by itself; execution is the
 * plugin's SWAP &amp; EXECUTE at click time:
 *
 *   1. locate the client's OWN native obstacle row for the step's object in
 *      the live menu buffer ({@link #findNativeObstacleEntry}) - the row the
 *      client assembled from the hovered object, with the exact parameters
 *      it would dispatch for a manual click;
 *   2. dispatch that native action through the client's standard action
 *      method with the parameters copied VERBATIM from the native row -
 *      type (packet interaction type), identifier (object id), param0/param1
 *      (scene tile), even the option/target strings. The server receives a
 *      byte-identical interaction, and the client's standard range/
 *      pathfinding handling applies because the dispatch goes through the
 *      client's own processing path, not a hand-built imitation of it.
 *
 * The walk step keeps the kuri-oneclick-proven variant: consume the click
 * and dispatch the destination into the scene walk state (the vanilla walk
 * arm sources its destination from the mouse hover tile, so an unconsumed
 * walk entry would walk wherever the cursor happens to be).
 *
 * One marker constant is used in every place the entry is touched:
 * injection option text, the dedup guard, the pin finder and the click
 * matcher (drift between them is the classic silent bug).
 */
public final class CourseMenuEntries
{
	/** The single marker for injection, dedup, pinning and click matching. */
	public static final String MENU_MARKER = "Sahij";

	private CourseMenuEntries()
	{
	}

	/** The rendered option text for a step, e.g. "Sahij 3: Jump Gap". */
	public static String optionFor(AgilityStep step)
	{
		return "<col=8532cb>" + MENU_MARKER + "</col> " + step.getNumber() + ": "
			+ step.getAction()
			+ (step.getTarget().isEmpty() ? "" : " " + step.getTarget());
	}

	/** Whether our entry is already on the current menu build (dedup guard). */
	public static boolean isPresent(MenuEntry[] entries)
	{
		return findIndex(entries) != -1;
	}

	/** Index of our entry on the current menu build, or -1. */
	public static int findIndex(MenuEntry[] entries)
	{
		for (int i = 0; i < entries.length; i++)
		{
			if (entries[i].getOption().contains(MENU_MARKER))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Appends the current step's client-local visual entry. All parameters
	 * are neutral - a RUNELITE row carries no game action of its own; the
	 * real action is dispatched at click time by the swap &amp; execute
	 * handler. The option text carries the step number so the row reads as
	 * the sequence position it is.
	 */
	public static void inject(Menu menu, AgilityStep step)
	{
		menu.createMenuEntry(-1)
			.setOption(optionFor(step))
			.setTarget("")
			.setType(MenuAction.RUNELITE)
			.setIdentifier(-1)
			.setParam0(0)
			.setParam1(0)
			.setForceLeftClick(false);
	}

	/** Whether a MenuOptionClicked event is a click on our injected entry. */
	public static boolean matchesStepClick(MenuOptionClicked event)
	{
		return event.getMenuAction() == MenuAction.RUNELITE
			&& event.getMenuOption().contains(MENU_MARKER);
	}

	/**
	 * Locates the client's OWN native obstacle row for the exact object id
	 * in the live menu buffer: a {@code GAME_OBJECT_*} entry whose
	 * identifier equals the id - the row the client built from the hovered
	 * object, carrying the exact parameters it dispatches for a manual
	 * click. Matching is by action family and identifier only (never by
	 * rendered text, which carries color tags and translations).
	 *
	 * @return the native entry, or null when the current menu build carries
	 *         no row for the object (e.g. the click opened away from the
	 *         obstacle - the caller then falls back to the verified
	 *         live-object scan)
	 */
	public static MenuEntry findNativeObstacleEntry(MenuEntry[] entries, int objectId)
	{
		for (MenuEntry entry : entries)
		{
			if (entry.getIdentifier() == objectId && isGameObjectVariant(entry.getType()))
			{
				return entry;
			}
		}
		return null;
	}

	/** The GAME_OBJECT action family (packet interaction types 3-6 and 1001). */
	private static boolean isGameObjectVariant(MenuAction action)
	{
		return action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
	}

	/**
	 * Pinning: swap our entry into the FIRST slot (index 0) of every menu
	 * build - the TOP of the rendered menu, for visibility, and by the
	 * client's ordering rules also the LEFT-CLICK DEFAULT action: a plain
	 * left-click anywhere (hand or background-clicker forwarded) executes
	 * the current step without opening the menu. That is this plugin's
	 * intended operating mode - and it means every ordinary left-click on
	 * the world executes the step action, which must be understood before
	 * enabling the plugin.
	 */
	public static void pinToTop(Menu menu)
	{
		MenuEntry[] entries = menu.getMenuEntries();
		int idx = findIndex(entries);
		if (idx == -1 || idx == 0)
		{
			return;
		}

		MenuEntry ours = entries[idx];
		entries[idx] = entries[0];
		entries[0] = ours;
		menu.setMenuEntries(entries);
	}
}
