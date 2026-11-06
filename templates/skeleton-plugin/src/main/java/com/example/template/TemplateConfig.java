package com.example.template;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Plugin configuration. The group name is the storage prefix in the RuneLite
 * profile; every @ConfigItem becomes a field in the plugin's config panel.
 */
@ConfigGroup("template")
public interface TemplateConfig extends Config
{
	@ConfigSection(
		name = "Behaviour",
		description = "What the plugin does",
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
		keyName = "pinEntryToBottom",
		name = "Pin menu entry",
		description = "Keep the Template entry pinned to the bottom of the context menu",
		position = 0,
		section = behaviourSection
	)
	default boolean pinEntryToBottom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Display the status overlay panel",
		position = 1,
		section = displaySection
	)
	default boolean showOverlay()
	{
		return true;
	}
}
