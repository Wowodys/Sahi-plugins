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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Plugin configuration. The course itself (tiles, object ids, chunk/region
 * ids, the 7-step order) is NOT configurable - it is spec-locked in
 * {@link net.runelite.client.plugins.sahij.agility.engine.AgilityCourse}.
 * What IS configurable is the operational envelope: the screen-zone domain,
 * where the entry is offered, and what is displayed.
 */
@ConfigGroup("sahijagility")
public interface SahijAgilityConfig extends Config
{
	@ConfigSection(
		name = "Behaviour",
		description = "When the entry is offered and how clicks resolve",
		position = 0
	)
	String behaviourSection = "behaviour";

	@ConfigSection(
		name = "Display",
		description = "What the plugin shows",
		position = 1
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "monitorWidth",
		name = "Monitor width",
		description = "Primary monitor's NATIVE (physical) pixel width - the "
			+ "domain the click-time cursor X is bracketed across (N tiles = N "
			+ "equal horizontal zones). java.awt.MouseInfo reports in the same "
			+ "space; if Windows display scaling is not 100%, verify the right "
			+ "edge of your primary monitor selects the last zone and adjust. "
			+ "Matches the background clicker's monitor domain (2560)",
		position = 0,
		section = behaviourSection
	)
	default int monitorWidth()
	{
		return 2560;
	}

	@ConfigItem(
		keyName = "regionGate",
		name = "Region gate",
		description = "Only offer the menu entry while the player is inside "
			+ "the course region (10806) - one check keeps the whole "
			+ "subsystem inert elsewhere",
		position = 1,
		section = behaviourSection
	)
	default boolean regionGate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pinEntryToBottom",
		name = "Pin menu entry to bottom",
		description = "Swap the entry into the menu's bottom slot every build "
			+ "so it sits at a stable screen position - that stability is what "
			+ "makes it reliably clickable through the background clicker's "
			+ "safe-zone mapping. This is NOT a left-click hijack: the default "
			+ "action stays whatever the game shows for what you hover",
		position = 2,
		section = behaviourSection
	)
	default boolean pinEntryToBottom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "requireStepTile",
		name = "Require standing on step tiles",
		description = "Strict mode: refuse a step's click unless the player "
			+ "stands on one of that step's own tiles (the spec's tile sets "
			+ "are the documented standing areas). Off = plane agreement is "
			+ "enough and off-tile standings are only logged",
		position = 3,
		section = behaviourSection
	)
	default boolean requireStepTile()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Display the status overlay panel (current step, zone "
			+ "mapping, lap/confirm counters, last outcome - fail visible)",
		position = 0,
		section = displaySection
	)
	default boolean showOverlay()
	{
		return true;
	}
}
