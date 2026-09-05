package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerRecordTest {
	@Test void ladderIs10_8_6_4_thenFloor() {
		assertEquals(10, rec(0).maxHearts());
		assertEquals(8, rec(1).maxHearts());
		assertEquals(6, rec(2).maxHearts());
		assertEquals(4, rec(3).maxHearts());
		assertEquals(4, rec(4).maxHearts());
		assertEquals(4, rec(99).maxHearts());
		assertEquals(8.0, rec(3).maxHealth());
	}
	@Test void finalLifeAtThreeEliminatedAtFour() {
		assertFalse(rec(2).finalLife()); assertTrue(rec(3).finalLife()); assertTrue(rec(4).finalLife());
		assertFalse(rec(3).eliminated()); assertTrue(rec(4).eliminated());
	}
	@Test void restoreCostEscalatesAndNeverResets() {
		PlayerRecord r = new PlayerRecord(3, 0, 0, "a");
		assertEquals(1, r.restoreCost());
		r = r.withRestore(); assertEquals(2, r.deaths()); assertEquals(2, r.restoreCost());
		r = r.withRestore(); assertEquals(1, r.deaths()); assertEquals(3, r.restoreCost());
		r = r.withDeath().withDeath(); assertEquals(3, r.deaths()); assertEquals(3, r.restoreCost());
	}
	@Test void restoreAtZeroDeathsIsRejected() {
		assertFalse(rec(0).canRestore());
		assertThrows(IllegalStateException.class, () -> rec(0).withRestore());
	}
	@Test void heartLiftsFinalLife() { assertFalse(rec(3).withRestore().finalLife()); }
	@Test void downedTimerAgainstAbsoluteTicks() {
		PlayerRecord r = rec(0).withDownedUntil(1000);
		assertTrue(r.isDowned()); assertFalse(r.downedExpired(999)); assertTrue(r.downedExpired(1000));
		assertEquals(400, r.downedTicksRemaining(600)); assertEquals(0, r.downedTicksRemaining(5000));
		assertFalse(r.withDownedCleared().isDowned());
		assertFalse(PlayerRecord.FRESH.downedExpired(Long.MAX_VALUE));
	}
	@Test void negativeValuesRejected() {
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(-1, 0, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(0, -1, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(0, 0, -1, ""));
		assertEquals("", new PlayerRecord(0, 0, 0, null).lastKnownName());
	}
	private static PlayerRecord rec(int deaths) { return new PlayerRecord(deaths, 0, 0, "p"); }
}
