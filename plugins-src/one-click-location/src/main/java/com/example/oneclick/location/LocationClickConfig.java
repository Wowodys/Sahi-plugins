package com.example.oneclick.location;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Plugin configuration. Defaults are the reference location:
 * tile (2800, 9568) plane 3, chunk 717996, region 11157.
 */
@ConfigGroup("oneclicklocation")
public interface LocationClickConfig extends Config
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
		return 2800;
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
		return 9568;
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
		return 3;
	}

	@ConfigItem(
		keyName = "regionGate",
		name = "Region gate",
		description = "Only offer the menu entry while the player is inside the "
			+ "target's region (11157) - like the case study's varbit gate, one "
			+ "check keeps the whole subsystem inert elsewhere",
		position = 0,
		section = behaviourSection
	)
	default boolean regionGate()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pinEntryToBottom",
		name = "Pin menu entry (left-click hijack!)",
		description = "Pin the entry to the menu's top slot (making it the default "
			+ "left-click). OFF by default and not recommended: this is a real WALK "
			+ "entry, so pinning would turn every ordinary left-click in the world "
			+ "into a walk to the fixed target",
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
