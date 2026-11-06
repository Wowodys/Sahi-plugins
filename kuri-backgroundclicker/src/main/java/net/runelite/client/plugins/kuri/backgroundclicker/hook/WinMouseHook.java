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
package net.runelite.client.plugins.kuri.backgroundclicker.hook;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WH_MOUSE_LL low-level mouse hook (Windows), via the JNA copy the client
 * already ships (jna-platform 5.9, an `api` dependency of runelite-client).
 *
 * SAFETY INVARIANT - injected-event rejection lives HERE, in the OS callback,
 * unconditionally: any event the OS flags with LLMHF_INJECTED is dropped
 * before the forwarder ever sees it. This module dispatches synthetic events
 * through AWT only (Canvas.dispatchEvent) and never calls SendInput, so its
 * own events cannot appear in the hook stream; the filter exists so FOREIGN
 * injected streams (other automation, screen recorders, accessibility tools)
 * can never bounce through this pipeline. It is deliberately not
 * configurable - it is the infinite-loop guard, not a preference.
 *
 * Threading contract - this is the part that must not drift:
 *
 *   - The hook is installed on a dedicated daemon thread that then runs a
 *     GetMessage pump. Windows delivers low-level hook callbacks on the
 *     thread that installed the hook, and that thread MUST pump messages or
 *     the whole system's cursor latency degrades until Windows silently
 *     evicts the hook.
 *   - The callback runs on that pump thread. It must return in low single
 *     digit microseconds: it only reads the {@link WinUser.MSLLHOOKSTRUCT}
 *     and calls the forwarder, which must hand off without blocking
 *     (SynchronousQueue#offer or equivalent). Never dispatch AWT work, take
 *     locks, or log at INFO from inside the callback - Windows removes
 *     hooks whose callbacks stall (LowLevelHooksTimeout) and the desktop
 *     feels it first.
 *   - Returning a non-zero LRESULT swallows the click system-wide (it never
 *     reaches the focused application). Returning {@link #next(int,
 *     WinDef.WPARAM, WinUser.MSLLHOOKSTRUCT)} lets it propagate normally.
 *
 * The {@link WinUser.LowLevelMouseProc} instance is held in a final field on
 * purpose: JNA callbacks are native-callable only while reachable. If the
 * only reference were a local variable, GC could collect it while the OS
 * still holds its trampoline - that is a hard JVM crash, not an exception.
 *
 * Shutdown ordering (why {@link #stop()} cannot simply post WM_QUIT): a
 * thread's message queue does not exist until the thread first touches a
 * message API, and PostThreadMessage to a queue-less thread fails with
 * ERROR_INVALID_THREAD_ID - the WM_QUIT would be silently lost and the pump
 * would sit in GetMessage forever. The pump therefore creates its queue
 * explicitly (PeekMessage with PM_NOREMOVE: returns immediately, but the
 * queue now exists) BEFORE publishing its thread id, and only then counts
 * down {@link #queueReady}; stop() awaits that latch, so every
 * PostThreadMessage it issues is guaranteed a live queue.
 *
 * JNA 5.9 exports WM_QUIT but not the WM_LBUTTON* / PM_* constants, so those
 * are declared here (values are fixed by WinUser.h).
 */
public final class WinMouseHook
{
	private static final Logger log = LoggerFactory.getLogger(WinMouseHook.class);

	/**
	 * The two things a hook callback does with a hardware button transition:
	 * SWALLOW stops the click system-wide (it never reaches the focused
	 * application); PASS lets it propagate normally - the game only sees it
	 * because the forwarder also dispatched a synthetic canvas event.
	 */
	public interface Forwarder
	{
		/**
		 * Called on the hook pump thread for every NON-INJECTED hardware
		 * left/right button transition system-wide. Must not block.
		 *
		 * @param pressed true = button down, false = button up
		 * @param leftButton true = left button, false = right button
		 * @param screenX virtual-screen X of the cursor (can be negative on
		 *                 multi-monitor layouts)
		 * @param screenY virtual-screen Y of the cursor
		 * @return true to swallow the click system-wide (redirect mode),
		 *         false to let it propagate (mirror mode)
		 */
		boolean onOsMouseButton(boolean pressed, boolean leftButton, int screenX, int screenY);
	}

	private static final int WM_LBUTTONDOWN = 0x0201;
	private static final int WM_LBUTTONUP = 0x0202;
	private static final int WM_RBUTTONDOWN = 0x0204;
	private static final int WM_RBUTTONUP = 0x0205;
	private static final int LLMHF_INJECTED = 0x0001;
	private static final int PM_NOREMOVE = 0x0000;

	/** How long stop() tolerates a pump that never became message-ready. */
	private static final long STOP_TIMEOUT_MS = 2000;

	private final Forwarder forwarder;

	/**
	 * Strong reference - see class javadoc. Never make this local.
	 */
	private final WinUser.LowLevelMouseProc hookProc = this::callback;

	/**
	 * Counts down once the pump thread has created its message queue and
	 * published {@link #hookThreadId} - the point from which a posted
	 * WM_QUIT is guaranteed to be deliverable. See the shutdown-ordering
	 * note in the class javadoc.
	 */
	private final CountDownLatch queueReady = new CountDownLatch(1);

	private volatile WinUser.HHOOK hookHandle;
	private volatile int hookThreadId = -1;
	private volatile boolean installed;
	private volatile String installError;
	private Thread pumpThread;

	public WinMouseHook(Forwarder forwarder)
	{
		this.forwarder = forwarder;
	}

	/**
	 * Spawns the daemon pump thread and installs the hook on it. Cheap and
	 * asynchronous; poll {@link #isInstalled()} / {@link #getInstallError()}
	 * (both read by the status overlay) for the outcome.
	 */
	public void start()
	{
		if (pumpThread != null)
		{
			return;
		}
		installError = null;
		pumpThread = new Thread(this::pump, "kuri-bgclicker-os-hook");
		pumpThread.setDaemon(true);
		pumpThread.start();
	}

	/**
	 * Unhooks first (no further callbacks reach the forwarder), then breaks
	 * the pump's GetMessage wait with WM_QUIT, then joins. Safe to call from
	 * any thread except the pump itself.
	 */
	public void stop() throws InterruptedException
	{
		installed = false;
		unhookCurrent();

		// The pump creates its queue before publishing its thread id, so a
		// short await covers the startup race. If it never becomes ready
		// (install blew up before reaching the queue call), PostThreadMessage
		// below fails and the join confirms the thread is gone.
		queueReady.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

		int tid = hookThreadId;
		hookThreadId = -1;
		// JNA maps PostThreadMessage's BOOL to int; 0 = failure (queue gone -
		// pump already dead on the failed-install path; the join below is the
		// confirmation, not this return value).
		if (tid != -1
			&& User32.INSTANCE.PostThreadMessage(tid, WinUser.WM_QUIT,
				new WinDef.WPARAM(0), new WinDef.LPARAM(0)) == 0)
		{
			// Pump already gone (failed install path) - the join below is
			// the confirmation, not this return value.
			log.debug("PostThreadMessage(WM_QUIT) failed for pump thread {}", tid);
		}

		Thread thread = pumpThread;
		pumpThread = null;
		if (thread != null)
		{
			thread.join(STOP_TIMEOUT_MS);
			if (thread.isAlive())
			{
				log.warn("WH_MOUSE_LL pump thread did not exit within {}ms - it is a daemon"
					+ " and will not block JVM exit", STOP_TIMEOUT_MS);
			}
		}
	}

	/**
	 * Reads the handle once, unhooks, then nulls the field. stop() is the
	 * only caller, so no other writer can interleave; the callback's
	 * CallNextHookEx path tolerates a null handle (Windows treats it as
	 * "this hook is being removed" and passes through).
	 */
	private void unhookCurrent()
	{
		WinUser.HHOOK current = hookHandle;
		if (current == null)
		{
			// already stopped, or start() failed - nothing to do
			return;
		}
		hookHandle = null;
		User32.INSTANCE.UnhookWindowsHookEx(current);
	}

	public boolean isInstalled()
	{
		return installed;
	}

	public String getInstallError()
	{
		return installError;
	}

	/**
	 * Runs on the pump thread: create the message queue, publish the thread
	 * id, install, then pump until WM_QUIT. GetMessage returns 0 for WM_QUIT
	 * (clean exit), -1 on error (treated as fatal for the hook - the overlay
	 * flips to "hook error").
	 */
	private void pump()
	{
		try
		{
			runPump();
		}
		finally
		{
			// Failure paths that exit before the explicit count-down below
			// must still release stop(). Double count-down is a no-op.
			queueReady.countDown();
		}
	}

	private void runPump()
	{
		// Queue creation BEFORE the thread id becomes visible: PeekMessage
		// with PM_NOREMOVE returns immediately but guarantees the queue
		// exists, so a PostThreadMessage that observes hookThreadId can never
		// fail with ERROR_INVALID_THREAD_ID (class javadoc: shutdown order).
		User32.INSTANCE.PeekMessage(new WinUser.MSG(), null, 0, 0, PM_NOREMOVE);
		hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
		queueReady.countDown();

		WinUser.HHOOK handle;
		try
		{
			handle = User32.INSTANCE.SetWindowsHookEx(
				WinUser.WH_MOUSE_LL, hookProc, null, 0);
		}
		catch (Throwable ex)
		{
			// Non-Windows platform (no user32) or blocked JNA native access:
			// surface it on the status overlay instead of dying quietly.
			installError = "SetWindowsHookEx unavailable: " + ex.getClass().getSimpleName()
				+ (ex.getMessage() != null ? ": " + ex.getMessage() : "");
			log.error(installError);
			return;
		}
		if (handle == null)
		{
			// Native.getLastError(), not a fresh Kernel32.GetLastError call:
			// JNA preserves the failing call's error per-thread, while a new
			// JNI invocation may already have clobbered it.
			installError = "SetWindowsHookEx(WH_MOUSE_LL) failed, GetLastError="
				+ Native.getLastError();
			log.error(installError);
			return;
		}
		hookHandle = handle;
		installed = true;
		log.debug("WH_MOUSE_LL installed on thread {}", hookThreadId);

		WinUser.MSG msg = new WinUser.MSG();
		while (true)
		{
			int result = User32.INSTANCE.GetMessage(msg, null, 0, 0);
			if (result <= 0)
			{
				// 0 = WM_QUIT (our clean shutdown), -1 = pump error
				break;
			}
			// No posted window messages are expected here; loop.
		}

		installed = false;
		// stop() normally unhooks before posting WM_QUIT; a fast
		// start()-then-stop() race can reach this path with the hook still
		// installed. Unhook the handle this pump installed - a second unhook
		// of an already-removed handle just returns FALSE.
		User32.INSTANCE.UnhookWindowsHookEx(handle);
		hookHandle = null;
		log.debug("WH_MOUSE_LL pump exited");
	}

	/**
	 * The OS callback (hook pump thread). Filter chain, cheapest first:
	 * nCode >= 0 (HC_ACTION), left/right button transitions only, then the
	 * injected-event guard - LLMHF_INJECTED events are dropped HERE, before
	 * the forwarder, unconditionally (class javadoc: safety invariant).
	 * Everything else - wheel, move, non-injected-range nCodes - goes to
	 * CallNextHookEx untouched.
	 */
	private WinDef.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinUser.MSLLHOOKSTRUCT info)
	{
		if (nCode >= 0 && forwarder != null)
		{
			int message = wParam.intValue();
			boolean pressed = message == WM_LBUTTONDOWN || message == WM_RBUTTONDOWN;
			boolean released = message == WM_LBUTTONUP || message == WM_RBUTTONUP;
			if ((pressed || released)
				&& (info.flags & LLMHF_INJECTED) == 0)
			{
				boolean leftButton = message == WM_LBUTTONDOWN || message == WM_LBUTTONUP;
				boolean swallow = forwarder.onOsMouseButton(
					pressed, leftButton, info.pt.x, info.pt.y);
				if (swallow)
				{
					// Non-zero return = block. The click never reaches any
					// application; it exists only as the forwarder's synthetic
					// canvas event.
					return new WinDef.LRESULT(1);
				}
			}
		}
		return next(nCode, wParam, info);
	}

	/**
	 * Pass the event along the hook chain. CallNextHookEx needs the original
	 * LPARAM (a pointer to the hook struct); JNA hands us the struct already
	 * mapped onto that memory, so its native pointer IS the original value.
	 */
	private WinDef.LRESULT next(int nCode, WinDef.WPARAM wParam, WinUser.MSLLHOOKSTRUCT info)
	{
		WinUser.HHOOK handle = hookHandle;
		return User32.INSTANCE.CallNextHookEx(
			handle, nCode, wParam, new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
	}
}
