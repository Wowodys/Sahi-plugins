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

/**
 * The agility sequence state machine - PURE: it imports nothing from
 * RuneLite, consumes only {@link PlayerState} snapshots, and can be unit
 * tested standalone. One instance per plugin, owning exactly one opinion:
 * WHICH step of {@link AgilityCourse} is next, derived from the server's own
 * position reports - never from timers.
 *
 * Transition rule (world bounds + plane, chunk-blind): a step is current
 * while the player stands in its ARRIVAL AREA - the bounding box of its
 * tiles widened by {@link AgilityStep#ARRIVAL_MARGIN_TILES}, matched on the
 * step's plane ({@link AgilityStep#containsArrival}). CHUNK IDS ARE NEVER
 * CONSULTED: chunk boundaries cut arbitrarily across this course's geometry
 * (the chunk changes mid-way between the step-2 wall climb and the step-3
 * gap), so a chunk comparison here would strand the sequence - they exist on
 * the step definition as metadata only. A FORWARD transition (step k to its
 * successor) additionally verifies that a click for step k was DISPATCHED -
 * advancement is always "dispatched the action AND arrived in the successor's
 * world bounds", never a geometric gate alone. Any other placement change
 * (manual walk-back, login mid-course, recovery from an unwatched fall) is a
 * POSITIONAL RESYNC to the last step whose arrival area contains the player:
 * visibly labeled, never a dispatch, and never stuck. Off every arrival area
 * (mid-obstacle animation, between platforms): the current step holds.
 *
 * Z-plane safety fallback (the spec's rule, verbatim): while a step flagged
 * {@link AgilityStep#isPlaneDropResets()} is current - steps 3, 4 and 5 -
 * and the player's plane drops to 0, the machine resets to step 1
 * IMMEDIATELY and clears any armed click. Steps 6 and 7 carry no such flag
 * in the spec, so a fall there is handled by ordinary positional resync once
 * the player walks back into a step's arrival area (documented: returning to
 * the start area after step 7 - fall or finished jump alike - counts the
 * lap; position alone cannot distinguish the two).
 *
 * Click observability (fail visible): every dispatched click ARMS the engine
 * with the dispatched step, the screen-zone-selected tile index and the
 * physical mouse X that chose it. The next position change confirms it; no
 * movement within {@link #CONFIRM_HORIZON_TICKS} is an abort - a visible
 * anomaly, never a retry. There is no scheduler, no loop, no retry path: if
 * the sequence could advance without the server moving the player, this
 * class would be a bot - it cannot.
 *
 * 1:1 CLICK GATING (the anti-spam invariant, enforced here, not hoped for).
 * The plugin MUST ask {@link #clickGateReason()} before dispatching and must
 * refuse visibly when it returns non-null. Two hard gates, both mechanical:
 *
 *   - TICK gate: at most ONE dispatched click between two consecutive
 *     observations (GameTicks). Two physical clicks in the same tick can at
 *     most produce one action - click spam can never become action spam.
 *   - ARMED gate: while a dispatched click is still awaiting the server's
 *     movement confirmation, every further click is refused. Clicks are
 *     strictly serial: one click, one action, observed movement, THEN (and
 *     only then) the next click may dispatch. A dead click releases the
 *     gate only when its bounded observation aborts.
 *
 * Together with the plugin's consume-first interception this makes every
 * dispatched action the 1:1 consequence of exactly one user click: no
 * autonomous dispatch path exists anywhere in the module.
 *
 * Contract with the plugin (all calls on the client thread):
 *
 *   observe(player)        once per GameTick - safety check, position sync,
 *                          armed-click confirmation, and the tick gate's
 *                          turn-over
 *   clickGateReason()      BEFORE any dispatch - null means the click may
 *                          proceed; non-null is the refusal reason
 *   recordClick(...)       right after a successful 1:1 dispatch of the
 *                          current step's action (the client has already
 *                          performed the action on this click); engages the
 *                          tick gate for the rest of the tick
 *   refuseClick(reason)    a click was refused (gate or validation); the
 *                          plugin consumed it; nothing was dispatched
 *   reset(reason)          natural reset trigger (login, plugin shutdown)
 */
