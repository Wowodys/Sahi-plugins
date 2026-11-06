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
 * Region/chunk arithmetic over world coordinates, so the course's documented
 * chunk ids can be cross-checked against the step tiles (a definition typo
 * surfaces as a visible mismatch instead of a silent wrong-location click).
 *
 * Same formulas as the sibling plugins (kept per-module on purpose - the
 * modules share nothing, they meet only at the client's input pipeline),
 * verified numerically against this course's documented values:
 *
 *   regionId = ((x &gt;&gt; 6) &lt;&lt; 8) | (y &gt;&gt; 6)       64x64-tile regions
 *              (the arithmetic behind WorldPoint.getRegionID())
 *              (2729&gt;&gt;6)&lt;&lt;8 | 3489&gt;&gt;6 = 42&lt;&lt;8 | 54 = 10806
 *
 *   chunkId  = ((x &gt;&gt; 3) &amp; 0x7FF) &lt;&lt; 11 | ((y &gt;&gt; 3) &amp; 0x7FF)
 *              8x8-tile chunks, chunkX/chunkY packed as 11-bit fields
 *              (plane carried separately by the tile itself)
 *              step 1: (2729&gt;&gt;3)&lt;&lt;11 | 3489&gt;&gt;3 = 341&lt;&lt;11 | 436 = 698804
 *              step 3: (2720&gt;&gt;3)&lt;&lt;11 | 3491&gt;&gt;3 = 340&lt;&lt;11 | 436 = 696756
 *              step 4: (2710&gt;&gt;3)&lt;&lt;11 | 3489&gt;&gt;3 = 338&lt;&lt;11 | 436 = 692660
 *              step 5: (2709&gt;&gt;3)&lt;&lt;11 | 3476&gt;&gt;3 = 338&lt;&lt;11 | 434 = 692658
 *              step 6: (2701&gt;&gt;3)&lt;&lt;11 | 3469&gt;&gt;3 = 337&lt;&lt;11 | 433 = 690609
 *              step 7: (2703&gt;&gt;3)&lt;&lt;11 | 3464&gt;&gt;3 = 337&lt;&lt;11 | 433 = 690609
 *
 * Pure, static, RuneLite-free.
 */
public final class RsCoordMath
{
	public static final int CHUNK_BITS = 11;

	private RsCoordMath()
	{
	}

	public static int regionId(int x, int y)
	{
		return (x >> 6) << 8 | (y >> 6);
	}

	public static int chunkId(int x, int y)
	{
		return (x >> 3 & 0x7FF) << CHUNK_BITS | (y >> 3 & 0x7FF);
	}

	public static int chunkX(int x)
	{
		return x >> 3;
	}

	public static int chunkY(int y)
	{
		return y >> 3;
	}
}
