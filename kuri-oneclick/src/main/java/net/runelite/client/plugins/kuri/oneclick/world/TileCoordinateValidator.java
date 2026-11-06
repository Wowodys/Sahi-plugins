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

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Coordinate validation, isolated from both the state machine (engine
 * package) and the menu hooks (menu package). Answers exactly two questions:
 *
 *   1. Is a world tile currently representable in the loaded scene (real
 *      scene coordinates, plane loaded, actual tile under it)? A real WALK
 *      entry can only be honest for a tile the scene can see.
 *   2. Is the player inside the target's region (the cheap gate that keeps
 *      the whole subsystem inert elsewhere)?
 */
public final class TileCoordinateValidator
{
	// Scene tile indices are 0..103 per plane.
	public static final int SCENE_MAX = 103;

	private TileCoordinateValidator()
	{
	}

	/**
	 * World -&gt; scene coords through the API helpers only (never shift raw
	 * ints). Returns {sceneX, sceneY} or null when the target is outside the
	 * loaded scene or has no tile (blocked).
	 */
	public static int[] toSceneCoords(Client client, WorldPoint target)
	{
		LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
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

		Tile tile = client.getTopLevelWorldView().getScene().getTiles()[target.getPlane()][sx][sy];
		if (tile == null)
		{
			return null;
		}

		return new int[]{sx, sy};
	}

	/** True when the local player is inside the target tile's region. */
	public static boolean playerInTargetRegion(Client client, WorldPoint target)
	{
		Player me = client.getLocalPlayer();
		return me != null
			&& me.getWorldLocation().getRegionID() == RsCoords.regionId(target.getX(), target.getY());
	}
}
