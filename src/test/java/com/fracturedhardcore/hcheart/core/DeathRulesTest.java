package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fracturedhardcore.hcheart.core.DeathRules.Outcome;
import org.junit.jupiter.api.Test;

class DeathRulesTest {
	@Test void healthyPlayerGetsDowned() {
		assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(0, 0), false, false));
		assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(2, 0), false, false));
	}
	@Test void bypassDamageIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 0), true, false)); }
	@Test void finalLifeIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(3, 0), false, false)); }
	@Test void alreadyDownedIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 500), false, false)); }
	@Test void downedBlocksOnlyNonBypassDamage() {
		assertTrue(DeathRules.blocksDamageWhileDowned(rec(0, 500), false));
		assertFalse(DeathRules.blocksDamageWhileDowned(rec(0, 500), true));
		assertFalse(DeathRules.blocksDamageWhileDowned(rec(0, 0), false));
	}
	@Test void spectatorOnlyWhenEliminated() {
		assertFalse(DeathRules.respawnsAsSpectator(rec(3, 0)));
		assertTrue(DeathRules.respawnsAsSpectator(rec(4, 0)));
	}
	@Test void rescueStrandedSpectatorsUnlessEliminated() {
		assertTrue(DeathRules.shouldRescueFromSpectator(rec(0, 0), true));
		assertTrue(DeathRules.shouldRescueFromSpectator(rec(3, 0), true));
		assertFalse(DeathRules.shouldRescueFromSpectator(rec(4, 0), true));
		assertFalse(DeathRules.shouldRescueFromSpectator(rec(0, 0), false));
	}
	private static PlayerRecord rec(int deaths, long downedUntil) { return new PlayerRecord(deaths, 0, downedUntil, "p"); }
	@Test void pendingKillIsAlwaysATrueDeathAndNeverBlocked() {
		PlayerRecord p = new PlayerRecord(1, 0, 0L, 0L, true, "p");
		assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(p, false, false));
		assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(p, true, false));
		assertFalse(DeathRules.blocksDamageWhileDowned(p, false));
		assertFalse(DeathRules.respawnsAsSpectator(p));
	}
	@Test void heldTotemFiresBeforeDownedOnAnyLife() {
		assertEquals(Outcome.TOTEM, DeathRules.onLethalDamage(rec(0, 0), false, true), "first life");
		assertEquals(Outcome.TOTEM, DeathRules.onLethalDamage(rec(2, 0), false, true), "one death from final life");
		assertEquals(Outcome.TOTEM, DeathRules.onLethalDamage(rec(3, 0), false, true), "final life too: the totem fires, the mod steps aside");
		assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 0), true, true), "bypass damage: vanilla ignores the totem, so do we");
		assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 500), true, true), "already downed");
		assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(new PlayerRecord(1, 0, 0L, 0L, true, "p"), false, true), "owed kill");
	}
}
