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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Plugin configuration. Defaults are the reference location:
 * tile (2800, 9568) plane 3, chunk 717996, region 11157.
 */
@ConfigGroup("kurioneclick")
public interface KuriOneClickConfig extends Config
{
	@ConfigSection(
		name = "Target",
		description = "The 1:1 click location",
		position = 0
	)
	String targetSection = "target";

	@ConfigSection(
		name = "Behaviour",
		description = "When the entry is offered",
		position = 1
	)
	String behaviourSection = "behaviour";

	@ConfigSection(
		name = "Display",
		description = "What the plugin shows",
		position = 2
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "targetX",
		name = "Target tile X",
		description = "World X of the click target",
		position = 0,
		section = targetSection
	)
	default int targetX()
	{
		return KuriOneClickPlugin.REFERENCE_X;
	}

	@ConfigItem(
		keyName = "targetY",
		name = "Target tile Y",
		description = "World Y of the click target",
		position = 1,
		section = targetSection
	)
	default int targetY()
	{
		return KuriOneClickPlugin.REFERENCE_Y;
	}

	@ConfigItem(
		keyName = "targetPlane",
		name = "Target plane",
		description = "Plane (height level) of the click target",
		position = 2,
		section = targetSection
	)
	default int targetPlane()
	{
		return KuriOneClickPlugin.REFERENCE_PLANE;
	}

	@ConfigItem(
		keyName = "regionGate",
		name = "Region gate",
		description = "Only offer the menu entry while the player is inside the "
			+ "target's region (11157) - one check keeps the whole subsystem "
			+ "inert elsewhere",
		position = 0,
		section = behaviourSection
	)
	default boolean regionGate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pinEntryToBottom",
		name = "Pin menu entry to bottom",
		description = "Swap the entry into the menu's bottom slot every build so "
			+ "it sits at a stable position, away from the left-click default. "
			+ "This is NOT a left-click hijack: the default action stays whatever "
			+ "the game shows for what you hover",
		position = 1,
		section = behaviourSection
	)
	default boolean pinEntryToBottom()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Display the status overlay panel",
		position = 0,
		section = displaySection
	)
	default boolean showOverlay()
	{
		return true;
	}
}
