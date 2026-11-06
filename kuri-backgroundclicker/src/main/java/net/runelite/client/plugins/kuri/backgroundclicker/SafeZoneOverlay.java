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
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.kuri.backgroundclicker.clicker.SafeZoneMapper;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Calibration overlay (fail visible): draws the safe zone box plus a cross
 * at the last mapped click, so the zone config can be tuned by eye.
 *
 * COORDINATE SPACES - read before touching the draw calls. The zone config
 * (and every point the injector dispatches) lives in CANVAS space: relative
 * to the canvas origin, bounded by canvas width/height. But render() does
 * NOT always receive canvas space:
 *
 *   - Plain (unstretched) rendering: Hooks#draw hands overlays the game's
 *     main buffer, whose size equals the canvas - coordinates are canvas
 *     space as-is.
 *   - Stretched Mode (non-GPU): overlays render into the main buffer at the
 *     client's REAL dimensions (e.g. 765x503) and the whole buffer is then
 *     stretched onto the canvas (Hooks#draw -> stretchedImage). A rect drawn
 *     at buffer coords m appears on screen at m * stretchFactor, while a
 *     click dispatched at canvas coords lands at exactly those canvas coords
 *     (TranslateMouseListener divides the event down, the stretch draws it
 *     back up). Without compensation the marker visually sits at
 *     m * factor - up and left of where the click lands, drifting linearly
 *     with position. This overlay therefore SCALES ITS DRAWING by
 *     real/stretched, so what you see on screen is the canvas-space rect
 *     the events land on.
 *   - GPU rendering: the GPU plugin draws overlays at canvas dimensions
 *     directly; no compensation.
 *
 * DYNAMIC with no preferred location locates the overlay at its previous
 * bounds origin (0,0 for an overlay that never moved), so draw coordinates
 * are buffer-space - the same convention screen markers draw with. (DETACHED
 * would also do this but is deprecated; DYNAMIC is the supported spelling.)
 * ABOVE_WIDGETS keeps the box unclipped by the fixed-mode viewport: a zone
 * configured outside the viewport is visible as a mistake instead of
 * silently absent (the viewport clip is exactly the warning - clicks there
 * hit widgets, not the scene).
 */
public class SafeZoneOverlay extends Overlay
{
	private final BackgroundClickerPlugin plugin;
	private final BackgroundClickerConfig config;
	private final Client client;

	@Inject
	private SafeZoneOverlay(BackgroundClickerPlugin plugin, BackgroundClickerConfig config,
		Client client)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/**
	 * Canvas coordinate -> the buffer coordinate that renders back onto that
	 * same canvas position. Uses the EXACT expression TranslateMouseListener
	 * applies to dispatched events - {@code (int) (x / (stretched / real))} -
	 * inverted for drawing: a point drawn at the returned coordinate lands
	 * pixel-identically on the canvas coordinate it was derived from (same
	 * integer truncation on both paths; no rounding drift).
	 */
	private static int toBufferSpace(int canvasCoord, int stretched, int real)
	{
		if (stretched <= 0 || real <= 0 || real >= stretched)
		{
			return canvasCoord;
		}
		return (int) (canvasCoord / (stretched / (double) real));
	}

	private boolean needsStretchCompensation()
	{
		// GPU overlays draw at canvas dimensions directly (no main-buffer
		// stretch downstream), so compensating there would MISDRAW.
		return client.isStretchedEnabled() && !client.isGpu();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showZoneOverlay())
		{
			return null;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return null;
		}

		// Focused use = plain play; the zone is a background instrument.
		// isActive() on the canvas's own window is the focus truth.
		Window window = SwingUtilities.getWindowAncestor(canvas);
		boolean focused = window != null && window.isActive();

		// Canvas-space rect: exactly the region dispatched clicks can land
		// in (SafeZoneMapper clamps identically).
		Rectangle zone = SafeZoneMapper.zoneBounds(
			config.zoneOffsetX(), config.zoneOffsetY(),
			config.zoneWidth(), config.zoneHeight(),
			canvas.getWidth(), canvas.getHeight());

		// Re-express both corners in the buffer space render() draws into,
		// compensating for stretched-mode rendering when active.
		boolean compensate = needsStretchCompensation();
		int stretchedW = compensate ? client.getStretchedDimensions().width : 0;
		int stretchedH = compensate ? client.getStretchedDimensions().height : 0;
		int realW = compensate ? client.getRealDimensions().width : 0;
		int realH = compensate ? client.getRealDimensions().height : 0;
		int drawX = compensate ? toBufferSpace(zone.x, stretchedW, realW) : zone.x;
		int drawY = compensate ? toBufferSpace(zone.y, stretchedH, realH) : zone.y;
		int drawX2 = compensate ? toBufferSpace(zone.x + zone.width, stretchedW, realW) : zone.x + zone.width;
		int drawY2 = compensate ? toBufferSpace(zone.y + zone.height, stretchedH, realH) : zone.y + zone.height;
		Rectangle drawZone = new Rectangle(drawX, drawY,
			Math.max(1, drawX2 - drawX), Math.max(1, drawY2 - drawY));

		boolean active = plugin.isForwarderActive()
			&& client.getGameState() == GameState.LOGGED_IN
			&& !focused;

		graphics.setColor(active ? new Color(0, 170, 0, 36) : new Color(120, 120, 120, 28));
		graphics.fill(drawZone);
		graphics.setColor(active ? new Color(0, 200, 0, 200) : new Color(160, 160, 160, 160));
		graphics.draw(drawZone);

		graphics.setColor(new Color(255, 255, 255, 150));
		graphics.drawString("background clicker zone" + (active ? " - armed" : ""),
			drawZone.x + 4, drawZone.y + 14);

		java.awt.Point last = plugin.getLastMapped();
		if (last != null && zone.contains(last))
		{
			int cx = compensate ? toBufferSpace(last.x, stretchedW, realW) : last.x;
			int cy = compensate ? toBufferSpace(last.y, stretchedH, realH) : last.y;
			graphics.setColor(new Color(255, 220, 0, 220));
			graphics.drawLine(cx - 5, cy, cx + 5, cy);
			graphics.drawLine(cx, cy - 5, cx, cy + 5);
		}

		return null;
	}
}
