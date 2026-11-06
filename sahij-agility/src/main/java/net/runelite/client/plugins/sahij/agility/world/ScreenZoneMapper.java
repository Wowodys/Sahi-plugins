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
package net.runelite.client.plugins.sahij.agility.world;

/**
 * The primary-monitor screen-zone mapping - the human-randomization core of
 * the click path, and the deliberate alternative to machine RNG.
 *
 * For a step whose tile array has N entries, the primary monitor's absolute
 * horizontal extent (config domain, default 2560 physical pixels) is divided
 * into N EQUAL horizontal brackets:
 *
 * <pre>
 *   zone k = [k * width / N, (k + 1) * width / N)   for k = 0 .. N-1
 * </pre>
 *
 * At click time the user's absolute mouse X on the primary monitor selects
 * the bracket, and the bracket's index IS the index into the step's tile
 * array (AgilityStep's canonical x-outer/y-inner ordering). Where the user's
 * hand happens to be on their main screen therefore determines which of the
 * step's valid tiles is used - behavioral correlation with the human's own
 * cursor, not blind randomness. A steady hand reproduces the same zone; a
 * drifting hand drifts through zones: the distribution is the user's own.
 *
 * Domain semantics (same contract as the background clicker's safe-zone
 * mapper, kept consistent on purpose):
 *
 *   - The domain is the PRIMARY monitor's PHYSICAL pixel width, an explicit
 *     config item. Java's GraphicsEnvironment reports logical units when
 *     Windows display scaling is not 100%, so an auto-detected width would
 *     silently mis-bracket; the config value must be the panel's native
 *     resolution. java.awt.MouseInfo pointer coordinates are read in the
 *     same space the config declares.
 *   - Out-of-domain X (a cursor parked on a monitor LEFT of the primary
 *     reports negative virtual-screen X; one beyond the right edge reports
 *     larger-than-width) CLAMPS to the nearest bracket's edge tile - it does
 *     not wrap. Deterministic and single-valued; a multi-monitor hand parked
 *     off-primary pins to an edge zone instead of folding back mid-screen.
 *
 * Pure, static, RuneLite-free: unit-testable without a client.
 */
public final class ScreenZoneMapper
{
	private ScreenZoneMapper()
	{
	}

	/**
	 * Bracket index for an absolute primary-monitor mouse X.
	 *
	 * @param absoluteMouseX absolute (virtual-screen) cursor X at click time;
	 *                       negative or past the right edge clamps to the
	 *                       nearest bracket
	 * @param monitorWidth   primary monitor's PHYSICAL pixel width, the
	 *                       bracket domain (&gt;= 1; config, default 2560)
	 * @param zoneCount      N - the step's tile count (&gt;= 1)
	 * @return the bracket index, 0 .. zoneCount-1, directly usable as the
	 *         tile-array index
	 */
	public static int zoneIndex(int absoluteMouseX, int monitorWidth, int zoneCount)
	{
		if (zoneCount <= 1)
		{
			return 0;
		}
		int width = Math.max(1, monitorWidth);
		int clamped = Math.max(0, Math.min(width - 1, absoluteMouseX));

		// Floor of the scaled coordinate. The clamp bounds the product below
		// width * zoneCount, so the index can never reach zoneCount; the
		// outer Math.min is belt-and-braces, not a reachable branch.
		int index = (int) ((long) clamped * zoneCount / width);
		return Math.min(zoneCount - 1, index);
	}

	/**
	 * The FIRST pixel X of bracket {@code index} on the primary monitor - the
	 * calibration/telemetry twin of {@link #zoneIndex} (the overlay uses it
	 * to show the user which screen bracket maps to which tile).
	 *
	 * Ceiling division on purpose: brackets are the half-open ranges
	 * [k*W/N, (k+1)*W/N), so the first INTEGER pixel inside bracket k is
	 * ceil(k*W/N) - e.g. W=2560, N=12: bracket 1 spans [213.33, 426.67) and
	 * its first pixel is 214, matching zoneIndex(214) == 1 (floor-based
	 * start would claim 213, which zoneIndex still assigns to bracket 0).
	 */
	public static int zoneStartX(int monitorWidth, int zoneCount, int index)
	{
		int width = Math.max(1, monitorWidth);
		int count = Math.max(1, zoneCount);
		int k = Math.max(0, Math.min(count - 1, index));
		return (int) (((long) k * width + count - 1) / count);
	}

	/**
	 * Nominal bracket width in pixels (the last bracket may be 1px wider in
	 * exact terms when width is not divisible by zoneCount; the floor
	 * arithmetic in {@link #zoneIndex} absorbs that internally).
	 */
	public static int zoneWidth(int monitorWidth, int zoneCount)
	{
		return Math.max(1, monitorWidth) / Math.max(1, zoneCount);
	}
}
