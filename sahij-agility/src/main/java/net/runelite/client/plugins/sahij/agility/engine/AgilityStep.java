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

import java.util.Collections;
import java.util.List;

/**
 * One step of the 7-step agility sequence - immutable, pure data. Built only
 * by {@link AgilityCourse}; the exact tile, arrival, chunk/region and object
 * values are spec-locked there and must not be invented or substituted
 * anywhere else.
 *
 * Two distinct coordinate sets, two distinct jobs:
 *
 *   - TILES: where the player stands while this step's action is taken. For
 *     multi-tile steps the array doubles as the primary-monitor screen-zone
 *     array (index = zone index, world/ScreenZoneMapper; ranges expand
 *     x-outer/y-inner ascending). The world layer's obstacle scan also uses
 *     exactly these tiles. Nothing here is widened, fuzzed or auto-ranged.
 *
 *   - ARRIVAL POINT: the single explicit, deterministic tile (x, y AND
 *     plane) the player lands on when this step's action completes. The
 *     engine advances strictly on an exact x/y/plane match against it - no
 *     margins, no bounding boxes, no plane heuristics. Vertical obstacles
 *     are handled by the data itself: step 2's arrival (2729, 3491, 3) IS
 *     the plane-3 wall-top landing of the plane-0 climb, so the 0 -&gt; 3
 *     transition needs no special-casing in logic.
 *
 * Chunk ids (target-tile chunk and arrival chunk) are METADATA ONLY: they
 * document the data and are cross-checked once per login for definition
 * typos, but no transition, arrival match or click validation ever consults
 * them - chunk boundaries cut arbitrarily across this course's geometry.
 */
public final class AgilityStep
{
	/** Sentinel object id for the pure walk step (no object interaction). */
	public static final int WALK_OBJECT_ID = -1;

	private final int number;
	private final String action;
	private final String target;
	private final int objectId;
	private final boolean planeDropResets;
	private final int chunkId;
	private final int regionId;
	private final List<StepTile> tiles;
	private final StepTile arrivalPoint;
	private final int arrivalChunkId;

	AgilityStep(int number, String action, String target, int objectId,
		boolean planeDropResets, int chunkId, int regionId, List<StepTile> tiles,
		StepTile arrivalPoint, int arrivalChunkId)
	{
		if (tiles == null || tiles.isEmpty())
		{
			throw new IllegalArgumentException("step " + number + " has no tiles");
		}
		if (arrivalPoint == null)
		{
			throw new IllegalArgumentException("step " + number + " has no arrival point");
		}
		int tilePlane = tiles.get(0).getPlane();
		for (StepTile tile : tiles)
		{
			if (tile.getPlane() != tilePlane)
			{
				throw new IllegalArgumentException("step " + number
					+ " spans planes - the course definition is single-plane per step");
			}
		}
		this.number = number;
		this.action = action;
		this.target = target;
		this.objectId = objectId;
		this.planeDropResets = planeDropResets;
		this.chunkId = chunkId;
		this.regionId = regionId;
		this.tiles = Collections.unmodifiableList(tiles);
		this.arrivalPoint = arrivalPoint;
		this.arrivalChunkId = arrivalChunkId;
	}

	/** Step number in the documented sequence, 1..7. */
	public int getNumber()
	{
		return number;
	}

	/**
	 * The menu action verb, exactly as the spec names it ("Walk here",
	 * "Climb-up", "Jump", "Cross"). Action-variant matching against the
	 * object composition is case-insensitive, so the spec's step-3 spelling
	 * "jump" and the display form "Jump" are the same verb.
	 */
	public String getAction()
	{
		return action;
	}

	/** The menu target noun ("Wall", "Gap", "Tightrope", "Edge"); "" for walk. */
	public String getTarget()
	{
		return target;
	}

	/** True when this step is a plain walk (no object interaction). */
	public boolean isWalk()
	{
		return objectId == WALK_OBJECT_ID;
	}

	/** Exact spec object id; {@link #WALK_OBJECT_ID} for the walk step. */
	public int getObjectId()
	{
		return objectId;
	}

	/**
	 * Safety condition: when true and the player's plane drops to 0 while
	 * this step is current, the engine resets to step 1 immediately (a fall
	 * off the course). Spec applies this to steps 3, 4 and 5 only.
	 */
	public boolean isPlaneDropResets()
	{
		return planeDropResets;
	}

	/**
	 * Documented chunk id covering every target tile of this step. METADATA
	 * ONLY - never consulted by arrival matching, step transitions or click
	 * validation.
	 */
	public int getChunkId()
	{
		return chunkId;
	}

	/** Documented region id (10806 for every step of this course). */
	public int getRegionId()
	{
		return regionId;
	}

	/** The ordered tile array; index = screen-zone index. Never widened. */
	public List<StepTile> getTiles()
	{
		return tiles;
	}

	/** Convenience for single-tile steps; the one tile they have. */
	public StepTile soleTile()
	{
		return tiles.get(0);
	}

	/** The single plane every target tile of this step lives on. */
	public int getPlane()
	{
		return tiles.get(0).getPlane();
	}

	/**
	 * The explicit, deterministic arrival tile (x, y, plane) of this step's
	 * action - the exact position the engine requires before advancing to
	 * the successor.
	 */
	public StepTile getArrivalPoint()
	{
		return arrivalPoint;
	}

	/**
	 * Documented chunk id of the arrival tile. METADATA ONLY, like
	 * {@link #getChunkId()} - never a transition gate.
	 */
	public int getArrivalChunkId()
	{
		return arrivalChunkId;
	}

	/** True when {@code x, y, plane} denotes one of this step's tiles. */
	public boolean containsTile(int x, int y, int plane)
	{
		for (StepTile tile : tiles)
		{
			if (tile.getX() == x && tile.getY() == y && tile.getPlane() == plane)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * STRICT arrival match: true only when {@code x, y, plane} is exactly
	 * this step's documented arrival tile - all three components. One tile
	 * off in any axis, or the right x/y on the wrong plane, is not an
	 * arrival. There is deliberately no tolerance, margin or area here.
	 */
	public boolean isAtArrival(int x, int y, int plane)
	{
		return arrivalPoint.getX() == x
			&& arrivalPoint.getY() == y
			&& arrivalPoint.getPlane() == plane;
	}

	@Override
	public String toString()
	{
		return "step " + number + " (" + action
			+ (target.isEmpty() ? "" : " " + target) + ", " + tiles.size() + " tile"
			+ (tiles.size() == 1 ? "" : "s") + ", arrival " + arrivalPoint + ")";
	}
}
