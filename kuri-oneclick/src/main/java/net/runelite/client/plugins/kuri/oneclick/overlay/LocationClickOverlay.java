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
package net.runelite.client.plugins.kuri.oneclick.overlay;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.kuri.oneclick.KuriOneClickConfig;
import net.runelite.client.plugins.kuri.oneclick.KuriOneClickPlugin;
import net.runelite.client.plugins.kuri.oneclick.engine.ClickState;
import net.runelite.client.plugins.kuri.oneclick.world.RsCoords;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Status overlay (fail visible): the panel always shows what the plugin
 * thinks is happening - state, last outcome with reason, target identity,
 * and why the entry is absent when a gate withholds it.
 */
public class LocationClickOverlay extends OverlayPanel
{
	private final KuriOneClickPlugin plugin;
	private final KuriOneClickConfig config;
	private final Client client;

	@Inject
	private LocationClickOverlay(KuriOneClickPlugin plugin, KuriOneClickConfig config, Client client)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.BOTTOM_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		WorldPoint target = plugin.targetPoint();
		int region = RsCoords.regionId(target.getX(), target.getY());
		int chunk = RsCoords.chunkId(target.getX(), target.getY());
		boolean identityOk = region == KuriOneClickPlugin.REFERENCE_REGION_ID
			&& chunk == KuriOneClickPlugin.REFERENCE_CHUNK_ID;

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Target")
			.right("(" + target.getX() + ", " + target.getY() + ") z" + target.getPlane())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Region / chunk")
			.right(region + " / " + chunk + (identityOk ? " \u2713" : " MISMATCH"))
			.rightColor(identityOk ? null : Color.RED)
			.build());

		// Distance + in-scene indicator (the entry only exists when in scene).
		String distance = "-";
		if (client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null)
		{
			Player me = client.getLocalPlayer();
			distance = String.valueOf(target.distanceTo(me.getWorldLocation()));
		}
		String availability = plugin.getInjectedReason().isEmpty()
			? "entry injected"
			: plugin.getInjectedReason();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Distance / entry")
			.right(distance + " / " + availability)
			.build());

		ClickState state = plugin.getEngine().getState();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("State")
			.right(state == ClickState.IDLE ? "idle (waiting for your click)" : "armed")
			.build());

		String outcome = plugin.getEngine().getLastOutcome();
		Color outcomeColor = null;
		if (outcome.startsWith("aborted"))
		{
			outcomeColor = Color.ORANGE;
		}
		else if (outcome.startsWith("confirmed"))
		{
			outcomeColor = Color.GREEN;
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last cycle")
			.right(outcome)
			.rightColor(outcomeColor)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Confirmed / aborted")
			.right(plugin.getEngine().getCyclesConfirmed() + " / "
				+ plugin.getEngine().getCyclesAborted())
			.build());

		return super.render(graphics);
	}
}
