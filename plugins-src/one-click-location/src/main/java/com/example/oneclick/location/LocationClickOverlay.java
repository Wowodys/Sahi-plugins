package com.example.oneclick.location;

import com.google.inject.Inject;
import com.example.oneclick.framework.ClickState;
import com.example.oneclick.framework.RsCoords;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Status overlay (rule 6 - fail visible): the panel always shows what the
 * plugin thinks is happening - state, last outcome with reason, target
 * identity, and why the entry is absent when a gate withholds it.
 */
public class LocationClickOverlay extends OverlayPanel
{
	private final LocationClickPlugin plugin;
	private final LocationClickConfig config;
	private final Client client;

	@Inject
	private LocationClickOverlay(LocationClickPlugin plugin, LocationClickConfig config, Client client)
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
		boolean identityOk = region == LocationClickPlugin.REFERENCE_REGION_ID
			&& chunk == LocationClickPlugin.REFERENCE_CHUNK_ID;

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Target")
			.right("(" + target.getX() + ", " + target.getY() + ") z" + target.getPlane())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Region / chunk")
			.right(region + " / " + chunk + (identityOk ? " \u2713" : " MISMATCH"))
			.rightColor(identityOk ? null : java.awt.Color.RED)
			.build());

		// Distance + in-scene indicator (the entry only exists when in scene).
		String distance = "-";
		if (client.getGameState() == net.runelite.api.GameState.LOGGED_IN
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
		java.awt.Color outcomeColor = null;
		if (outcome.startsWith("aborted"))
		{
			outcomeColor = java.awt.Color.ORANGE;
		}
		else if (outcome.startsWith("confirmed"))
		{
			outcomeColor = java.awt.Color.GREEN;
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
