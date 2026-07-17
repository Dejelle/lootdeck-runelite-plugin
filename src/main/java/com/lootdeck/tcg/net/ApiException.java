package com.lootdeck.tcg.net;

/** Typed API failure. The EventQueue's retry policy keys off classify() (audit M1/M2). */
public class ApiException extends Exception
{
	/** How the EventQueue must treat this failure. */
	public enum FailureClass
	{
		/** Network error, 408, 429, 5xx — retry with backoff forever (idempotency key protects us). */
		TRANSIENT,
		/** Any other 4xx — the report is bad or unauthorized; retrying can never succeed. Drop it. */
		PERMANENT,
		/** 2xx with an unparseable body — the server already granted; retry a few times, then drop. */
		MALFORMED_SUCCESS
	}

	private final int code;
	private final FailureClass failureClass;
	private final int retryAfterSeconds; // from a 429 Retry-After header; 0 = absent

	public ApiException(int code, String message)
	{
		this(code, message, 0);
	}

	public ApiException(int code, String message, int retryAfterSeconds)
	{
		super(message);
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
		this.failureClass = classifyCode(code);
	}

	/** Network-level failure (no HTTP status). */
	public ApiException(String message, Throwable cause)
	{
		super(message, cause);
		this.code = 0;
		this.retryAfterSeconds = 0;
		this.failureClass = FailureClass.TRANSIENT;
	}

	private ApiException(String message, Throwable cause, FailureClass failureClass)
	{
		super(message, cause);
		this.code = 0;
		this.retryAfterSeconds = 0;
		this.failureClass = failureClass;
	}

	/** A 2xx whose body didn't parse — previously indistinguishable from a network error (M2). */
	public static ApiException malformedSuccess(String message, Throwable cause)
	{
		return new ApiException(message, cause, FailureClass.MALFORMED_SUCCESS);
	}

	private static FailureClass classifyCode(int code)
	{
		if (code == 0 || code == 408 || code == 429 || code >= 500)
		{
			return FailureClass.TRANSIENT;
		}
		return FailureClass.PERMANENT;
	}

	public int getCode()
	{
		return code;
	}

	public int getRetryAfterSeconds()
	{
		return retryAfterSeconds;
	}

	public FailureClass classify()
	{
		return failureClass;
	}

	/** Legacy convenience for call sites that only branch on "can this ever succeed". */
	public boolean isRetryable()
	{
		return failureClass != FailureClass.PERMANENT;
	}
}
