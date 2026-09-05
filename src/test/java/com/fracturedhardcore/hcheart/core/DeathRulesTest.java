package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fracturedhardcore.hcheart.core.DeathRules.Outcome;
import org.junit.jupiter.api.Test;

class DeathRulesTest {
	@Test void healthyPlayerGetsDowned() {
		assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(0, 0), false));
		assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(2, 0), false));
	}
	@Test void bypassDamageIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 0), true)); }
	@Test void finalLifeIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(3, 0), false)); }
	@Test void alreadyDownedIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 500), false)); }
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
}
