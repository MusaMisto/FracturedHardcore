package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fracturedhardcore.hcheart.core.ReviveRules.BreakReason;
import com.fracturedhardcore.hcheart.core.ReviveRules.Hunger;
import org.junit.jupiter.api.Test;

class ReviveRulesTest {
	@Test void drainsExactlySixPointsSpreadOverChannel() {
		assertArrayEquals(new int[] {27, 53, 80, 107, 133, 160}, ReviveRules.DRAIN_TICKS);
		int drains = 0;
		for (int t = 1; t <= Rules.REVIVE_DURATION_TICKS; t++) if (ReviveRules.drainsAt(t)) drains++;
		assertEquals(6, drains);
		assertFalse(ReviveRules.drainsAt(0));
		assertFalse(ReviveRules.drainsAt(161));
	}
	@Test void saturationDrainsBeforeFood() {
		Hunger h = new Hunger(20, 2.5f);
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(1.5f, h.saturation());
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(0.5f, h.saturation());
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(0f, h.saturation());
		h = h.drainOne(); assertEquals(19, h.food()); assertEquals(0f, h.saturation());
		assertEquals(0, new Hunger(0, 0f).drainOne().food());
	}
	@Test void entryRequiresSixFood() { assertFalse(ReviveRules.canStart(5)); assertTrue(ReviveRules.canStart(6)); }
	@Test void completionAndProgress() {
		assertFalse(ReviveRules.isComplete(159)); assertTrue(ReviveRules.isComplete(160));
		assertEquals(0, ReviveRules.progressPercent(0)); assertEquals(50, ReviveRules.progressPercent(80)); assertEquals(100, ReviveRules.progressPercent(160));
	}
	@Test void breakConditions() {
		assertEquals(BreakReason.NONE, check(1.0, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_MOVED, check(2.1 * 2.1, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.NONE, check(2.0 * 2.0, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.TOO_FAR_APART, check(0, 4.1 * 4.1, false, false, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_HURT, check(0, 0, true, false, 10, 10, true, true));
		assertEquals(BreakReason.TARGET_HURT, check(0, 0, false, true, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_STARVING, check(0, 0, false, false, 0, 10, true, true));
		assertEquals(BreakReason.TARGET_STARVING, check(0, 0, false, false, 10, 0, true, true));
		assertEquals(BreakReason.TARGET_NOT_DOWNED, check(0, 0, false, false, 10, 10, false, true));
		assertEquals(BreakReason.REVIVER_UNAVAILABLE, check(0, 0, false, false, 10, 10, true, false));
	}
	private static BreakReason check(double driftSq, double sepSq, boolean rHurt, boolean tHurt, int rFood, int tFood, boolean downed, boolean avail) {
		return ReviveRules.check(driftSq, sepSq, rHurt, tHurt, rFood, tFood, downed, avail);
	}
	@Test void notesPlayEveryEightTicksAndTwentyTimes() {
		int notes = 0;
		for (int t = 0; t <= Rules.REVIVE_DURATION_TICKS; t++) if (ReviveRules.playsNoteAt(t)) notes++;
		assertEquals(20, notes);
		assertFalse(ReviveRules.playsNoteAt(0));
		assertTrue(ReviveRules.playsNoteAt(8));
		assertTrue(ReviveRules.playsNoteAt(Rules.REVIVE_DURATION_TICKS));
		assertFalse(ReviveRules.playsNoteAt(Rules.REVIVE_DURATION_TICKS + 8));
	}
	@Test void notePitchClimbsTwoOctavesWithoutFalling() {
		float last = 0f;
		for (int t = 8; t <= Rules.REVIVE_DURATION_TICKS; t += 8) {
			float p = ReviveRules.notePitch(t);
			assertTrue(p >= 0.5f && p <= 2.0f, "note-block range");
			assertTrue(p >= last, "never falls");
			last = p;
		}
		assertEquals(2.0f, ReviveRules.notePitch(Rules.REVIVE_DURATION_TICKS), 1e-6f);
		assertTrue(ReviveRules.notePitch(8) < 0.6f);
	}
}
