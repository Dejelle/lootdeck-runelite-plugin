package com.lootdeck.tcg.net;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.lootdeck.tcg.TcgConfig;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the LootDeck NestJS API. All methods are blocking and MUST be called off the
 * client thread (from the plugin's ScheduledExecutorService). Never logs the token.
 */
@Singleton
public class TcgApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// Sent on every request so the API can tag genuine-plugin traffic. DETECTION SIGNAL ONLY —
	// trivially spoofable, so the server must never grant or deny based on it (IMPLEMENTATION-PLAN Phase 2).
	private static final String CLIENT_ID = "runelite-plugin";
	private static final String PLUGIN_VERSION = "1.4"; // keep in sync with build.gradle + runelite-plugin.properties version

	/** The version string sent as X-LootDeck-Plugin-Version; also stamped into feedback context. */
	public static String pluginVersion()
	{
		return PLUGIN_VERSION;
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final TcgConfig config;

	@Inject
	public TcgApiClient(OkHttpClient rlHttp, Gson gson, TcgConfig config)
	{
		// Reuse RuneLite's connection pool. Timeouts are generous because the API can be
		// slow to respond (cold/throttled hosting): a too-short read timeout turns a slow-but-
		// valid open/claim into a spurious "network_error". These run off the client thread.
		this.http = rlHttp.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(20, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.config = config;
	}

	private String base()
	{
		String b = config.apiBase();
		if (b.endsWith("/"))
		{
			b = b.substring(0, b.length() - 1);
		}
		return b;
	}

	public Dtos.TokenResp redeem(String code, String accountHash, String rsn, String profileType)
		throws ApiException
	{
		Dtos.RedeemReq req = new Dtos.RedeemReq();
		req.code = code;
		req.accountHash = accountHash;
		req.rsn = rsn;
		req.profileType = profileType;
		return post("/link/redeem", gson.toJson(req), Dtos.TokenResp.class, false);
	}

	public Dtos.DropResult reportDrop(Dtos.DropReport report) throws ApiException
	{
		return post("/drops/report", gson.toJson(report), Dtos.DropResult.class, true);
	}

	/** Per-character link status for the sidebar state machine (capped 1:N linking). */
	public Dtos.LinkStatusResp linkStatus(String accountHash) throws ApiException
	{
		return get("/plugin/link/status?accountHash=" + accountHash, Dtos.LinkStatusResp.class, true);
	}

	private volatile String cardBackUrl;

	/** Global card-back URL from the last pack-list poll (CDN .png?v=); null = use bundled. */
	public String cardBackUrl()
	{
		return cardBackUrl;
	}

	public List<Dtos.UserPack> listPacks() throws ApiException
	{
		// Plugin-scoped route (the web /packs is JWT-only and 401s for plugin tokens).
		Dtos.PacksResp resp = get("/plugin/packs", Dtos.PacksResp.class, true);
		if (resp != null)
		{
			this.cardBackUrl = resp.cardBackUrl;
		}
		return resp != null && resp.packs != null ? resp.packs : Collections.emptyList();
	}

	public Dtos.OpenResp openPack(String userPackId) throws ApiException
	{
		return post("/plugin/packs/" + userPackId + "/open", "{}", Dtos.OpenResp.class, true);
	}

	public List<Dtos.Opening> listOpenings(int limit) throws ApiException
	{
		Dtos.Opening[] arr = get("/plugin/packs/openings?limit=" + limit, Dtos.Opening[].class, true);
		return arr != null ? java.util.Arrays.asList(arr) : Collections.emptyList();
	}

	public List<Dtos.PendingPack> listPending() throws ApiException
	{
		Dtos.PendingPack[] arr = get("/packs/pending", Dtos.PendingPack[].class, true);
		return arr != null ? java.util.Arrays.asList(arr) : Collections.emptyList();
	}

	public Dtos.ClaimResp claim(String pendingId) throws ApiException
	{
		return post("/packs/pending/" + pendingId + "/claim", "{}", Dtos.ClaimResp.class, true);
	}

	/** Submit an in-client bug report / feedback message (authed with the plugin token). */
	public Dtos.FeedbackResp submitFeedback(Dtos.FeedbackReq req) throws ApiException
	{
		return post("/feedback/plugin", gson.toJson(req), Dtos.FeedbackResp.class, true);
	}

	/** Released card sets + the player's current pack-release selection. */
	public Dtos.ReleasesResp listReleases() throws ApiException
	{
		return get("/plugin/packs/releases", Dtos.ReleasesResp.class, true);
	}

	/** Choose which release NEW packs come from (null = follow the latest release). */
	public void selectRelease(String setCode) throws ApiException
	{
		post("/plugin/packs/releases/select",
			gson.toJson(new Dtos.SelectReleaseReq(setCode)), Void.class, true);
	}

	// ---- HTTP helpers ----

	private <T> T get(String path, Class<T> type, boolean authed) throws ApiException
	{
		Request.Builder rb = new Request.Builder().url(base() + path).get();
		return execute(rb, type, authed);
	}

	private <T> T post(String path, String json, Class<T> type, boolean authed) throws ApiException
	{
		Request.Builder rb = new Request.Builder()
			.url(base() + path)
			.post(RequestBody.create(JSON, json));
		return execute(rb, type, authed);
	}

	private <T> T execute(Request.Builder rb, Class<T> type, boolean authed) throws ApiException
	{
		// A phishing-supplied http:// apiBase would ship the bearer token in cleartext (L4).
		// Parse properly — a startsWith("http://localhost") check would pass http://localhost.evil.com.
		okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(base() + "/");
		if (parsed == null
			|| (!"https".equals(parsed.scheme())
				&& !"localhost".equals(parsed.host())
				&& !"127.0.0.1".equals(parsed.host())))
		{
			throw new ApiException(400, "insecure_api_base");
		}
		// Channel tag on every request (authed or not). Detection-only; never a gate.
		rb.header("X-LootDeck-Client", CLIENT_ID);
		rb.header("X-LootDeck-Plugin-Version", PLUGIN_VERSION);
		if (authed)
		{
			String token = config.token();
			if (token == null || token.isEmpty())
			{
				throw new ApiException(401, "no_token");
			}
			rb.header("Authorization", "Bearer " + token);
		}
		try (Response resp = http.newCall(rb.build()).execute())
		{
			String body = resp.body() != null ? resp.body().string() : "";
			if (!resp.isSuccessful())
			{
				int retryAfter = 0;
				if (resp.code() == 429)
				{
					try
					{
						String h = resp.header("Retry-After");
						if (h != null)
						{
							retryAfter = Integer.parseInt(h.trim());
						}
					}
					catch (NumberFormatException ignored)
					{
					}
				}
				throw new ApiException(resp.code(), parseError(body, resp.code()), retryAfter);
			}
			if (type == Void.class || body.isEmpty())
			{
				return null;
			}
			return gson.fromJson(body, type);
		}
		catch (IOException e)
		{
			throw new ApiException("network_error", e);
		}
		catch (JsonSyntaxException e)
		{
			// A 2xx whose body didn't parse — the grant already happened server-side (M2).
			throw ApiException.malformedSuccess("bad_response", e);
		}
	}

	private String parseError(String body, int code)
	{
		try
		{
			Dtos.ApiError err = gson.fromJson(body, Dtos.ApiError.class);
			if (err != null && err.error != null && err.error.message != null)
			{
				return err.error.message;
			}
		}
		catch (JsonSyntaxException ignored)
		{
			// fall through
		}
		return "http_" + code;
	}
}
