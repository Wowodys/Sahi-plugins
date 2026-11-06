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
package net.runelite.client.plugins.kuri.oneclick.world;

/**
 * Region/chunk arithmetic over world coordinates, so a configured tile can
 * be checked against its documented identifiers (config typos surface as a
 * visible mismatch instead of a silent wrong-location click).
 *
 * Formulas verified numerically against the reference location tile
 * (2800, 9568, 3) -&gt; region 11157, chunk 717996:
 *
 *   regionId = ((x &gt;&gt; 6) &lt;&lt; 8) | (y &gt;&gt; 6)       64x64-tile regions
 *              (the arithmetic behind WorldPoint.getRegionID())
 *              (2800&gt;&gt;6)&lt;&lt;8 | 9568&gt;&gt;6 = 43&lt;&lt;8 | 149 = 11157
 *
 *   chunkId  = ((x &gt;&gt; 3) &amp; 0x7FF) &lt;&lt; 11 | ((y &gt;&gt; 3) &amp; 0x7FF)
 *              8x8-tile chunks, chunkX/chunkY packed as 11-bit fields,
 *              plane carried separately (the reference location's chunk id
 *              717996 = 350 &lt;&lt; 11 | 1196 encodes chunks (350, 1196) and no
 *              plane bits - plane comes from the tile, here 3)
 */
public final class RsCoords
{
	public static final int CHUNK_BITS = 11;
	public static final int CHUNK_SIZE = 8;

	private RsCoords()
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
