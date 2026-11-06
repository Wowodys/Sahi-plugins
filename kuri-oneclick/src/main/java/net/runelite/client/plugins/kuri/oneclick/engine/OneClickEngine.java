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
package net.runelite.client.plugins.kuri.oneclick.engine;

/**
 * The 1:1 click engine - the rules of the one-click discipline as code.
 * One instance per plugin. This class is PURE: it imports nothing from
 * RuneLite, consumes only {@link PlayerSnapshot}s, and can be unit tested
 * standalone.
 *
 * Contract with the plugin (all calls on the client thread):
 *
 *   requestArm(action, player)   from MenuOptionClicked for the plugin's own
 *                                marker-matched entry, AFTER the world layer
 *                                validated the click preconditions (an
 *                                unvalidated click is consumed by the plugin
 *                                and never reaches here). Arms the cycle;
 *                                the client dispatches the entry's own real
 *                                action on this same click: 1:1, nothing
 *                                follows.
 *
 *   refuseArm(reason)            the world layer refused the click at click
 *                                time; the click is consumed and the abort
 *                                is surfaced (rule: fail visible).
 *
 *   onGameTick(player)           once per GameTick. Evaluates ONE
 *                                confirmation step. CONFIRMED/ABORTED end
 *                                the cycle and fall back to IDLE the same
 *                                tick (bounded); PENDING past the confirm
 *                                horizon is itself an abort (observation
 *                                window, not a wait loop).
 *
 *   reset(reason)                any natural reset trigger (login, plugin
 *                                shutdown, unexpected widget). Drops armed
 *                                intent; never fires anything.
 *
 * No timers, no schedulers, no retries: if the sequence could run without a
 * click, this class would be a bot - it cannot.
 */
public final class OneClickEngine
{
	/**
	 * How many GameTicks a server confirmation may take. Observation only -
	 * nothing is dispatched in this window (ticks are the game's metronome;
	 * using them for checking is not action). 3 ticks (~1.8s) covers
	 * walk-packet round trips.
	 */
	private static final int CONFIRM_HORIZON_TICKS = 3;

	private ClickState state = ClickState.IDLE;
	private BoundedAction action;
	private PlayerSnapshot armedAt;
	private int pendingTicks;

	// Last terminal outcome, kept visible until the next cycle (fail visible).
	private String lastOutcome = "idle";
	private ClickState lastState = ClickState.IDLE;

	private int cyclesConfirmed;
	private int cyclesAborted;

	/**
	 * Arm on the user's click. Preconditions were already validated by the
	 * world layer; this records the intent. Re-arm while armed = the player
	 * clicked again; the old intent's confirmation window is simply replaced
	 * (no hidden double state).
	 */
	public boolean requestArm(BoundedAction boundedAction, PlayerSnapshot player)
	{
		if (boundedAction == null || player == null)
		{
			refuseArm("invalid arm request");
			return false;
		}

		action = boundedAction;
		armedAt = player;
		pendingTicks = 0;
		state = ClickState.ARMED;
		lastState = ClickState.ARMED;
		lastOutcome = "armed: " + boundedAction.name();
		return true;
	}

	/**
	 * The world layer refused the click (preconditions failed at click
	 * time); the plugin consumes it. Record a visible abort.
	 */
	public void refuseArm(String reason)
	{
		abort(reason);
	}

	/** One confirmation step per GameTick. */
	public void onGameTick(PlayerSnapshot player)
	{
		if (state != ClickState.ARMED)
		{
			return;
		}

		if (player == null)
		{
			abort("local player lost mid-cycle");
			return;
		}

		ConfirmResult result = action.confirm(player, armedAt);
		if (result.isConfirmed())
		{
			cyclesConfirmed++;
			settle(ClickState.IDLE, "confirmed: " + result.getReason());
		}
		else if (result.isAborted())
		{
			abort(result.getReason());
		}
		else
		{
			// PENDING: keep observing, but bounded - a silence past the
			// horizon is an anomaly, and anomalies abort.
			pendingTicks++;
			if (pendingTicks >= CONFIRM_HORIZON_TICKS)
			{
				abort("no server confirmation within " + CONFIRM_HORIZON_TICKS + " ticks");
			}
		}
	}

	/**
	 * Natural reset trigger (login, plugin shutdown, unexpected widget).
	 * Clears intent without dispatching anything.
	 */
	public void reset(String reason)
	{
		if (state == ClickState.ARMED)
		{
			cyclesAborted++;
		}
		state = ClickState.IDLE;
		action = null;
		armedAt = null;
		pendingTicks = 0;
		lastState = ClickState.IDLE;
		lastOutcome = "reset: " + reason;
	}

	/**
	 * Session-stats reset (login). Called alongside reset() on
	 * GameStateChanged -&gt; LOGIN_SCREEN; mid-session resets keep the
	 * counters.
	 */
	public void clearStats()
	{
		cyclesConfirmed = 0;
		cyclesAborted = 0;
	}

	private void abort(String reason)
	{
		cyclesAborted++;
		settle(ClickState.IDLE, "aborted: " + reason);
	}

	private void settle(ClickState terminal, String outcome)
	{
		state = ClickState.IDLE;
		action = null;
		armedAt = null;
		pendingTicks = 0;
		lastState = terminal;
		lastOutcome = outcome;
	}

	// ----- overlay accessors -----

	public ClickState getState()
	{
		return state;
	}

	public ClickState getLastState()
	{
		return lastState;
	}

	public String getLastOutcome()
	{
		return lastOutcome;
	}

	public int getCyclesConfirmed()
	{
		return cyclesConfirmed;
	}

	public int getCyclesAborted()
	{
		return cyclesAborted;
	}

	public boolean isArmed()
	{
		return state == ClickState.ARMED;
	}
}
