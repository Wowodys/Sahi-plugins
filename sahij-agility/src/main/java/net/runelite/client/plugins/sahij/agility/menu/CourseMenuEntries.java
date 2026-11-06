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
 * TWO entry flavors, matched to how the client actually executes clicks:
 *
 * OBSTACLE (steps 2-7): a REAL {@code GAME_OBJECT_*} action row whose
 * parameters are the client's own - copied VERBATIM from the native menu
 * row for the object when the current build carries one (cursor over the
 * object), otherwise derived from the LIVE scene object (its own scene
 * min-location via the world layer, plus the composition-bound action
 * variant). Because the row is a real game action, the client both renders
 * it like any obstacle option AND accepts it as the LEFT-CLICK DEFAULT -
 * which is what makes forwarded clicks work WITHOUT the cursor hovering
 * the tile: a {@code RUNELITE}-typed row is never a default candidate, so
 * with one pinned, forwarded left-clicks silently executed the native entry
 * under the cursor instead (the exact hover-dependence this design
 * removes). The click is left UNCONSUMED: the client's native object arm
 * performs the interaction with the entry's parameters - its range/
 * pathfinding routing, its packet, its UI trail. Nothing synthetic is ever
 * sent.
 *
 * WALK (step 1): a client-local row ({@code MenuAction.RUNELITE}).
 * The click is CONSUMED and the destination dispatched into the scene walk
 * state ({@code WorldView#dispatchDirectWalk}) - the client's own walk
 * processor performs it. The indirection is REQUIRED: this client's walk
 * arm sources its destination from the mouse hover tile, not the entry's
 * parameters (the kuri-oneclick precedent), and a WALK-typed row would
 * otherwise walk wherever the cursor happens to be.
 *
 * PIN ORDERING - the LAST array slot is the TOP-rendered, left-click
 * DEFAULT slot in this client: first-party RuneLite promotes entries
 * exactly this way (MenuEntrySwapper's bankModeSwap swaps the chosen entry
 * with {@code menuEntries[length - 1]} "so it doesn't get sorted later").
 * Index 0 is the BOTTOM of the rendered menu - swapping there pins the
 * entry to the bottom, the inversion observed in the field. Pinning is
 * therefore a swap to the LAST slot (see {@link #pinToTop}).
 *
 * One marker constant is used in every place the entry is touched:
 * injection option text, the dedup guard, the pin finder and the click
 * matchers (drift between them is the classic silent bug). Every match is
 * an EXACT-prefix test against the injected option's colored prefix -
 * never a loose substring - so a third-party entry that merely contains
 * the marker word can never be deduped, pinned, consumed or intercepted
 * by this class.
 */
public final class CourseMenuEntries
{
	/** The single marker for injection, dedup, pinning and click matching. */
	public static final String MENU_MARKER = "Sahij";

	/**
	 * The exact colored prefix every injected option starts with; all
	 * identity checks (dedup, pin, click matching) test against this whole
	 * prefix, never a bare substring of it.
	 */
	private static final String MENU_PREFIX = "<col=8532cb>" + MENU_MARKER + "</col>";

	private CourseMenuEntries()
	{
	}

	/** The rendered option text for a step, e.g. "Sahij 3: Jump Gap". */
	public static String optionFor(AgilityStep step)
	{
		return MENU_PREFIX + " " + step.getNumber() + ": "
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
			if (entries[i].getOption().startsWith(MENU_PREFIX))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Appends the WALK step's client-local entry. All parameters are neutral
	 * (the destination is dispatched into the scene walk state at click
	 * time); the option text carries the step number so the row reads as
	 * the sequence position it is.
	 */
	public static void injectWalk(Menu menu, AgilityStep step)
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

	/**
	 * Appends an OBSTACLE step's REAL action entry. The dispatch-relevant
	 * fields are the client's own: {@code variant} (packet interaction
	 * type), {@code sceneX}/{@code sceneY} (param0/param1 - the object's
	 * scene tile) and {@code objectId} (identifier). A click on this row
	 * runs through the client's native object-click path with exactly these
	 * parameters, and the row is a valid LEFT-CLICK DEFAULT candidate - so a
	 * plain forwarded click executes the step without the cursor hovering
	 * the obstacle and without opening the menu.
	 */
	public static void injectObstacle(Menu menu, AgilityStep step, MenuAction variant,
		int sceneX, int sceneY, int objectId)
	{
		menu.createMenuEntry(-1)
			.setOption(optionFor(step))
			.setTarget(step.getTarget())
			.setType(variant)
			.setIdentifier(objectId)
			.setParam0(sceneX)
			.setParam1(sceneY)
			.setForceLeftClick(false);
	}

	/** Whether a clicked event is the WALK flavor of our entry. */
	public static boolean matchesWalkClick(MenuOptionClicked event)
	{
		return event.getMenuAction() == MenuAction.RUNELITE
			&& event.getMenuOption().startsWith(MENU_PREFIX);
	}

	/** Whether a clicked event is the OBSTACLE flavor of our entry. */
	public static boolean matchesObstacleClick(MenuOptionClicked event)
	{
		return isGameObjectVariant(event.getMenuAction())
			&& event.getMenuOption().startsWith(MENU_PREFIX);
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
	 * Locates the client's OWN native obstacle row for the exact object id
	 * in the live menu buffer: a {@code GAME_OBJECT_*} entry whose
	 * identifier equals the id - the row the client built from the hovered
	 * object, carrying the exact parameters it dispatches for a manual
	 * click. Matching is by action family and identifier only (never by
	 * rendered text, which carries color tags and translations). Present
	 * only while the cursor's hover raycast includes the object; used at
	 * menu-BUILD time so the injected row's parameters are verbatim copies
	 * whenever available.
	 *
	 * @return the native entry, or null when the current build carries no
	 *         row for the object (cursor away from the obstacle - the
	 *         caller then derives the parameters from the live object)
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

	/**
	 * Pinning: swap our entry into the LAST array slot of every menu build -
	 * the TOP-rendered, left-click-DEFAULT slot in this client (first-party
	 * MenuEntrySwapper promotes entries with exactly this swap; index 0
	 * renders at the BOTTOM, the inversion observed in the field). With the
	 * pin on, every ordinary left-click while the plugin is enabled executes
	 * the current step; disabling the pin (or the plugin) restores the
	 * game's own default action.
	 */
	public static void pinToTop(Menu menu)
	{
		MenuEntry[] entries = menu.getMenuEntries();
		int idx = findIndex(entries);
		if (idx == -1 || idx == entries.length - 1)
		{
			return;
		}

		MenuEntry ours = entries[idx];
		entries[idx] = entries[entries.length - 1];
		entries[entries.length - 1] = ours;
		menu.setMenuEntries(entries);
	}
}
