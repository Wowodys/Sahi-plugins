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

import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.sahij.agility.engine.AgilityCourse;
import net.runelite.client.plugins.sahij.agility.engine.AgilityStep;
import net.runelite.client.plugins.sahij.agility.engine.StepTile;

/**
 * Coordinate validation and obstacle lookup against the live scene - the
 * RuneLite-facing half of the world package, isolated from the pure engine
 * (which never sees a Client) and from the menu hooks. Answers five
 * questions; every refusal is returned as a reason string so the plugin can
 * fail visibly:
 *
 *   1. Does the course definition's documented chunk/region data agree with
 *      coordinate arithmetic? ({@link #courseIdentityFailure} - once per
 *      login, a definition-typo tripwire. METADATA ONLY: this check reports
 *      on the definition and never gates any step transition, arrival
 *      match or click - chunk boundaries cut arbitrarily across this
 *      course's geometry and must not gate progression)
 *   2. Is the player inside the course region 10806? (the cheap gate that
 *      keeps the whole subsystem inert elsewhere; region, not chunk)
 *   3. Is a step tile representable in the loaded scene? (a real action can
 *      only be honest for a tile the scene can see)
 *   4. Is the step's exact obstacle object present in the step's ARRIVAL
 *      AREA (world bounds + plane), and which instance is nearest the
 *      zone-selected tile? (exact-ID match, no id substitutions; the scan
 *      covers the documented tile bounding box widened by the arrival
 *      margin, because obstacle objects can sit a tile or two off the
 *      documented standing tiles - and chunk boundaries are irrelevant
 *      here by design)
 *   5. Which {@link MenuAction} variant carries the step's action verb for
 *      the obstacle's composition? (case-insensitive match on the
 *      composition's own actions array)
 */
public final class CourseValidator
{
	// Scene tile indices are 0..103 per plane.
	public static final int SCENE_MAX = 103;

	private CourseValidator()
	{
	}

	// ----- 1. course identity -----

	/**
	 * Cross-checks every step tile against its documented chunk id and region
	 * id using {@link RsCoordMath}. Null when the whole definition agrees
	 * with the arithmetic; otherwise the first mismatch, human-readable
	 * (surfaced once per login - fail visible, not fail silently).
	 *
	 * METADATA ONLY: this is a self-consistency report on the documented
	 * definition. NOTHING in the engine's transition logic, arrival matching
	 * or the click path consults chunk ids - a chunk boundary crossing
	 * mid-obstacle can never block step progression (see the engine's
	 * transition rule).
	 */
	@Nullable
	public static String courseIdentityFailure(AgilityCourse course)
	{
		for (AgilityStep step : course.getSteps())
		{
			for (StepTile tile : step.getTiles())
			{
				int region = RsCoordMath.regionId(tile.getX(), tile.getY());
				int chunk = RsCoordMath.chunkId(tile.getX(), tile.getY());
				if (region != step.getRegionId() || chunk != step.getChunkId())
				{
					return "step " + step.getNumber() + " tile " + tile
						+ " maps to region " + region + " / chunk " + chunk
						+ ", documented region " + step.getRegionId()
						+ " / chunk " + step.getChunkId();
				}
			}
		}
		return null;
	}

	// ----- 2. region gate -----

	/** True when the local player is inside the course region (10806). */
	public static boolean playerInCourseRegion(Client client)
	{
		Player me = client.getLocalPlayer();
		return me != null
			&& me.getWorldLocation().getRegionID() == AgilityCourse.COURSE_REGION_ID;
	}

	// ----- 3. scene representability -----

