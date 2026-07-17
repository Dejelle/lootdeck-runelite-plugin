package com.lootdeck.tcg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;

/**
 * Exercises the plugin's own session state machine — the layer the audit showed had zero coverage.
 * TcgPlugin's collaborators are @Inject private fields; we set them by reflection and drive the
 * @Subscribe handlers directly. No Mockito: interfaces are faked with java.lang.reflect.Proxy.
 */
public class TcgPluginSessionTest
{
	private static void set(Object target, String field, Object value) throws Exception
	{
		java.lang.reflect.Field f = TcgPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object defaultFor(Class<?> rt)
	{
		if (rt == boolean.class) return Boolean.FALSE;
		if (rt == char.class) return (char) 0;
		if (rt == byte.class) return (byte) 0;
		if (rt == short.class) return (short) 0;
		if (rt == int.class) return 0;
		if (rt == long.class) return 0L;
		if (rt == float.class) return 0f;
		if (rt == double.class) return 0d;
		return null;
	}

	/** Fake Client: LOGGED_IN, accountHash 123, no local player, empty world-type set. */
	private static Client fakeClient()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getAccountHash": return 123L;
					case "getGameState": return GameState.LOGGED_IN;
					case "getLocalPlayer": return null;
					// RuneScapeProfileType.getCurrent(client) iterates this — must be non-null.
					case "getWorldType": return java.util.EnumSet.noneOf(WorldType.class);
					default: return defaultFor(method.getReturnType());
				}
			});
	}

	private static TcgConfig config(String token)
	{
		return (TcgConfig) Proxy.newProxyInstance(TcgConfig.class.getClassLoader(),
			new Class<?>[]{TcgConfig.class}, (proxy, method, args) ->
			{
				if ("token".equals(method.getName())) return token;
				if (method.getReturnType() == boolean.class) return Boolean.TRUE;
				if (method.getReturnType() == String.class) return "";
				return defaultFor(method.getReturnType());
			});
	}

	/** Build a plugin whose onGameStateChanged path is fully wired with fakes. */
	private static TcgPlugin plugin(Client client, com.lootdeck.tcg.activity.ActivityDetector det,
		String token) throws Exception
	{
		TcgPlugin p = new TcgPlugin();
		set(p, "client", client);
		set(p, "config", config(token));
		set(p, "detector", det);
		set(p, "combatState", new com.lootdeck.tcg.activity.CombatState(client));
		set(p, "groundPacks", new com.lootdeck.tcg.world.GroundPackManager(client, null));
		set(p, "panel", new com.lootdeck.tcg.ui.TcgPanel());
		// A real (daemon) pool so a token-bearing login's async refreshLinkState/refreshPanel
		// don't NPE on a null net. With api == null those tasks fail internally and are swallowed;
		// the synchronous session state (what these tests assert) is already set by then.
		set(p, "net", java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "test-net");
			t.setDaemon(true);
			return t;
		}));
		return p;
	}

	private static GameStateChanged state(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}

	private static ChatMessage chat(ChatMessageType type, String message)
	{
		ChatMessage e = new ChatMessage();
		e.setType(type);
		e.setMessage(message);
		return e;
	}

	@Test
	public void detectorSurvivesSceneLoad() throws Exception
	{
		Client client = fakeClient();
		com.lootdeck.tcg.activity.ActivityDetector det =
			new com.lootdeck.tcg.activity.ActivityDetector(client);
		TcgPlugin p = plugin(client, det, ""); // empty token → login block skips network paths

		p.onGameStateChanged(state(GameState.LOGGED_IN));       // real login
		det.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Tempoross has been subdued."));
		p.onGameStateChanged(state(GameState.LOADING));          // leaving the instance...
		p.onGameStateChanged(state(GameState.LOGGED_IN));        // ...scene load, NOT a login
		// THE audit-C1 assertion: the staged minigame survived the scene load.
		assertNotNull(det.flushMinigame(false));
	}

	@Test
	public void sessionEndResetsDetector() throws Exception
	{
		Client client = fakeClient();
		com.lootdeck.tcg.activity.ActivityDetector det =
			new com.lootdeck.tcg.activity.ActivityDetector(client);
		TcgPlugin p = plugin(client, det, "");
		p.onGameStateChanged(state(GameState.LOGGED_IN));
		det.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Tempoross has been subdued."));
		p.onGameStateChanged(state(GameState.LOGIN_SCREEN));     // session ends → reset
		assertNull(det.flushMinigame(false));
	}

	@Test
	public void publicChatNeverClassifies() throws Exception
	{
		Client client = fakeClient();
		final boolean[] reached = {false};
		com.lootdeck.tcg.activity.ActivityDetector recording =
			new com.lootdeck.tcg.activity.ActivityDetector(client)
			{
				@Override
				public com.lootdeck.tcg.activity.ActivityType onChatMessage(ChatMessage e)
				{
					reached[0] = true;
					return null;
				}
			};
		TcgPlugin p = plugin(client, recording, "tok");          // token set → canReport() true
		set(p, "loggedIn", true);
		set(p, "accountHash", "123");
		p.onChatMessage(chat(ChatMessageType.PUBLICCHAT, "Tempoross has been subdued."));
		assertFalse("public chat must be filtered before the detector", reached[0]);
		p.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Tempoross has been subdued."));
		assertTrue(reached[0]);
	}

	@Test
	public void startupWhileLoggedInSeedsSession() throws Exception
	{
		Client client = fakeClient(); // getGameState() already returns LOGGED_IN
		TcgPlugin p = plugin(client, new com.lootdeck.tcg.activity.ActivityDetector(client), "tok");
		assertFalse(p.canReport());
		p.seedSessionFromCurrentState();
		assertTrue(p.canReport()); // loggedIn + token + STANDARD + hash — all seeded
	}
}
