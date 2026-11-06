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
 * by {@link AgilityCourse}; the exact tile/chunk/region/object values are
 * spec-locked there and must not be invented or substituted anywhere else.
 *
 * A step means: "while the player is standing in this step's ARRIVAL AREA,
 * this step's action is the next thing to do". The arrival area is the
 * bounding box of the step's tiles expanded by {@link #ARRIVAL_MARGIN_TILES}
 * on every side, matched on the step's plane - WORLD COORDINATES AND PLANE
 * ONLY. Chunk ids are deliberately never consulted here: chunk boundaries
 * cut arbitrarily across game geometry (they change mid-obstacle on this
 * course), so they are metadata on this class, never a matching rule.
 * Obstacle landings routinely fall a tile or two outside a documented tile
 * set; the margin absorbs that without abandoning the documented bounds.
 *
 * Tile-array ordering is part of the contract: the array index IS the
 * primary-monitor screen-zone index (world/ScreenZoneMapper). Ranges expand
 * x-ascending in the outer loop and y-ascending in the inner loop, e.g.
 * step 3's 4x3 range yields
 * {@code (2720,3491), (2720,3492), (2720,3493), (2721,3491), ...} - 12
 * tiles, 12 equal screen zones across the primary monitor's width. The
 * exact tile set (not the widened box) is also what strict-mode standing
 * validation uses at click time.
 */
public final class AgilityStep
{
	/** Sentinel object id for the pure walk step (no object interaction). */
	public static final int WALK_OBJECT_ID = -1;

	/**
	 * How many tiles the arrival area extends beyond the step's tile
	 * bounding box on each side. Obstacle landings routinely overshoot the
	 * documented tile sets by a tile or two (and chunk boundaries shift
	 * mid-obstacle on this course); 2 absorbs that while keeping every
	 * step's arrival area disjoint from its neighbors' on the same plane
	 * (verified across all seven steps' boxes).
	 */
	public static final int ARRIVAL_MARGIN_TILES = 2;

	private final int number;
	private final String action;
	private final String target;
	private final int objectId;
	private final boolean planeDropResets;
	private final int chunkId;
	private final int regionId;
	private final List<StepTile> tiles;

	// Arrival area: bounding box of the tiles, widened by the margin. All
	// tiles of a step share one plane by construction (asserted below).
	private final int plane;
	private final int boxMinX;
	private final int boxMinY;
	private final int boxMaxX;
	private final int boxMaxY;

	AgilityStep(int number, String action, String target, int objectId,
		boolean planeDropResets, int chunkId, int regionId, List<StepTile> tiles)
	{
		if (tiles == null || tiles.isEmpty())
		{
			throw new IllegalArgumentException("step " + number + " has no tiles");
		}
		this.number = number;
		this.action = action;
		this.target = target;
		this.objectId = objectId;
		this.planeDropResets = planeDropResets;
		this.chunkId = chunkId;
		this.regionId = regionId;
		this.tiles = Collections.unmodifiableList(tiles);

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int tilePlane = tiles.get(0).getPlane();
		for (StepTile tile : tiles)
		{
			if (tile.getPlane() != tilePlane)
			{
				throw new IllegalArgumentException("step " + number
					+ " spans planes - the course definition is single-plane per step");
			}
			minX = Math.min(minX, tile.getX());
			minY = Math.min(minY, tile.getY());
			maxX = Math.max(maxX, tile.getX());
			maxY = Math.max(maxY, tile.getY());
		}
		this.plane = tilePlane;
		this.boxMinX = minX - ARRIVAL_MARGIN_TILES;
		this.boxMinY = minY - ARRIVAL_MARGIN_TILES;
		this.boxMaxX = maxX + ARRIVAL_MARGIN_TILES;
		this.boxMaxY = maxY + ARRIVAL_MARGIN_TILES;
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
	 * Documented chunk id covering every tile of this step. METADATA ONLY -
	 * never consulted by arrival matching, step transitions or click
	 * validation (chunk boundaries cut across this course's geometry; a
	 * chunk change mid-obstacle must not and does not affect progression).
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

	/** The ordered tile array; index = screen-zone index. */
	public List<StepTile> getTiles()
	{
		return tiles;
	}

	/** Convenience for single-tile steps; the one tile they have. */
	public StepTile soleTile()
	{
		return tiles.get(0);
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

	/** The single plane every tile of this step lives on. */
	public int getPlane()
	{
		return plane;
	}

	/**
	 * True when the world position is inside this step's ARRIVAL AREA: the
	 * tile bounding box widened by {@link #ARRIVAL_MARGIN_TILES}, on this
	 * step's plane. World coordinates and plane ONLY - deliberately chunk
	 * blind (see class javadoc).
	 */
	public boolean containsArrival(int x, int y, int plane)
	{
		return plane == this.plane
			&& x >= boxMinX && x <= boxMaxX
			&& y >= boxMinY && y <= boxMaxY;
	}

	/**
	 * The arrival area as {@code {minX, minY, maxX, maxY}} world bounds
	 * (already margin-widened), for the world layer's obstacle scan.
	 */
	public int[] arrivalBox()
	{
		return new int[]{boxMinX, boxMinY, boxMaxX, boxMaxY};
	}

	@Override
	public String toString()
	{
		return "step " + number + " (" + action
			+ (target.isEmpty() ? "" : " " + target) + ", " + tiles.size() + " tile"
			+ (tiles.size() == 1 ? "" : "s") + ")";
	}
}