public final class AgilitySequenceEngine
{
	/**
	 * How many GameTicks a dispatched click may take to produce visible
	 * movement. Generous on purpose: obstacle animations hold the player on
	 * the dispatch tile for several ticks before the server moves them (the
	 * wall climb and tightrope are the slow ones). Observation only - nothing
	 * is dispatched inside this window; ticks are the game's metronome, and
	 * using them for checking is not action.
	 */
	private static final int CONFIRM_HORIZON_TICKS = 10;

	private final AgilityCourse course;

	private int stepIndex;
	private boolean synced;

	/**
	 * Condition 1 of the forward-transition rule: a click was dispatched for
	 * the CURRENT step (since becoming current). Forward advancement to the
	 * successor requires this plus arrival in the successor's world bounds;
	 * cleared on every transition and reset so each step re-earns its own
	 * dispatch fact.
	 */
	private boolean dispatchedCurrentStep;

	// 1:1 click gates (see class javadoc): one dispatch per tick, and none
	// while the previous dispatch still awaits server-confirmed movement.
	private boolean clickDispatchedSinceLastObserve;

	// Armed click (observability only - no dispatch ever follows from it).
	private boolean armed;
	private int armedStepIndex = -1;
	private int armedTileIndex = -1;
	private int armedMouseX = -1;
	private PlayerState armedAt;
	private int pendingTicks;

	// Last terminal outcome, kept visible until the next one (fail visible).
	private String lastOutcome = "idle";

	// Session stats (cleared on login screen, kept across mid-session resets).
	private int laps;
	private int clicksDispatched;
	private int clicksRefused;
	private int stepsConfirmed;
	private int clicksAborted;
	private int planeResets;

	public AgilitySequenceEngine(AgilityCourse course)
	{
		this.course = course;
	}

	// ----- per-tick observation -----

	/**
	 * One server observation per GameTick, in order: the tick gate turns
	 * over, the Z-plane safety fallback runs first (a fall outranks
	 * everything), then armed-click confirmation, then arrival-based step
	 * matching - so a landing that confirms a click AND advances the step
	 * ends the tick with the transition as the visible outcome.
	 */
	public void observe(PlayerState now)
	{
		clickDispatchedSinceLastObserve = false;

		if (now == null)
		{
			lastOutcome = "no local player - holding step " + getCurrentStep().getNumber();
			return;
		}

		// 1. Z-plane safety fallback: current step flagged, player fell to
		// plane 0 -> straight back to step 1, armed intent dropped.
		if (getCurrentStep().isPlaneDropResets() && now.getPlane() == 0)
		{
			planeResets++;
			if (armed)
			{
				armed = false;
				clicksAborted++;
			}
			resetToFirstStep("plane dropped to 0 at step "
				+ getCurrentStep().getNumber() + " - back to step 1");
			return;
		}

		// 2. Armed-click confirmation against the same snapshot: any tile or
		// plane change since dispatch = the server accepted the action. Runs
		// BEFORE the arrival block on purpose: when the landing both confirms
		// the click AND crosses into the next step's bounds, the transition
		// outcome ("advanced: dispatched click + arrival") must be the final
		// visible label for that tick.
		if (armed)
		{
			if (!now.sameTile(armedAt))
			{
				armed = false;
				stepsConfirmed++;
				lastOutcome = "confirmed: step " + (armedStepIndex + 1)
					+ " click accepted (" + armedAt + " -> " + now + ")";
			}
			else
			{
				pendingTicks++;
				if (pendingTicks >= CONFIRM_HORIZON_TICKS)
				{
					armed = false;
					clicksAborted++;
					lastOutcome = "aborted: no movement within "
						+ CONFIRM_HORIZON_TICKS + " ticks of step "
						+ (armedStepIndex + 1) + " click";
				}
			}
		}

		// 3. Step matching on WORLD BOUNDS + PLANE ONLY (chunk-blind): the
		// last step whose ARRIVAL AREA contains the player. Forward
		// advancement to the successor additionally requires the dispatched-
		// click fact for the current step; any other placement change is a
		// positional resync (recovery - visibly labeled, never a dispatch,
		// never stuck). No arrival match keeps the current step: mid-
		// animation or off-course is not a transition.
		int arrivalIndex = lastArrivalStep(now);
		if (arrivalIndex != -1 && arrivalIndex != stepIndex)
		{
			int fromStep = getCurrentStep().getNumber();
			boolean forward = isForwardTransition(arrivalIndex)
				&& dispatchedCurrentStep;

			if (stepIndex == course.lastStepIndex())
			{
				// Left step 7's arrival area into another step's: the
				// circuit is closed. Fall-vs-jump is indistinguishable from
				// position alone (see class javadoc) - the lap counts either
				// way, visibly.
				laps++;
			}
			stepIndex = arrivalIndex;
			dispatchedCurrentStep = false;
			synced = true;
			lastOutcome = forward
				? "advanced: step " + fromStep + " -> " + getCurrentStep().getNumber()
					+ " (dispatched click + arrival " + now + ", chunk-blind)"
				: "position resync: step " + getCurrentStep().getNumber()
					+ " (" + now + ", no dispatched click for the sequence path)";
		}
		else if (arrivalIndex != -1)
		{
			synced = true;
		}
	}

