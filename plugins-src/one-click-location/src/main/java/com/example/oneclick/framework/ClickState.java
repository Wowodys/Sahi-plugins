package com.example.oneclick.framework;

/**
 * The whole state machine of a one-click cycle. There is deliberately nothing
 * else: a bot has schedulers and loops; this has exactly three states
 * (docs/plugin-development/01-design-philosophy.md rule 1 - one click, one
 * bounded sequence).
 *
 * IDLE   - waiting for the player's single real click. Nothing runs.
 * ARMED  - the click happened; the framework is now REACTING to game events
 *          only, evaluating server confirmations per GameTick until the
 *          action reaches a terminal state or a reset trigger fires.
 *
 * Terminal results (CONFIRMED / ABORTED) are surfaced, then the state falls
 * back to IDLE on the same tick - a cycle can never outlive its
 * confirmation horizon, and there is no retry path anywhere.
 */
public enum ClickState
{
	IDLE,
	ARMED
}
