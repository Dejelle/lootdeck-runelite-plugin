package com.lootdeck.tcg.net;

/** Typed API failure. The EventQueue retries transient failures (network / 5xx). */
public class ApiException extends Exception
{
	private final int code;

	public ApiException(int code, String message)
	{
		super(message);
		this.code = code;
	}

	public ApiException(String message, Throwable cause)
	{
		super(message, cause);
		this.code = 0;
	}

	public int getCode()
	{
		return code;
	}

	/** Network error or 5xx — safe to retry (idempotency key protects against double-grant). */
	public boolean isRetryable()
	{
		return code == 0 || code >= 500;
	}
}
