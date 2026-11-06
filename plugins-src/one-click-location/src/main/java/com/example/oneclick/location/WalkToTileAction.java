package com.example.oneclick.location;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import com.example.oneclick.framework.BoundedAction;
import com.example.oneclick.framework.ConfirmResult;

/**
 * The concrete 1:1 action: the player clicks the injected WALK entry, and the
 * client's own dispatch performs the ordinary "walk here" to the target tile
 * - the exact packet and UI trail of clicking the tile by hand.
 *
 * Confirmations (all server-confirmed, never timers):
 *   - player's world location CHANGED since the click  -> the server accepted
 *     the walk (it is moving the player). CONFIRMED - the cycle ends; arrival
 *     happens by the server's own pathing, no plugin involvement.
 *   - player's world location EQUALS the target tile   -> arrived outright.
 *     CONFIRMED.
 *   - location unchanged within the engine's observation horizon -> blocked
 *     or zero-distance click. ABORTED (anomaly; no retry).
 */
final class WalkToTileAction implements BoundedAction
{
	private final WorldPoint target;

	WalkToTileAction(WorldPoint target)
	{
		this.target = target;
	}

	@Override
	public String name()
	{
		return "walk to (" + target.getX() + ", " + target.getY() + ") z" + target.getPlane();
	}

	@Override
	public boolean canArm(Client client)
	{
		Player me = client.getLocalPlayer();
		return me != null;
	}

	@Override
	public ConfirmResult confirm(Client client, WorldPoint armedAt)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return ConfirmResult.aborted("local player lost");
		}

		WorldPoint now = me.getWorldLocation();
		if (now.equals(target))
		{
			return ConfirmResult.confirmed("arrived at target");
		}
		if (!now.equals(armedAt))
		{
			return ConfirmResult.confirmed("server moving player ("
				+ armedAt.getX() + "," + armedAt.getY() + " -> "
				+ now.getX() + "," + now.getY() + ")");
		}
		return ConfirmResult.pending();
	}

	WorldPoint getTarget()
	{
		return target;
	}
}
