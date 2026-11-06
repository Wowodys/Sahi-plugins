package com.example.oneclick.framework;

/**
 * The verdict of one confirmation check. Actions never "retry" - they report
 * one of three things per GameTick and the engine decides:
 *
 * PENDING   - the server has not yet confirmed or denied the step; observing
 *             another tick is legitimate (rule 1: "checking is observation").
 * CONFIRMED - the game itself confirmed the step (position change, spawn,
 *             widget, varbit, chat). Terminal - the cycle ends here.
 * ABORTED   - anomaly or denial. Terminal - reset, never retry (rule 3:
 *             "abort on anomaly is safer and simpler than retry").
 */
public final class ConfirmResult
{
	public enum Type { PENDING, CONFIRMED, ABORTED }

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
