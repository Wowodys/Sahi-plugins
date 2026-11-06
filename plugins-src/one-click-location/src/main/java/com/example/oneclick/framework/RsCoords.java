package com.example.oneclick.framework;

/**
 * Region/chunk arithmetic over world coordinates, so a configured tile can be
 * checked against its documented identifiers (config typos surface as a
 * visible mismatch instead of a silent wrong-location click).
 *
 * Formulas verified numerically 2026-11-03 against the reference location
 * tile (2800, 9568, 3) -> region 11157, chunk 717996:
 *
 *   regionId = ((x >> 6) << 8) | (y >> 6)          64x64-tile regions
 *              (the arithmetic behind WorldPoint.getRegionID())
 *              (2800>>6)<<8 | 9568>>6 = 43<<8 | 149 = 11157
 *
 *   chunkId  = ((x >> 3) & 0x7FF) << 11 | ((y >> 3) & 0x7FF)
 *              8x8-tile chunks, chunkX/chunkY packed as 11-bit fields,
 *              plane carried separately (the reference location's chunk id
 *              717996 = 350 << 11 | 1196 encodes chunks (350, 1196) and no
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
