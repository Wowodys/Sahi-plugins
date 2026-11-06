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
package net.runelite.client.plugins.sahij.agility.overlay;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.plugins.sahij.agility.SahijAgilityConfig;
import net.runelite.client.plugins.sahij.agility.SahijAgilityPlugin;
import net.runelite.client.plugins.sahij.agility.engine.AgilitySequenceEngine;
import net.runelite.client.plugins.sahij.agility.engine.AgilityStep;
import net.runelite.client.plugins.sahij.agility.engine.StepTile;
import net.runelite.client.plugins.sahij.agility.world.ScreenZoneMapper;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Status overlay (fail visible): the panel always shows what the plugin
 * thinks is happening - the current step of the sequence, the screen-zone
 * mapping for it (zones, and which tile the last click's cursor bracket
 * selected), the counters, and the last outcome with its reason.
 */
public class AgilityStatusOverlay extends OverlayPanel
{
	private final SahijAgilityPlugin plugin;
	private final SahijAgilityConfig config;

	@Inject
	private AgilityStatusOverlay(SahijAgilityPlugin plugin, SahijAgilityConfig config)
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
			return null;
		}

		AgilitySequenceEngine engine = plugin.getEngine();
		AgilityStep step = engine.getCurrentStep();

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Sahij Agility")
			.right("step " + step.getNumber() + "/7 " + (engine.isSynced() ? "" : "(unsynced)"))
			.build());

		// The step's action + the zone geometry that will pick its tile.
		int tileCount = step.getTiles().size();
		String zoneText;
		if (tileCount == 1)
		{
			zoneText = "single tile";
		}
		else
		{
			zoneText = tileCount + " zones x "
				+ ScreenZoneMapper.zoneWidth(config.monitorWidth(), tileCount) + "px";
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(step.getAction() + (step.getTarget().isEmpty() ? "" : " " + step.getTarget()))
			.right(zoneText)
			.build());

		// The last click's cursor bracket -> tile, the mapping in action.
		if (engine.getLastTileIndex() >= 0 && tileCount > 1)
		{
			StepTile chosen = step.getTiles().get(
				Math.min(engine.getLastTileIndex(), tileCount - 1));
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Last zone -> tile")
				.right("mouseX=" + engine.getLastMouseX() + " -> #" + (engine.getLastTileIndex() + 1)
					+ " " + chosen)
				.build());
		}

		String availability = plugin.getInjectedReason().isEmpty()
			? "entry injected"
			: plugin.getInjectedReason();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Entry")
			.right(availability)
			.build());

		String outcome = engine.getLastOutcome();
		Color outcomeColor = null;
		if (outcome.startsWith("aborted") || outcome.startsWith("refused")
			|| outcome.startsWith("reset") || outcome.startsWith("stale"))
		{
			outcomeColor = Color.ORANGE;
		}
		else if (outcome.startsWith("confirmed") || outcome.startsWith("advanced")
			|| outcome.startsWith("position"))
		{
			// "position resync" is recovery information (green: the machine
			// is placed and un-stuck), not an anomaly.
			outcomeColor = Color.GREEN;
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last outcome")
			.right(outcome)
			.rightColor(outcomeColor)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Laps / plane resets")
			.right(engine.getLaps() + " / " + engine.getPlaneResets())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Clicks ok / refused / dead")
			.right(engine.getStepsConfirmed() + " / " + engine.getClicksRefused()
				+ " / " + engine.getClicksAborted())
			.build());

		return super.render(graphics);
	}
}
