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
package net.runelite.client.plugins.kuri.oneclick.menu;

import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;

/**
 * All menu-array manipulation for the one-click WALK entry, isolated from
 * the state machine (engine package) and coordinate validation (world
 * package).
 *
 * The injected entry is a REAL action entry ({@code MenuAction.WALK}, the
 * target's scene coordinates in param0/param1), so it renders and sorts like
 * the native walk row and its parameters carry the target through the click
 * event. The plugin consumes the click rather than letting the client
 * dispatch it: the vanilla opcode-23 arm sources its destination from the
 * mouse hover tile (not from param0/param1), so an unconsumed click walks to
 * whatever tile the cursor is over. The plugin instead dispatches the target
 * coordinates straight into the scene walk state
 * ({@link net.runelite.api.WorldView#dispatchDirectWalk(int, int)}), and the
 * client's own walk processor performs the ordinary walk-here for the target
 * tile - one click in, one ordinary game action out, nothing synthetic
 * follows.
 */
public final class WalkMenuEntries
{
	// ONE marker constant used in: injection option text, the dedup guard,
	// the pin finder and the click matcher.
	public static final String MENU_MARKER = "One-Click";
	public static final String MENU_OPTION = "<col=8532cb>" + MENU_MARKER + "</col> Walk";

	private WalkMenuEntries()
	{
	}

	/** Whether our entry is already on the current menu build (dedup guard). */
	public static boolean isPresent(MenuEntry[] entries)
	{
		for (MenuEntry entry : entries)
		{
			if (entry.getOption().contains(MENU_MARKER))
			{
				return true;
			}
		}
		return false;
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
	 * Append the real WALK entry to the menu. param0/param1 are the target's
	 * scene coordinates (id 23 semantics), validated by the world layer
	 * before this is called.
	 */
	public static void inject(Menu menu, int sceneX, int sceneY)
	{
		menu.createMenuEntry(-1)
			.setOption(MENU_OPTION)
			.setTarget("")
			.setType(MenuAction.WALK) // id 23: param0/param1 = scene x/y
			.setIdentifier(-1)
			.setParam0(sceneX)
			.setParam1(sceneY)
			.setForceLeftClick(false);
	}

	/** Whether a MenuOptionClicked event is a click on our injected entry. */
	public static boolean matchesClick(MenuOptionClicked event)
	{
		return event.getMenuAction() == MenuAction.WALK
			&& event.getMenuOption().contains(MENU_MARKER);
	}

	/**
	 * Optional pinning: swap our entry with the LAST slot so it sits at the
	 * bottom of the rendered menu - stable position, away from the left-click
	 * default. (Swapping with index 0 instead would make every ordinary
	 * left-click walk to the fixed target - that must stay opt-in config and
	 * this method deliberately does not do it.)
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