	/**
	 * Index of the LAST step whose ARRIVAL AREA (world bounds + plane,
	 * chunk-blind) contains the position, or -1. Order matters exactly once
	 * in this course - steps 1 and 2 share the start area, and standing
	 * there must mean "climb next" (step 2), not "walk again" (step 1).
	 */
	private int lastArrivalStep(PlayerState now)
	{
		int found = -1;
		for (int i = 0; i < AgilityCourse.STEP_COUNT; i++)
		{
			AgilityStep step = course.step(i);
			if (step.containsArrival(now.getX(), now.getY(), now.getPlane()))
			{
				found = i;
			}
		}
		return found;
	}

	/**
	 * Whether {@code arrivalIndex} is the natural successor of the current
	 * step in sequence order. The wrap from step 7 accepts both restart
	 * indices 0 and 1: steps 1 and 2 share the start area, and the
	 * last-match rule resolves a player standing there to step 2.
	 */
	private boolean isForwardTransition(int arrivalIndex)
	{
		if (arrivalIndex == (stepIndex + 1) % AgilityCourse.STEP_COUNT)
		{
			return true;
		}
		return stepIndex == course.lastStepIndex() && (arrivalIndex == 0 || arrivalIndex == 1);
	}

	// ----- click observability and the 1:1 gates -----

	/**
	 * The pre-dispatch 1:1 gate. The plugin MUST call this BEFORE resolving
	 * and dispatching a click, and must refuse visibly when non-null is
	 * returned. Null = the click may proceed.
	 *
	 * Gates, in order:
	 *   1. TICK gate - a click already dispatched since the last observation:
	 *      one action per tick, hard ceiling. Two clicks in one tick can
	 *      never produce two actions.
	 *   2. ARMED gate - the previous click still awaits server-confirmed
	 *      movement: clicks are strictly serial, so no burst of clicks can
	 *      ever become a burst of actions. The gate releases on movement
	 *      confirmation or on the bounded observation abort - never on a
	 *      timer-driven self-decision.
	 *
	 * @return the refusal reason, or null when the click may proceed (no
	 *         annotation dependency here - the engine package stays pure)
	 */
	public String clickGateReason()
	{
		if (clickDispatchedSinceLastObserve)
		{
			return "1:1 tick gate: another click already dispatched since the last game tick";
		}
		if (armed)
		{
			return "1:1 armed gate: step " + (armedStepIndex + 1)
				+ " click still awaiting server movement";
		}
		return null;
	}

