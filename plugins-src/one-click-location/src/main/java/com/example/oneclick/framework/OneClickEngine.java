package com.example.oneclick.framework;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * The 1:1 click engine - the rules of docs/plugin-development/
 * 01-design-philosophy.md as code. One instance per plugin.
 *
 * Contract with the plugin (all calls on the client thread):
 *
 *   requestArm(action, client)   from MenuOptionClicked for the plugin's own
 *                                marker-matched entry. Validates preconditions
 *                                and arms. Returns false (and records a
 *                                visible abort) if the click must be refused -
 *                                the plugin then consumes it. The client
 *                                dispatches the entry's own real action on
 *                                this same click: 1:1, nothing follows.
 *
 *   onGameTick(client)           once per GameTick. Evaluates ONE confirmation
 *                                step. CONFIRMED/ABORTED end the cycle and
 *                                fall back to IDLE the same tick (bounded);
 *                                PENDING past CONFIRM_HORIZON_TICKS is itself
 *                                an abort (observation window, not a wait loop).
 *
 *   reset(reason)                any natural reset trigger (login, plugin
 *                                shutdown, unexpected widget). Drops armed
 *                                intent; never fires anything.
 *
 * No timers, no schedulers, no retries: if the sequence could run without a
 * click, this class would be a bot (gotchas §8) - it cannot.
 */
public final class OneClickEngine
{
	/**
	 * How many GameTicks a server confirmation may take. Observation only -
	 * nothing is dispatched in this window (rule 1: ticks are the game's
	 * metronome; using them for checking is not action). 3 ticks (~1.8s)
	 * covers walk-packet round trips.
	 */
	private static final int CONFIRM_HORIZON_TICKS = 3;

	private ClickState state = ClickState.IDLE;
	private BoundedAction action;
	private WorldPoint armedAt;
	private int pendingTicks;

	// Last terminal outcome, kept visible until the next cycle (rule 6).
	private String lastOutcome = "idle";
	private ClickState lastState = ClickState.IDLE;

	private int cyclesConfirmed;
	private int cyclesAborted;

	/**
	 * Try to arm on the user's click. Returns true if armed (the click's own
	 * action may proceed); false if refused (plugin must event.consume()).
	 */
	public boolean requestArm(BoundedAction bounded, Client client)
	{
		if (bounded == null || !bounded.canArm(client))
		{
			abort("preconditions failed at click time");
			return false;
		}

		Player me = client.getLocalPlayer();
		if (me == null)
		{
			abort("no local player at click time");
			return false;
		}

		// Re-arm while armed = the player clicked again; the old intent's
		// confirmation window is simply replaced (no hidden double state).
		action = bounded;
		armedAt = me.getWorldLocation();
		pendingTicks = 0;
		state = ClickState.ARMED;
		lastState = ClickState.ARMED;
		lastOutcome = "armed: " + bounded.name();
		return true;
	}

	/** One confirmation step per GameTick (call only when logged in). */
	public void onGameTick(Client client)
	{
		if (state != ClickState.ARMED)
		{
			return;
		}

		Player me = client.getLocalPlayer();
		if (me == null)
		{
			abort("local player lost mid-cycle");
			return;
		}

		ConfirmResult result = action.confirm(client, armedAt);
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
			// horizon is an anomaly, and anomalies abort (rule 3).
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
	 * Session-stats reset (rule 4's global trigger: login). Called alongside
	 * reset() on GameStateChanged -> LOGIN_SCREEN; mid-session resets keep
	 * the counters.
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
