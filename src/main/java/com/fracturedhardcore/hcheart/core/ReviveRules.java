package com.fracturedhardcore.hcheart.core;

/** Revive channel arithmetic and break conditions. */
public final class ReviveRules {
	public enum BreakReason { NONE, REVIVER_MOVED, TOO_FAR_APART, REVIVER_HURT, TARGET_HURT, REVIVER_STARVING, TARGET_STARVING, TARGET_NOT_DOWNED, REVIVER_UNAVAILABLE, PLAYER_LEFT }

	public record Hunger(int food, float saturation) {
		public Hunger drainOne() {
			if (saturation > 0f) return new Hunger(food, Math.max(0f, saturation - 1f));
			return new Hunger(Math.max(0, food - 1), 0f);
		}
	}

	/** 1-based elapsed ticks at which one point is drained from each participant: 27, 53, 80, 107, 133, 160. */
	public static final int[] DRAIN_TICKS = computeDrainTicks();

	private ReviveRules() {}

	private static int[] computeDrainTicks() {
		int[] ticks = new int[Rules.REVIVE_FOOD_COST];
		for (int i = 1; i <= Rules.REVIVE_FOOD_COST; i++) ticks[i - 1] = Math.round(i * (float) Rules.REVIVE_DURATION_TICKS / Rules.REVIVE_FOOD_COST);
		return ticks;
	}

	public static boolean drainsAt(int elapsedTicks) {
		for (int t : DRAIN_TICKS) if (t == elapsedTicks) return true;
		return false;
	}
	public static boolean canStart(int reviverFoodLevel) { return reviverFoodLevel >= Rules.REVIVE_MIN_FOOD; }
	public static boolean isComplete(int elapsedTicks) { return elapsedTicks >= Rules.REVIVE_DURATION_TICKS; }
	public static int progressPercent(int elapsedTicks) { return (int) Math.min(100L, Math.floorDiv(elapsedTicks * 100L, Rules.REVIVE_DURATION_TICKS)); }

	public static BreakReason check(double reviverDriftSq, double separationSq, boolean reviverHurt, boolean targetHurt,
			int reviverFood, int targetFood, boolean targetDowned, boolean reviverAvailable) {
		if (!reviverAvailable) return BreakReason.REVIVER_UNAVAILABLE;
		if (!targetDowned) return BreakReason.TARGET_NOT_DOWNED;
		if (reviverHurt) return BreakReason.REVIVER_HURT;
		if (targetHurt) return BreakReason.TARGET_HURT;
		if (reviverFood <= 0) return BreakReason.REVIVER_STARVING;
		if (targetFood <= 0) return BreakReason.TARGET_STARVING;
		if (reviverDriftSq > Rules.REVIVE_MAX_REVIVER_DRIFT * Rules.REVIVE_MAX_REVIVER_DRIFT) return BreakReason.REVIVER_MOVED;
		if (separationSq > Rules.REVIVE_MAX_SEPARATION * Rules.REVIVE_MAX_SEPARATION) return BreakReason.TOO_FAR_APART;
		return BreakReason.NONE;
	}

	/** Rising note-block scale while the channel runs: one note every {@link Rules#REVIVE_NOTE_INTERVAL_TICKS} ticks, the last one on completion. */
	public static boolean playsNoteAt(int elapsed) {
		return elapsed > 0 && elapsed <= Rules.REVIVE_DURATION_TICKS && elapsed % Rules.REVIVE_NOTE_INTERVAL_TICKS == 0;
	}

	/** Pitch of the note at {@code elapsed}: whole semitones across the note-block range (0.5 to 2.0, two octaves) over the channel. */
	public static float notePitch(int elapsed) {
		int semitone = Math.round(24f * elapsed / Rules.REVIVE_DURATION_TICKS);
		return (float) Math.pow(2.0, (semitone - 12) / 12.0);
	}
}
