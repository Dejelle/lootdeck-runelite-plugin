package com.lootdeck.tcg.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import com.lootdeck.tcg.TcgConfig;
import java.lang.reflect.Proxy;
import org.junit.Test;

/** Guards the API-base https lockdown (audit L4): a http:// base must never ship the token. */
public class TcgApiClientTest
{
	private static TcgConfig config()
	{
		return (TcgConfig) Proxy.newProxyInstance(TcgConfig.class.getClassLoader(),
			new Class<?>[]{TcgConfig.class}, (proxy, method, args) ->
			{
				if ("token".equals(method.getName()))
				{
					return "tok";
				}
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class)
				{
					return Boolean.TRUE;
				}
				if (rt == String.class)
				{
					return "";
				}
				if (rt == int.class)
				{
					return 0;
				}
				return null;
			});
	}

	private static TcgApiClient client(String apiBase)
	{
		// The 4-arg constructor is the package-private test seam that injects the base directly
		// (the base is no longer a config item, so we can't go through TcgConfig any more).
		return new TcgApiClient(new okhttp3.OkHttpClient(), new com.google.gson.Gson(), config(), apiBase);
	}

	@Test
	public void httpLocalhostPassesTheGuard()
	{
		// http://localhost is allowed for local dev — the guard must NOT fire; the call instead
		// fails at the network layer (nothing listening on port 1).
		try
		{
			client("http://localhost:1").listPending();
			fail("expected a network failure");
		}
		catch (ApiException e)
		{
			assertNotEquals("insecure_api_base", e.getMessage());
			assertEquals(0, e.getCode()); // network error, not the guard
		}
	}

	@Test
	public void httpNonLocalhostIsRejected()
	{
		// The classic bypass: http://localhost.evil.com is NOT localhost — the guard must fire
		// before any request (and before the token is attached).
		try
		{
			client("http://localhost.evil.com").listPending();
			fail("expected the insecure_api_base guard to fire");
		}
		catch (ApiException e)
		{
			assertEquals("insecure_api_base", e.getMessage());
			assertEquals(400, e.getCode());
		}
	}

	@Test
	public void httpsPassesTheGuard()
	{
		// https always passes the guard regardless of host; failure comes from the network layer.
		try
		{
			client("https://127.0.0.1:1").listPending();
			fail("expected a network failure");
		}
		catch (ApiException e)
		{
			assertNotEquals("insecure_api_base", e.getMessage());
		}
	}
}
