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
 * The verdict of one confirmation check. Actions never "retry" - they report
 * one of three things per GameTick and the engine decides:
 *
 * PENDING   - the server has not yet confirmed or denied the step; observing
 *             another tick is legitimate (checking is observation).
 * CONFIRMED - the game itself confirmed the step (position change, spawn,
 *             widget, varbit, chat). Terminal - the cycle ends here.
 * ABORTED   - anomaly or denial. Terminal - reset, never retry ("abort on
 *             anomaly is safer and simpler than retry").
 */
public final class ConfirmResult
{
	public enum Type
	{
		PENDING,
		CONFIRMED,
		ABORTED
	}

	private static final ConfirmResult PENDING_INSTANCE =
		new ConfirmResult(Type.PENDING, "");

	private final Type type;
	private final String reason;

	private ConfirmResult(Type type, String reason)
	{
		this.type = type;
		this.reason = reason;
	}

	public static ConfirmResult pending()
	{
		return PENDING_INSTANCE;
	}

	public static ConfirmResult confirmed(String reason)
	{
		return new ConfirmResult(Type.CONFIRMED, reason);
	}

	public static ConfirmResult aborted(String reason)
	{
		return new ConfirmResult(Type.ABORTED, reason);
	}

	public Type getType()
	{
		return type;
	}

	public String getReason()
	{
		return reason;
	}

	public boolean isPending()
	{
		return type == Type.PENDING;
	}

	public boolean isConfirmed()
	{
		return type == Type.CONFIRMED;
	}

	public boolean isAborted()
	{
		return type == Type.ABORTED;
	}
}
