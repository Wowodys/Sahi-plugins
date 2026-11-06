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
 * WARRANTIES OF MERCHANTABILITY AND A PARTICULAR PURPOSE ARE
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
 * All menu-array manipulation for the course's step entry, isolated from the
 * state machine (engine package) and the coordinate work (world package).
 *
 * The visible entry is a CLIENT-LOCAL row ({@code MenuAction.RUNELITE}):
 * it renders in the ordinary right-click menu, but a click on it never
 * dispatches anything by itself. The plugin consumes the click in
 * {@code MenuOptionClicked} and performs ONE real dispatch for it - the
 * zone-mapped walk (scene walk state, the same path a real tile click takes)
 * or the obstacle's real object menu action (the same packet trail as
 * clicking the object by hand). That indirection is REQUIRED, not cosmetic:
 * the entry is built at menu-build time, but the step's tile is not chosen
 * until click time - it is a function of the primary-monitor cursor position
 * at the moment the click fires (world/ScreenZoneMapper). A pre-parametered
 * "real-action" entry would freeze the tile choice one tick early and break
 * the 1:1 human mapping.
 *
 * One marker constant is used in all four places an entry is touched:
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
	 * Appends the current step's client-local entry to the menu. All
	 * parameters are neutral (the dispatch happens at click time with the
	 * zone-resolved coordinates); the option text carries the step number so
	 * the row reads as the sequence position it is.
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

	/**
	 * Whether a MenuOptionClicked event is a click on our injected entry.
	 * The type check plus marker keeps the matcher honest against foreign
	 * rows that merely contain the marker in a target string.
	 */
	public static boolean matchesClick(MenuOptionClicked event)
	{
		return event.getMenuAction() == MenuAction.RUNELITE
			&& event.getMenuOption().contains(MENU_MARKER);
	}

	/**
	 * Optional pinning: swap our entry with the LAST slot so it sits at the
	 * bottom of the rendered menu - a stable screen position across ticks.
	 * That stability is what makes the entry reliably clickable through the
	 * background clicker's safe-zone mapping (a physical click maps to one
	 * canvas point; the entry under that point must not shuffle between
	 * builds). Swapping with index 0 instead would make every ordinary
	 * left-click trigger the step - a left-click hijack this method
	 * deliberately does not do.
	 */
	public static void pinToBottom(Menu menu)
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
