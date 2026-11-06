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
package net.runelite.client.plugins.kuri.backgroundclicker;

import java.awt.event.InputEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

/**
 * Plugin configuration.
 *
 * Defaults describe a safe zone inside the fixed-mode viewport
 * (765x503 canvas, viewport starts near the top-left): offset (128, 128),
 * size 256x192. Keep the zone inside the viewport - clicks landing on
 * widgets (chatbox, minimap) would click those widgets instead of the scene.
 */
@ConfigGroup(BackgroundClickerPlugin.CONFIG_GROUP)
public interface BackgroundClickerConfig extends Config
{
	@ConfigSection(
		name = "Activation",
		description = "When input forwarding runs",
		position = 0
	)
	String activationSection = "activation";

	@ConfigSection(
		name = "Safe zone",
		description = "The canvas box physical clicks are mapped into",
		position = 1
	)
	String zoneSection = "zone";

	@ConfigSection(
		name = "Filtering",
		description = "Which OS clicks are forwarded",
		position = 2
	)
	String filterSection = "filter";

	@ConfigSection(
		name = "Display",
		description = "What the plugin shows",
		position = 3
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "active",
		name = "Active",
		description = "Forward OS hardware clicks into the canvas while enabled. "
			+ "Also toggled by the hotkey below; the panel checkbox and the "
			+ "hotkey stay in sync",
		position = 0,
		section = activationSection
	)
	default boolean active()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hotkeyToggle",
		name = "Toggle hotkey",
		description = "Flips Active on key press. Hotkeys only fire while the "
			+ "client window has keyboard focus - click into the game window "
			+ "once, press it, then continue working elsewhere",
		position = 1,
		section = activationSection
	)
	default Keybind hotkeyToggle()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "zoneOffsetX",
		name = "Zone offset X",
		description = "Canvas X of the safe zone's top-left corner",
		position = 0,
		section = zoneSection
	)
	default int zoneOffsetX()
	{
		return 128;
	}

	@ConfigItem(
		keyName = "zoneOffsetY",
		name = "Zone offset Y",
		description = "Canvas Y of the safe zone's top-left corner",
		position = 1,
		section = zoneSection
	)
	default int zoneOffsetY()
	{
		return 128;
	}

	@ConfigItem(
		keyName = "zoneWidth",
		name = "Zone width",
		description = "Safe zone width in canvas pixels",
		position = 2,
		section = zoneSection
	)
	default int zoneWidth()
	{
		return 256;
	}

	@ConfigItem(
		keyName = "zoneHeight",
		name = "Zone height",
		description = "Safe zone height in canvas pixels",
		position = 3,
		section = zoneSection
	)
	default int zoneHeight()
	{
		return 192;
	}

	@ConfigItem(
		keyName = "monitorWidth",
		name = "Monitor width",
		description = "Primary monitor's NATIVE (physical) pixel width - the "
			+ "normalization domain physical clicks are mapped across. The OS "
			+ "hook reports physical pixels; if Windows display scaling is not "
			+ "100%, Java-reported sizes are logical units and wrong here. "
			+ "Match this to the panel's actual resolution (e.g. 2560)",
		position = 4,
		section = zoneSection
	)
	default int monitorWidth()
	{
		return 2560;
	}

	@ConfigItem(
		keyName = "monitorHeight",
		name = "Monitor height",
		description = "Primary monitor's NATIVE (physical) pixel height - the "
			+ "vertical normalization domain (e.g. 1440). See Monitor width "
			+ "for the scaling caveat",
		position = 5,
		section = zoneSection
	)
	default int monitorHeight()
	{
		return 1440;
	}

	@ConfigItem(
		keyName = "forwardRightClicks",
		name = "Forward right clicks",
		description = "Also map physical right clicks into the safe zone. The "
			+ "one-click walk flow needs this while unfocused: right click "
			+ "opens the context menu in the zone, left click selects the row. "
			+ "In mirror mode the focused application opens its own context "
			+ "menu too; combine with 'Swallow forwarded clicks' to avoid that",
		position = 0,
		section = filterSection
	)
	default boolean forwardRightClicks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ignoreClicksInClientWindow",
		name = "Ignore clicks in client window",
		description = "Physical clicks over the game client's own window are "
			+ "NOT mirrored into the canvas - the real window handles them, "
			+ "and without this guard every config-panel click would also "
			+ "walk. Disable only if you mirror from a setup where the check "
			+ "misreports bounds",
		position = 2,
		section = filterSection
	)
	default boolean ignoreClicksInClientWindow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "swallowForwardedClicks",
		name = "Swallow forwarded clicks",
		description = "Redirect mode: forwarded clicks are consumed at the OS "
			+ "hook and only happen inside the game. Off = mirror mode: the "
			+ "click still reaches whatever application is focused AND the "
			+ "game. If the forward hand-off is busy (a previous click is "
			+ "still being dispatched), the click is passed through untouched "
			+ "in both modes - a dropped click never disappears from the "
			+ "application you are actually using",
		position = 3,
		section = filterSection
	)
	default boolean swallowForwardedClicks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showZoneOverlay",
		name = "Show safe zone overlay",
		description = "Draw the safe zone rectangle and the last mapped click "
			+ "on the canvas - use it to calibrate offset/size",
		position = 0,
		section = displaySection
	)
	default boolean showZoneOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showStatusOverlay",
		name = "Show status overlay",
		description = "Display the status panel (active, hook state, counters)",
		position = 1,
		section = displaySection
	)
	default boolean showStatusOverlay()
	{
		return true;
	}

	/**
	 * AWT modifiers mask for a forwarded release-side event is always 0;
	 * this is only the press-side mask for the two forwarded buttons.
	 */
	static int pressModifiersFor(boolean leftButton)
	{
		return leftButton ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
	}
}
