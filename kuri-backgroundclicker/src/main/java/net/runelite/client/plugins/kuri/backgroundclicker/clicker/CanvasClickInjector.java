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
package net.runelite.client.plugins.kuri.backgroundclicker.clicker;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;

/**
 * Constructs and dispatches the synthetic AWT click pair onto the game
 * canvas.
 *
 * Why Canvas.dispatchEvent() is the correct entry point, and why it works
 * unfocused (verified against the vendored runelite source in this
 * repository):
 *
 *   1. The official client registers its own java.awt MouseListener on the
 *      canvas. Component.dispatchEvent() feeds an event to those listeners
 *      DIRECTLY - it bypasses the native event queue, which is the only
 *      place Windows applies window-focus routing. No field anywhere in the
 *      chain consults focus for mouse events (ClientUI's isFocused() gate
 *      covers window-level KEY dispatch only, ClientUI#dispatchWindowKeyEvent).
 *   2. The client's listener forwards the event through the Callbacks
 *      contract (net.runelite.api.hooks.Callbacks#mousePressed - "If this
 *      event will be consumed it will not be propagated further to client")
 *      into RuneLite's MouseManager, where plugins may consume it. Our
 *      events are constructed fresh and unconsumed, so they pass through.
 *   3. Unconsumed, the client records the click into its own mouse state at
 *      the event's canvas-local coordinates. The game loop (client thread)
 *      processes that state on the next cycle: hover/menu logic, then
 *      MenuOptionClicked on the event bus - which is exactly what the 1:1
 *      location plugin listens for. Both plugins meet at the vanilla input
 *      pipeline and nowhere else.
 *
 * Event shape: MOUSE_PRESSED (button down, button's down-mask in
 * modifiersEx) followed by MOUSE_RELEASED (mask cleared) at the SAME canvas
 * point, clickCount 1. No MOUSE_CLICKED: the client acts on the pressed
 * latch, and the pair is what a real AWT click sequence reduces to. The
 * dispatcher runs the pair inside ONE runnable on the EDT, so no paint or
 * game-cycle can interleave between press and release.
 */
public final class CanvasClickInjector
{
	private CanvasClickInjector()
	{
	}

	/**
	 * Dispatches one complete click (press + release) at the canvas-local
	 * point.
	 *
	 * COORDINATE CONTRACT (verified against MouseEvent's spec and the client
	 * input chain): {@code x}/{@code y} are COMPONENT-RELATIVE - relative to
	 * the canvas origin (0,0), strictly within [0, canvas.getWidth()) x
	 * [0, canvas.getHeight()). The mapped safe-zone point already IS canvas
	 * space (SafeZoneMapper emits zoneOffset + zone-space values, clamped
	 * against the live canvas dims), so NO screen-to-component translation
	 * applies here: subtracting canvas.getLocationOnScreen() would corrupt a
	 * component-relative point into a negative off-canvas one. (Screen-space
	 * anchors never enter this path: the physical cursor position is consumed
	 * by SafeZoneMapper only as a normalization domain, and no consumer in
	 * the client's input chain reads getXOnScreen/getLocationOnScreen.)
	 *
	 * What this method DOES guard: it clamps the point into the live canvas
	 * bounds so a stale zone config can never produce an off-canvas event,
	 * and it fails fast if called off the EDT.
	 *
	 * MUST be called on the Event Dispatch Thread - the caller owns the hop
	 * (see BackgroundClickerPlugin#forward, which wraps its guards and this
	 * call in EventQueue.invokeAndWait). AWT listener code assumes EDT
	 * serialization; running it on a foreign thread would race the client's
	 * real event handling and the overlay renderer.
	 *
	 * @param canvas the live game canvas (Client#getCanvas; re-read by the
	 *               caller on the EDT so the source is the current canvas)
	 * @param button MouseEvent.BUTTON1 or MouseEvent.BUTTON3
	 * @param pressModifiersEx the button's InputEvent.*_DOWN_MASK
	 * @param x canvas-local X (clamped to [0, canvas.getWidth()))
	 * @param y canvas-local Y (clamped to [0, canvas.getHeight()))
	 */
	public static void dispatchClick(Canvas canvas, int button, int pressModifiersEx, int x, int y)
	{
		// Fail fast, not race silently: AWT listener code assumes EDT
		// serialization, so a foreign-thread call would corrupt the client's
		// real event handling. The caller owns the hop
		// (BackgroundClickerPlugin#forward wraps its guards and this call in
		// EventQueue.invokeAndWait); a miss is a programming error.
		if (!EventQueue.isDispatchThread())
		{
			throw new IllegalStateException(
				"dispatchClick must run on the EDT - hop with EventQueue.invokeAndWait");
		}

		// Strict component-relative bounds: the mapped point targets the
		// canvas surface; clamp insurance against a zone config drifting out
		// of sync with a resizing canvas (stretched mode, window resize).
		int clampedX = Math.max(0, Math.min(x, canvas.getWidth() - 1));
		int clampedY = Math.max(0, Math.min(y, canvas.getHeight() - 1));

		long now = System.currentTimeMillis();

		MouseEvent pressed = new MouseEvent(canvas,
			MouseEvent.MOUSE_PRESSED,
			now,
			pressModifiersEx,
			clampedX, clampedY,
			1, false,
			button);
		canvas.dispatchEvent(pressed);

		// Release carries no down-mask - that is what a real release looks
		// like to listeners checking getModifiersEx().
		MouseEvent released = new MouseEvent(canvas,
			MouseEvent.MOUSE_RELEASED,
			System.currentTimeMillis(),
			0,
			clampedX, clampedY,
			1, false,
			button);
		canvas.dispatchEvent(released);
	}
}
