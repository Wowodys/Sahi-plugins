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
 * The whole state machine of a one-click cycle. There is deliberately nothing
 * else: a bot has schedulers and loops; this has exactly two states - one
 * click in, one ordinary game action out, then the framework only OBSERVES
 * the server's reaction.
 *
 * IDLE   - waiting for the player's single real click. Nothing runs.
 * ARMED  - the click happened (the client dispatched the entry's own real
 *          action natively on it); the framework now reacts to game events
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
