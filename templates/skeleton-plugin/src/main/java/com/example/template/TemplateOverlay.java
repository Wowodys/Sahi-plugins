package com.example.template;

import com.google.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Status overlay. OverlayPanel gives you a titled panel rendered by the
 * OverlayManager; fill panelComponent children in render() and return
 * super.render(g). `super(plugin)` wires the config "Configure" option into
 * the panel header.
 */
public class TemplateOverlay extends OverlayPanel
{
	private final TemplatePlugin plugin;
	private final TemplateConfig config;

	@Inject
	private TemplateOverlay(TemplatePlugin plugin, TemplateConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null; // returning null renders nothing
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Clicks:")
			.right(String.valueOf(plugin.getClicks()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last action:")
			.right(plugin.getLastAction())
			.build());

		return super.render(graphics);
	}
}
