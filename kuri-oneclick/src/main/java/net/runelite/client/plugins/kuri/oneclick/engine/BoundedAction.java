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
 * A one-click action in the 1:1 style: the dispatch IS the user's click.
 *
 * The plugin injects a real-action menu entry (the client's own menu system,
 * real {@code MenuAction}, real target). When the player clicks it, the
 * client performs the ordinary game action exactly as if the player had
 * clicked the underlying thing themselves: one click in, one ordinary packet
 * out, the standard UI trail. The framework never dispatches anything of its
 * own and adds no second action - hence 1:1.
 *
 * Responsibilities live in three separate layers:
 *
 *   - click-time PRECONDITION checks are the plugin/world layer's job
 *     ({@code TileCoordinateValidator}); a refused click is consumed there
 *     and never reaches the engine,
 *   - this interface therefore only describes OBSERVATION: given plain
 *     location snapshots, what did the game say happened after the click,
 *   - the engine decides the transitions.
 *
 * No implementing action may dispatch anything, sleep, or retry; the only
 * legal output is a {@link ConfirmResult}.
 */
public interface BoundedAction
{
	/** Short name for overlay/abort lines ("walk to (2800, 9568) z3"). */
	String name();

	/**
	 * Server-confirmation check, evaluated once per GameTick while armed.
	 *
	 * @param now     the player's world location at this GameTick
	 * @param armedAt the player's world location at the moment of the click
	 */
	ConfirmResult confirm(PlayerSnapshot now, PlayerSnapshot armedAt);
}