	/**
	 * Step tile -&gt; scene coordinates through the API helpers only (never raw
	 * shifts). Returns {sceneX, sceneY} or null when the tile is outside the
	 * loaded scene or has no tile on its plane.
	 */
	@Nullable
	public static int[] toSceneCoords(Client client, StepTile tile)
	{
		WorldPoint world = new WorldPoint(tile.getX(), tile.getY(), tile.getPlane());
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), world);
		if (local == null)
		{
			return null;
		}

		int sx = local.getSceneX();
		int sy = local.getSceneY();
		if (sx < 0 || sy < 0 || sx > SCENE_MAX || sy > SCENE_MAX)
		{
			return null;
		}

		Tile sceneTile = client.getTopLevelWorldView().getScene()
			.getTiles()[tile.getPlane()][sx][sy];
		if (sceneTile == null)
		{
			return null;
		}

		return new int[]{sx, sy};
	}

	// ----- 4. obstacle presence -----

	/**
	 * A located obstacle: the scene coordinates the menu action must carry,
	 * plus the tile it was found on (for logging/telemetry).
	 */
	public static final class SceneObjectTarget
	{
		private final int sceneX;
		private final int sceneY;
		private final int plane;
		private final int objectId;

		SceneObjectTarget(int sceneX, int sceneY, int plane, int objectId)
		{
			this.sceneX = sceneX;
			this.sceneY = sceneY;
			this.plane = plane;
			this.objectId = objectId;
		}

		public int getSceneX()
		{
			return sceneX;
		}

		public int getSceneY()
		{
			return sceneY;
		}

		public int getPlane()
		{
			return plane;
		}

		public int getObjectId()
		{
			return objectId;
		}

		@Override
		public String toString()
		{
			return "object " + objectId + " @ scene (" + sceneX + ", " + sceneY
				+ ") z" + plane;
		}
	}

	/**
	 * Finds the step's obstacle object (exact id) inside the step's ARRIVAL
	 * AREA - the documented tile bounding box widened by
	 * {@link AgilityStep#ARRIVAL_MARGIN_TILES}, on the step's plane - in the
	 * live scene. Scanning the widened world bounds (never chunk bounds) is
	 * deliberate: obstacle objects can sit a tile or two off the documented
	 * standing tiles, and a scan limited to the exact tile set would strand
	 * the click path exactly like a chunk-matched gate strands progression.
	 * Every Tile object slot is checked - obstacles are most often
	 * {@link GameObject}s, but wall/ground/decorative slots are exact-id
	 * matched too, so the lookup holds for any slot the course's objects
	 * live in without inventing placement data.
	 *
	 * When several tiles of the area host instances of the id, the one
	 * NEAREST (Chebyshev) the zone-selected tile wins - the screen-zone
	 * mapping therefore also disambiguates instances, keeping the human's
	 * cursor the sole selector. Ties resolve to the first in scan order
	 * (deterministic).
	 *
	 * @param preferred the screen-zone-selected tile (see ScreenZoneMapper)
	 * @return the target to dispatch against, or null when no instance of the
	 *         exact id exists in the step's arrival area in the loaded scene
	 */
	@Nullable
	public static SceneObjectTarget findObjectInStep(Client client, AgilityStep step, StepTile preferred)
	{
		SceneObjectTarget best = null;
		int bestDistance = Integer.MAX_VALUE;
		int[] box = step.arrivalBox();

		for (int x = box[0]; x <= box[2]; x++)
		{
			for (int y = box[1]; y <= box[3]; y++)
			{
				StepTile tile = new StepTile(x, y, step.getPlane());
				int[] scene = toSceneCoords(client, tile);
				if (scene == null)
				{
					continue;
				}

				Tile sceneTile = client.getTopLevelWorldView().getScene()
					.getTiles()[tile.getPlane()][scene[0]][scene[1]];
				if (sceneTile == null)
				{
					continue;
				}

				boolean present =
					matches(step.getObjectId(), sceneTile.getWallObject())
						|| matches(step.getObjectId(), sceneTile.getDecorativeObject())
						|| matches(step.getObjectId(), sceneTile.getGroundObject())
						|| anyMatches(step.getObjectId(), sceneTile.getGameObjects());

				if (!present)
				{
					continue;
				}

				int distance = tile.distanceTo(preferred);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = new SceneObjectTarget(scene[0], scene[1], tile.getPlane(), step.getObjectId());
				}
			}
		}
		return best;
	}

	private static boolean matches(int objectId, @Nullable TileObject object)
	{
		return object != null && object.getId() == objectId;
	}

	private static boolean anyMatches(int objectId, @Nullable TileObject[] objects)
	{
		if (objects == null)
		{
			return false;
		}
		for (TileObject object : objects)
		{
			if (matches(objectId, object))
			{
				return true;
			}
		}
		return false;
	}

	// ----- 5. action-variant resolution -----

	/** composition action index -&gt; menu action type (the 1.13.6 id table). */
	private static MenuAction variantForIndex(int index)
	{
		switch (index)
		{
			case 0: return MenuAction.GAME_OBJECT_FIRST_OPTION;
			case 1: return MenuAction.GAME_OBJECT_SECOND_OPTION;
			case 2: return MenuAction.GAME_OBJECT_THIRD_OPTION;
			case 3: return MenuAction.GAME_OBJECT_FOURTH_OPTION;
			case 4: return MenuAction.GAME_OBJECT_FIFTH_OPTION;
			default: return null;
		}
	}

	/**
	 * Resolves the {@link MenuAction} that carries the step's action verb
	 * ("Climb-up", "Jump", "Cross") for the obstacle's composition: finds the
	 * verb in the composition's own actions array (case-insensitive - the
	 * spec spells step 3's verb "jump") and maps its index to the matching
	 * menu action type. Impostor-aware: multi-state objects resolve against
	 * their impostor composition, which is what the client renders.
	 *
	 * @return the variant, or null when the composition carries no such verb
	 *         (the caller falls back to {@code GAME_OBJECT_FIRST_OPTION} and
	 *         logs it - most agility obstacles expose their verb first)
	 */
	@Nullable
	public static MenuAction resolveActionVariant(Client client, int objectId, String action)
	{
		ObjectComposition composition = client.getObjectDefinition(objectId);
		if (composition == null)
		{
			return null;
		}
		if (composition.getImpostorIds() != null)
		{
			ObjectComposition impostor = composition.getImpostor();
			if (impostor != null)
			{
				composition = impostor;
			}
		}

		String[] actions = composition.getActions();
		if (actions == null)
		{
			return null;
		}
		for (int i = 0; i < actions.length && i < 5; i++)
		{
			if (actions[i] != null && actions[i].equalsIgnoreCase(action))
			{
				return variantForIndex(i);
			}
		}
		return null;
	}
}
