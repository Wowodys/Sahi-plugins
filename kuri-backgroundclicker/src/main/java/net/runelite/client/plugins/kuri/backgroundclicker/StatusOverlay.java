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

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Status overlay (fail visible): what the plugin thinks is happening - the
 * effective active state, the OS hook's fate, the last physical-to-canvas
 * mapping it produced, and the forward counters. The panel deliberately
 * shows state the OS hook cannot assert for itself: a hook install failure
 * or a stuck dispatch queue is visible here, not silent.
 */
public class StatusOverlay extends OverlayPanel
{
	private final BackgroundClickerPlugin plugin;
	private final BackgroundClickerConfig config;

	@Inject
	private StatusOverlay(BackgroundClickerPlugin plugin, BackgroundClickerConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.BOTTOM_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showStatusOverlay())
		{
			return null;
		}

		boolean active = plugin.isForwarderActive();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Background clicker")
			.right(active ? "ACTIVE" : "inactive")
			.rightColor(active ? Color.GREEN : null)
			.build());

		String hookState;
		Color hookColor = null;
		if (plugin.getHookError() != null)
		{
			hookState = "FAILED";
			hookColor = Color.RED;
		}
		else
		{
			hookState = plugin.isHookInstalled() ? "installed" : "starting...";
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("OS hook (WH_MOUSE_LL)")
			.right(hookState)
			.rightColor(hookColor)
			.build());

		Point physical = plugin.getLastPhysical();
		Point mapped = plugin.getLastMapped();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last physical")
			.right(physical == null ? "-" : physical.x + ", " + physical.y)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Last mapped")
			.right(mapped == null ? "-" : mapped.x + ", " + mapped.y)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Forwarded / dropped / skipped")
			.right(plugin.getForwardedCount() + " / " + plugin.getDroppedCount()
				+ " / " + plugin.getSkippedCount())
			.build());

		return super.render(graphics);
	}
}
