package com.lootdeck.tcg.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import org.junit.Test;

/**
 * CombatState only touches Client#getLocalPlayer / Client#getTickCount and Player#getInteracting,
 * so we stub those interfaces with a tiny reflection Proxy instead of pulling in Mockito (which is
 * not on this module's test classpath).
 */
public class CombatStateTest
{
	/** Records stubbed return values by method name; returns type-appropriate defaults otherwise. */
	private static final class Stub implements InvocationHandler
	{
		final Map<String, Object> values = new HashMap<>();

		void set(String method, Object value)
		{
			values.put(method, value);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args)
		{
			String name = method.getName();
			if (values.containsKey(name))
			{
				return values.get(name);
			}
			if ("equals".equals(name))
			{
				return proxy == args[0];
			}
			if ("hashCode".equals(name))
			{
				return System.identityHashCode(proxy);
			}
			if ("toString".equals(name))
			{
				return "stub";
			}
			Class<?> rt = method.getReturnType();
			if (rt == int.class || rt == short.class || rt == byte.class)
			{
				return 0;
			}
			if (rt == long.class)
			{
				return 0L;
			}
			if (rt == boolean.class)
			{
				return false;
			}
			if (rt == double.class)
			{
				return 0d;
			}
			if (rt == float.class)
			{
				return 0f;
			}
			if (rt == char.class)
			{
				return (char) 0;
			}
			return null;
		}
	}

	private static <T> T proxy(Class<T> iface, InvocationHandler h)
	{
		return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
	}

	@Test
	public void freshStateIsNotInCombat()
	{
		Stub clientStub = new Stub();
		clientStub.set("getTickCount", 0);
		Client client = proxy(Client.class, clientStub);

		CombatState cs = new CombatState(client);
		assertFalse(cs.isInCombat());
	}

	@Test
	public void hitsplatOnLocalPlayerDecaysAfterTenTicks()
	{
		Stub clientStub = new Stub();
		Player local = proxy(Player.class, new Stub());
		clientStub.set("getLocalPlayer", local);
		Client client = proxy(Client.class, clientStub);
		CombatState cs = new CombatState(client);

		// Hit lands on the local player at tick 100.
		clientStub.set("getTickCount", 100);
		HitsplatApplied hit = new HitsplatApplied();
		hit.setActor(local);
		cs.onHitsplatApplied(hit);

		// 5 ticks later → still in combat.
		clientStub.set("getTickCount", 105);
		assertTrue(cs.isInCombat());

		// 11 ticks later → window elapsed.
		clientStub.set("getTickCount", 111);
		assertFalse(cs.isInCombat());
	}

	@Test
	public void resetClearsCombatImmediately()
	{
		Stub clientStub = new Stub();
		Player local = proxy(Player.class, new Stub());
		clientStub.set("getLocalPlayer", local);
		Client client = proxy(Client.class, clientStub);
		CombatState cs = new CombatState(client);

		clientStub.set("getTickCount", 100);
		HitsplatApplied hit = new HitsplatApplied();
		hit.setActor(local);
		cs.onHitsplatApplied(hit);
		assertTrue(cs.isInCombat());

		cs.reset();
		assertFalse(cs.isInCombat());
	}

	@Test
	public void interactionAloneIsNotCombat()
	{
		Stub clientStub = new Stub();
		Player local = proxy(Player.class, new Stub());
		NPC npc = proxy(NPC.class, new Stub());
		clientStub.set("getLocalPlayer", local);
		clientStub.set("getTickCount", 50);
		Client client = proxy(Client.class, clientStub);
		CombatState cs = new CombatState(client);

		// An NPC interaction with no recent hitsplat (following / being followed) is NOT combat (L5).
		cs.onInteractingChanged(new InteractingChanged(npc, local));
		assertFalse(cs.isInCombat());
	}

	@Test
	public void hitsplatThenInteractionCountsAsCombat()
	{
		Stub clientStub = new Stub();
		Player local = proxy(Player.class, new Stub());
		NPC npc = proxy(NPC.class, new Stub());
		clientStub.set("getLocalPlayer", local);
		Client client = proxy(Client.class, clientStub);
		CombatState cs = new CombatState(client);

		// A hitsplat lands on us at tick 50...
		clientStub.set("getTickCount", 50);
		HitsplatApplied hit = new HitsplatApplied();
		hit.setActor(local);
		cs.onHitsplatApplied(hit);
		// ...and an NPC interaction within 25 ticks confirms combat.
		clientStub.set("getTickCount", 60);
		cs.onInteractingChanged(new InteractingChanged(npc, local));
		assertTrue(cs.isInCombat());
	}
}
