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
package net.runelite.client.plugins.sahij.agility.engine;

import java.util.Objects;

/**
 * One tile of a step's ordered tile array - immutable x/y/plane, nothing
 * else. The engine package is deliberately free of RuneLite imports (see
 * {@link AgilitySequenceEngine}), so tiles are plain values rather than
 * {@code WorldPoint}s; the world package converts at the framework boundary.
 *
 * Equality includes the plane: the course spans planes 0-3 and the same x/y
 * exists on every plane; a position match that ignored the plane would
 * advance the sequence while the player is standing under the course.
 */
public final class StepTile
{
	private final int x;
	private final int y;
	private final int plane;

	public StepTile(int x, int y, int plane)
	{
		this.x = x;
		this.y = y;
		this.plane = plane;
	}

	public int getX()
	{
		return x;
	}

	public int getY()
	{
		return y;
	}

	public int getPlane()
	{
		return plane;
	}

	/** Chebyshev (chessboard) distance to another tile, on the same plane. */
	public int distanceTo(StepTile other)
	{
		if (other == null || other.plane != plane)
		{
			return Integer.MAX_VALUE;
		}
		return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof StepTile))
		{
			return false;
		}
		StepTile other = (StepTile) o;
		return x == other.x && y == other.y && plane == other.plane;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(x, y, plane);
	}

	@Override
	public String toString()
	{
		return "(" + x + ", " + y + ") z" + plane;
	}
}
