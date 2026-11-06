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
package net.runelite.client.plugins.kuri.oneclick.location;

import net.runelite.client.plugins.kuri.oneclick.engine.BoundedAction;
import net.runelite.client.plugins.kuri.oneclick.engine.ConfirmResult;
import net.runelite.client.plugins.kuri.oneclick.engine.PlayerSnapshot;

/**
 * The concrete 1:1 action: the player clicks the injected WALK entry, and
 * the client's own dispatch performs the ordinary "walk here" to the target
 * tile - the exact packet and UI trail of clicking the tile by hand.
 *
 * Confirmations (all server-confirmed, never timers):
 *   - player's world location CHANGED since the click  -&gt; the server accepted
 *     the walk (it is moving the player). CONFIRMED - the cycle ends; arrival
 *     happens by the server's own pathing, no plugin involvement.
 *   - player's world location EQUALS the target tile   -&gt; arrived outright.
 *     CONFIRMED.
 *   - location unchanged within the engine's observation horizon -&gt; blocked
 *     or zero-distance click. ABORTED (anomaly; no retry).
 *
 * Pure like the whole engine package: consumes only {@link PlayerSnapshot}s.
 */
public final class WalkToTileAction implements BoundedAction
{
	private final PlayerSnapshot target;

	public WalkToTileAction(PlayerSnapshot target)
	{
		this.target = target;
	}

	@Override
	public String name()
	{
		return "walk to " + target;
	}

	@Override
	public ConfirmResult confirm(PlayerSnapshot now, PlayerSnapshot armedAt)
	{
		if (now == null)
		{
			return ConfirmResult.aborted("local player lost");
		}

		if (now.sameTile(target))
		{
			return ConfirmResult.confirmed("arrived at target");
		}

		if (!now.sameTile(armedAt))
		{
			return ConfirmResult.confirmed("server moving player ("
				+ armedAt + " -> " + now + ")");
		}

		return ConfirmResult.pending();
	}
}
