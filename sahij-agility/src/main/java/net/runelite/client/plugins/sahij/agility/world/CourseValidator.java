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

import java.util.Locale;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
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
 *      coordinate arithmetic - for every target tile AND every explicit
 *      arrival point? ({@link #courseIdentityFailure} - once per login, a
 *      definition-typo tripwire. METADATA ONLY: this check reports on the
 *      definition and never gates any step transition, arrival match or
 *      click - chunk boundaries cut arbitrarily across this course's
 *      geometry and must not gate progression)
 *   2. Is the player inside the course region 10806? (the cheap gate that
 *      keeps the whole subsystem inert elsewhere; region, not chunk)
 *   3. Is a step tile representable in the loaded scene? (a real action can
 *      only be honest for a tile the scene can see)
 *   4. Is the step's exact obstacle object present on the step's own
 *      documented tiles, and which instance is nearest the zone-selected
 *      tile? (exact-ID match, exact documented tiles - no substitutions,
 *      no widened scans, no margins)
 *   5. Which {@link MenuAction} variant carries the step's action verb for
 *      the obstacle's composition? (two-pass bind - exact case-insensitive,
 *      then containment - over the composition's own actions array; an
 *      unbindable verb is the CALLER's refusal, never a guessed dispatch)
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
	 * Cross-checks every step's documented data against coordinate
	 * arithmetic using {@link RsCoordMath}: each target tile against the
	 * step's documented chunk/region id, and the step's explicit arrival
	 * point against its documented arrival chunk. Null when the whole
	 * definition agrees; otherwise the first mismatch, human-readable
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

			StepTile arrival = step.getArrivalPoint();
			int arrivalRegion = RsCoordMath.regionId(arrival.getX(), arrival.getY());
			int arrivalChunk = RsCoordMath.chunkId(arrival.getX(), arrival.getY());
			if (arrivalRegion != step.getRegionId()
				|| arrivalChunk != step.getArrivalChunkId())
			{
				return "step " + step.getNumber() + " arrival " + arrival
					+ " maps to region " + arrivalRegion + " / chunk " + arrivalChunk
					+ ", documented region " + step.getRegionId()
					+ " / chunk " + step.getArrivalChunkId();
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
	 * Step tile -&gt; scene coordinates, PLANE-DECOUPLED on purpose. The
	 * {@code WorldPoint} overload of {@code LocalPoint.fromWorld} hard-returns
	 * null whenever the client's ACTIVE plane differs from the point's plane
	 * (its first guard) - and the landings that precede steps 6 and 7 are
	 * exactly the plane-changing ones (z2 -&gt; z3 and z3 -&gt; z2), where the
	 * active plane can lag the player for a few ticks. During that window the
	 * WorldPoint overload made every step tile "unconvertible" and the whole
	 * obstacle scan report the object as absent. Scene x/y are plane-
	 * INDEPENDENT arithmetic (world - scene base); the plane only selects the
	 * tile-array layer, which this method controls explicitly below. Returns
	 * {sceneX, sceneY} or null when the tile is outside the loaded scene or
	 * has no tile on its own plane.
	 */
	@Nullable
	public static int[] toSceneCoords(Client client, StepTile tile)
	{
		WorldView worldview = client.getTopLevelWorldView();

		// The (wv, x, y) overload carries NO active-plane guard.
		if (!WorldPoint.isInScene(worldview, tile.getX(), tile.getY()))
		{
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(worldview, tile.getX(), tile.getY());
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

		Tile sceneTile = worldview.getScene().getTiles()[tile.getPlane()][sx][sy];
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
	 * Finds the step's obstacle object (exact id) on the step's own
	 * documented tiles - exactly those tiles, never widened - in the live
	 * scene. Every Tile object slot is checked: obstacles are most often
	 * {@link GameObject}s, but wall/ground/decorative slots are exact-id
	 * matched too, so the lookup holds for any slot the course's objects
	 * live in without inventing placement data.
	 *
	 * The returned target carries the object's OWN scene coordinates: for
	 * {@link GameObject}s that is {@link GameObject#getSceneMinLocation()}
	 * - the exact tile the client's own menu rows carry as param0/param1 -
	 * not the scanned step tile. An action dispatched at any other tile of
	 * a multi-tile object can be rejected server-side as a silent no-op,
	 * which the click's movement observation can only report as a timeout
	 * (the step 6/7 failure mode: those objects' documented standing tiles
	 * differ from their min-location tile). Wall/decorative/ground slots
	 * keep the scanned tile (their location IS the tile).
	 *
	 * Each found instance is cross-checked against its OWN world location:
	 * the live object's plane must agree with the step's documented plane
	 * before the instance is accepted. A misresolved or stale instance at
	 * the chunk/scene edges the late course steps sit on must surface as a
	 * miss here - never as a dispatch the client silently drops.
	 *
	 * When several of the step's tiles host instances of the id, the one
	 * NEAREST (Chebyshev) the zone-selected tile wins - the screen-zone
	 * mapping therefore also disambiguates instances, keeping the human's
	 * cursor the sole selector. Ties resolve to the first in canonical tile
	 * order (deterministic).
	 *
	 * @param preferred the screen-zone-selected tile (see ScreenZoneMapper)
	 * @return the target to dispatch against, or null when no verifiable
	 *         instance of the exact id exists on the step's tiles in the
	 *         loaded scene
	 */
	@Nullable
	public static SceneObjectTarget findObjectInStep(Client client, AgilityStep step, StepTile preferred)
	{
		SceneObjectTarget best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (StepTile tile : step.getTiles())
		{
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

			TileObject object = firstInstanceOf(step.getObjectId(), sceneTile);
			if (object == null)
			{
				continue;
			}

			// Live cross-check: the object's own reported world location
			// must sit on the step's documented plane.
			WorldPoint objectWorld = object.getWorldLocation();
			if (objectWorld == null || objectWorld.getPlane() != tile.getPlane())
			{
				continue;
			}

			// The object's OWN scene coordinates - the client's menu rows
			// for game objects carry the min-location tile, so the action
			// must be addressed there, not at whichever standing tile the
			// scan happened to find it on.
			int targetX = scene[0];
			int targetY = scene[1];
			if (object instanceof GameObject)
			{
				Point min = ((GameObject) object).getSceneMinLocation();
				if (min != null)
				{
					targetX = min.getX();
					targetY = min.getY();
				}
			}

			int distance = tile.distanceTo(preferred);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = new SceneObjectTarget(targetX, targetY, tile.getPlane(), step.getObjectId());
			}
		}
		return best;
	}

	/** First exact-id instance in any of the tile's object slots, or null. */
	@Nullable
	private static TileObject firstInstanceOf(int objectId, Tile sceneTile)
	{
		TileObject wall = sceneTile.getWallObject();
		if (matches(objectId, wall))
		{
			return wall;
		}
		TileObject decorative = sceneTile.getDecorativeObject();
		if (matches(objectId, decorative))
		{
			return decorative;
		}
		TileObject ground = sceneTile.getGroundObject();
		if (matches(objectId, ground))
		{
			return ground;
		}
		TileObject[] gameObjects = sceneTile.getGameObjects();
		if (gameObjects != null)
		{
			for (TileObject object : gameObjects)
			{
				if (matches(objectId, object))
				{
					return object;
				}
			}
		}
		return null;
	}

	private static boolean matches(int objectId, @Nullable TileObject object)
	{
		return object != null && object.getId() == objectId;
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
	 * Impostor-aware composition resolution: multi-state objects resolve
	 * against their impostor composition, which is what the client renders
	 * and offers menu actions for.
	 */
	@Nullable
	private static ObjectComposition compositionFor(Client client, int objectId)
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
		return composition;
	}

	/**
	 * Resolves the {@link MenuAction} that carries the step's action verb
	 * ("Climb-up", "Jump", "Cross") for the obstacle's composition, binding
	 * the verb to the packet interaction type via the composition's own
	 * actions array. Two deterministic passes: exact case-insensitive match
	 * first ("jump" binds "Jump" - the spec's step-3 spelling), then a
	 * containment pass ("Jump" binding "Jump across") for obstacles whose
	 * verb spelling varies.
	 *
	 * @return the variant, or null when the composition carries no bindable
	 *         verb. A null MUST be refused visibly by the caller
	 *         ({@link #describeActions}) - dispatching a guessed variant
	 *         sends a packet for the WRONG verb, which the server executes
	 *         as a silent no-op: the player never moves and the click's
	 *         observation can only end in its timeout.
	 */
	@Nullable
	public static MenuAction resolveActionVariant(Client client, int objectId, String action)
	{
		ObjectComposition composition = compositionFor(client, objectId);
		if (composition == null)
		{
			return null;
		}

		String[] actions = composition.getActions();
		if (actions == null)
		{
			return null;
		}

		String verb = action.toLowerCase(Locale.ENGLISH);
		int containsIndex = -1;
		for (int i = 0; i < actions.length && i < 5; i++)
		{
			if (actions[i] == null)
			{
				continue;
			}
			if (actions[i].equalsIgnoreCase(action))
			{
				return variantForIndex(i);
			}
			if (containsIndex == -1
				&& actions[i].toLowerCase(Locale.ENGLISH).contains(verb))
			{
				containsIndex = i;
			}
		}
		return containsIndex == -1 ? null : variantForIndex(containsIndex);
	}

	/**
	 * Human-readable dump of the obstacle's composition actions (impostor
	 * aware), for the visible refusal when a step's verb cannot be bound -
	 * the log line tells you exactly what the object offers instead of a
	 * silent timeout later.
	 */
	public static String describeActions(Client client, int objectId)
	{
		ObjectComposition composition = compositionFor(client, objectId);
		if (composition == null)
		{
			return "composition unavailable";
		}

		String[] actions = composition.getActions();
		if (actions == null || actions.length == 0)
		{
			return "no actions";
		}

		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < actions.length; i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(i).append(':')
				.append(actions[i] == null ? "null" : actions[i]);
		}
		return out.append(']').toString();
	}
}
