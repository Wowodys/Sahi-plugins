package com.example.oneclick.framework;

import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/**
 * A one-click action in the 1:1 style: the dispatch IS the user's click.
 *
 * The plugin injects a real-action menu entry (the client's own menu system,
 * real MenuAction, real target - rule 2). When the player clicks it, the
 * client performs the ordinary game action exactly as if the player had
 * clicked the underlying thing themselves: one click in, one ordinary packet
 * out, the standard UI trail. The framework never dispatches anything of its
 * own and adds no second action - hence 1:1.
 *
 * The framework asks the action for exactly two things:
 *
 *   canArm()  - at click time, BEFORE the click is allowed through: are the
 *               preconditions observable right now? If not, the click is
 *               consumed and the abort is surfaced (rule 6) - a real entry
 *               that would send an action for an unobserved state must never
 *               pass (gotchas §8: no speculative clicks).
 *
 *   confirm() - once per GameTick after the click: what does the GAME say
 *               happened? Server-confirmed progress only (rule 3) - position
 *               deltas, spawns, widget loads, varbits. Anomalies report
 *               ABORTED; there is no retry path in the framework.
 */
public interface BoundedAction
{
	/** Short name for overlay/abort lines ("walk to (2800, 9568) z3"). */
	String name();

	/**
	 * Click-time validation, evaluated on the client thread inside
	 * MenuOptionClicked (before the client dispatches the click's action).
	 * Return false to refuse the click entirely (it gets consumed).
	 */
	boolean canArm(Client client);

	/**
	 * Server-confirmation check, evaluated once per GameTick while armed.
	 *
	 * @param client  the client
	 * @param armedAt the player's world location at the moment of the click
	 */
	ConfirmResult confirm(Client client, WorldPoint armedAt);
}
