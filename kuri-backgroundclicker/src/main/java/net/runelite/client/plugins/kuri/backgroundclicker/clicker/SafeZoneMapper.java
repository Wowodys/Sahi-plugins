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
package net.runelite.client.plugins.kuri.backgroundclicker.clicker;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * The relative physical-to-canvas mapping.
 *
 * Capture chain: the OS hook reads {@code MSLLHOOKSTRUCT.pt} (WinMouseHook)
 * - raw virtual-screen pixels, origin at the primary monitor's top-left, so
 * X and Y can be arbitrarily large or negative on multi-monitor layouts.
 * This class turns those into a canvas point inside the safe zone:
 *
 * <pre>
 * normalizedX = clamp(physicalX, 0, monitorWidth  - 1) / monitorWidth    // 0.0 .. 1.0
 * normalizedY = clamp(physicalY, 0, monitorHeight - 1) / monitorHeight   // 0.0 .. 1.0
 * canvasX     = zoneOffsetX + (int) (normalizedX * zoneWidth)
 * canvasY     = zoneOffsetY + (int) (normalizedY * zoneHeight)
 * </pre>
 *
 * LINEAR, not modular - and why that matters. This mapping REPLACED a
 * modulo fold ({@code zoneOffset + |physical mod zoneSize|}), which had an
 * inherent asymmetry: physical 0 is a modulo fold's origin, so the LEFT edge
 * of the monitor always landed on the zone's left edge, but the monitor's
 * RIGHT edge landed at {@code (monitorWidth-1) mod zoneWidth} - an arbitrary
 * fold position that only coincided with the zone's right edge when the zone
 * width happened to divide the monitor width evenly (2560 % 256 == 0). Any
 * other zone size undershot the right edge to a mid-zone point: the reported
 * "left edge correct, right edge compresses toward center" bug. Normalizing
 * against the monitor's physical extent removes the sawtooth entirely:
 *
 *   - physical 0        -> zone left edge
 *   - physical width-1  -> zone right edge (2559/2560 * zoneWidth floors
 *                          onto the zone's last pixel - no +1 correction
 *                          needed, and normalized stays &lt; 1.0)
 *   - everything between moves monotonically, edge to edge
 *
 * Design properties, deliberately kept from the previous mapping:
 *
 *   - NO random source. The plugin-hub style guides and this repository's
 *     own threat model (docs/plugin-development/01-design-philosophy.md,
 *     "human-shaped input") treat synthetic randomness as a signature.
 *     Micro-jitter in the mapped click is the USER's own hand jitter,
 *     translated 1:1 - the mapping is monotonic and locally linear, so a
 *     steady hand clicks the same spot twice and a drifting hand drifts in
 *     the canvas. (A 2560-physical-px axis over a 256-px zone quantizes to
 *     ~10 physical px per canvas px; the jitter that survives is still the
 *     hand's own.)
 *   - Out-of-domain clicks CLAMP to the nearest edge, they do not wrap.
 *     The mapping domain is explicitly the primary monitor's physical
 *     resolution (monitorWidth x monitorHeight, config: 2560x1440
 *     defaults), so a cursor parked on a monitor left of or below the
 *     primary pins to the zone edge instead of folding back into the
 *     middle. Deterministic and single-valued, like the linear map itself.
 *   - Defensive clamps kept: config is not guaranteed in sync with the live
 *     canvas (stretched mode, resizing), so a zone partially outside the
 *     canvas still produces on-canvas points.
 *
 * DPI caveat (why the domain is config, not auto-detected): the hook reports
 * PHYSICAL pixels, while Java's own GraphicsEnvironment reports bounds in
 * logical units when Windows display scaling is active (a 2560x1440 panel at
 * 150% shows up as 1707x960). Mixing the two is exactly the silent scaling
 * error this class exists to avoid, so the monitor size is an explicit
 * config item and must be the panel's NATIVE resolution.
 *
 * Pure, static, RuneLite-free: unit-testable without a client.
 */
public final class SafeZoneMapper
{
	/**
	 * A press-release pair older than this forwards using the release's own
	 * position (the press is treated as lost - e.g. forwarding was toggled
	 * on between press and release).
	 */
	public static final int PAIR_STALE_MS = 1500;

	private SafeZoneMapper()
	{
	}

	/**
	 * Normalizes a physical cursor position against the primary monitor's
	 * extent and scales it linearly across the safe zone.
	 *
	 * @param physicalX virtual-screen cursor X (any sign; clamped to the
	 *                  monitor domain)
	 * @param physicalY virtual-screen cursor Y (any sign; clamped to the
	 *                  monitor domain)
	 * @param monitorWidth primary monitor PHYSICAL pixel width (&gt;= 1;
	 *                     config default 2560)
	 * @param monitorHeight primary monitor PHYSICAL pixel height (&gt;= 1;
	 *                      config default 1440)
	 * @param zoneOffsetX canvas X of the zone's top-left corner
	 * @param zoneOffsetY canvas Y of the zone's top-left corner
	 * @param zoneWidth zone width in canvas pixels (&gt;= 1)
	 * @param zoneHeight zone height in canvas pixels (&gt;= 1)
	 * @param canvasWidth live canvas width, for the defensive clamp
	 * @param canvasHeight live canvas height, for the defensive clamp
	 * @return the canvas point the click must be dispatched at
	 */
	public static Point map(int physicalX, int physicalY, int monitorWidth, int monitorHeight,
		int zoneOffsetX, int zoneOffsetY, int zoneWidth, int zoneHeight,
		int canvasWidth, int canvasHeight)
	{
		int monWidth = Math.max(1, monitorWidth);
		int monHeight = Math.max(1, monitorHeight);
		int width = Math.max(1, zoneWidth);
		int height = Math.max(1, zoneHeight);

		// Normalize against the explicit primary-monitor domain. The clamp
		// bounds the ratio to [0, (monWidth-1)/monWidth] - strictly inside
		// [0, 1) - so the scaled product floors onto a pixel within the zone
		// and out-of-domain (multi-monitor) clicks pin to the nearest edge.
		double normalizedX = (double) clamp(physicalX, 0, monWidth - 1) / monWidth;
		double normalizedY = (double) clamp(physicalY, 0, monHeight - 1) / monHeight;

		int x = zoneOffsetX + (int) (normalizedX * width);
		int y = zoneOffsetY + (int) (normalizedY * height);

		// The ratio is < 1.0, so x/y stay inside the zone; the only way to
		// exceed the canvas is a zone placed partially outside it - clamp
		// keeps the synthetic event on the canvas surface in that case.
		if (canvasWidth > 0 && x >= canvasWidth)
		{
			x = canvasWidth - 1;
		}
		if (canvasHeight > 0 && y >= canvasHeight)
		{
			y = canvasHeight - 1;
		}
		return new Point(x, y);
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(value, max));
	}

	/**
	 * The safe zone as a canvas-space rectangle, clamped to the canvas - the
	 * same clamp {@link #map} applies, so the overlay always shows exactly
	 * the region clicks can land in.
	 */
	public static Rectangle zoneBounds(int zoneOffsetX, int zoneOffsetY, int zoneWidth, int zoneHeight,
		int canvasWidth, int canvasHeight)
	{
		int width = Math.max(1, zoneWidth);
		int height = Math.max(1, zoneHeight);
		int x = Math.max(0, zoneOffsetX);
		int y = Math.max(0, zoneOffsetY);
		if (canvasWidth > 0 && x + width > canvasWidth)
		{
			width = Math.max(1, canvasWidth - x);
		}
		if (canvasHeight > 0 && y + height > canvasHeight)
		{
			height = Math.max(1, canvasHeight - y);
		}
		return new Rectangle(x, y, width, height);
	}
}