	/**
	 * Record a successfully dispatched click. The client already performed
	 * the action on this click (walk state or object menu action); the engine
	 * engages the tick gate and arms its bounded movement observation.
	 *
	 * @param dispatchedStepIndex the step whose action was dispatched (must
	 *                            equal the current step - a mismatch means
	 *                            the click raced a step change and is
	 *                            surfaced as such, unarmed)
	 * @param tileIndex           the screen-zone-selected tile index within
	 *                            that step's tile array (0 for single-tile
	 *                            steps)
	 * @param mouseX              the primary-monitor absolute mouse X that
	 *                            selected the tile (telemetry/overlay)
	 * @param atDispatch          the player's position when the dispatch ran
	 */
	public void recordClick(int dispatchedStepIndex, int tileIndex, int mouseX, PlayerState atDispatch)
	{
		clicksDispatched++;
		clickDispatchedSinceLastObserve = true;
		armedTileIndex = tileIndex;
		armedMouseX = mouseX;

		if (dispatchedStepIndex != stepIndex || atDispatch == null)
		{
			armed = false;
			armedStepIndex = dispatchedStepIndex;
			lastOutcome = "stale click: dispatched step " + (dispatchedStepIndex + 1)
				+ ", current step " + getCurrentStep().getNumber() + " - not armed";
			return;
		}

		armed = true;
		armedStepIndex = dispatchedStepIndex;
		armedAt = atDispatch;
		pendingTicks = 0;
		// Condition 1 of the forward-transition rule is now satisfiable for
		// this step (the stale path above deliberately does not clear it: a
		// click for a different step must not erase this step's dispatch fact).
		dispatchedCurrentStep = true;
		lastOutcome = "armed: step " + getCurrentStep().getNumber() + ", zone tile #"
			+ (tileIndex + 1) + "/" + getCurrentStep().getTiles().size()
			+ " (mouseX=" + mouseX + ")";
	}

	/**
	 * A click was refused at click time (a 1:1 gate or the world layer; the
	 * plugin consumed it and nothing was dispatched). Records the refusal
	 * WITHOUT touching the armed state: the refusal belongs to a DIFFERENT
	 * click than any dispatched one still awaiting movement - clobbering it
	 * would silently discard that click's bounded observation.
	 */
	public void refuseClick(String reason)
	{
		clicksRefused++;
		lastOutcome = "refused: " + reason
			+ (armed ? " (step " + (armedStepIndex + 1) + " click still pending)" : "");
	}

	// ----- resets -----

	/** Natural reset trigger (login screen, plugin shutdown). Drops everything. */
	public void reset(String reason)
	{
		armed = false;
		resetToFirstStep(reason);
	}

	private void resetToFirstStep(String reason)
	{
		stepIndex = 0;
		synced = false;
		dispatchedCurrentStep = false;
		armedAt = null;
		pendingTicks = 0;
		lastOutcome = "reset: " + reason;
	}

	/**
	 * Session-stats reset (login). Called alongside {@link #reset(String)} on
	 * the login screen; mid-session resets keep the counters.
	 */
	public void clearStats()
	{
		laps = 0;
		clicksDispatched = 0;
		clicksRefused = 0;
		stepsConfirmed = 0;
		clicksAborted = 0;
		planeResets = 0;
	}

	// ----- accessors (overlay / plugin) -----

	public AgilityStep getCurrentStep()
	{
		return course.step(stepIndex);
	}

	public int getStepIndex()
	{
		return stepIndex;
	}

	public AgilityCourse getCourse()
	{
		return course;
	}

	public boolean isArmed()
	{
		return armed;
	}

	/** True once any observation has placed the player on a step's tiles. */
	public boolean isSynced()
	{
		return synced;
	}

	public String getLastOutcome()
	{
		return lastOutcome;
	}

	public int getLastTileIndex()
	{
		return armedTileIndex;
	}

	public int getLastMouseX()
	{
		return armedMouseX;
	}

	public int getLaps()
	{
		return laps;
	}

	public int getClicksDispatched()
	{
		return clicksDispatched;
	}

	public int getClicksRefused()
	{
		return clicksRefused;
	}

	public int getStepsConfirmed()
	{
		return stepsConfirmed;
	}

	public int getClicksAborted()
	{
		return clicksAborted;
	}

	public int getPlaneResets()
	{
		return planeResets;
	}
}
