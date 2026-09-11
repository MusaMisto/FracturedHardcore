package com.fracturedhardcore.hcheart.core;

/**
 * Persistent per-player state. Immutable; everything else in the mod is derived from these values.
 * <p>
 * The bleed-out deadline is <b>wall-clock time</b> (epoch milliseconds), not world time. World time stops while the server
 * is empty (vanilla pause-when-empty) or down, and the brief says the clock "continues regardless". {@code downedPausedMs}
 * is what is left on the clock while a revive channel holds it (0 = the clock is running). {@code pendingKill} means the
 * clock ran out while the player was offline: the death is already counted; the vanilla kill is owed on their next join.
 * {@code legacyDeadlineTicks} is a deadline written by 0.1.3 or older in overworld game ticks; it is converted to a wall-clock
 * deadline on the first server tick after loading and is 0 otherwise.
 */
public record PlayerRecord(int deaths, int restoresUsed, long downedUntilMs, long downedPausedMs, long legacyDeadlineTicks, boolean pendingKill, String lastKnownName) {
	public static final PlayerRecord FRESH = new PlayerRecord(0, 0, 0L, 0L, 0L, false, "");
	/** 0.1.3 and older measured the clock in world ticks; a tick is 50 ms. */
	public static final long LEGACY_MS_PER_TICK = 50L;

	/** A record whose clock is not paused and that owes no kill. */
	public PlayerRecord(int deaths, int restoresUsed, long downedUntilMs, String lastKnownName) {
		this(deaths, restoresUsed, downedUntilMs, 0L, 0L, false, lastKnownName);
	}

	/** A record that owes no kill. */
	public PlayerRecord(int deaths, int restoresUsed, long downedUntilMs, long downedPausedMs, String lastKnownName) {
		this(deaths, restoresUsed, downedUntilMs, downedPausedMs, 0L, false, lastKnownName);
	}

	/** A record with a wall-clock deadline (no legacy clock). */
	public PlayerRecord(int deaths, int restoresUsed, long downedUntilMs, long downedPausedMs, boolean pendingKill, String lastKnownName) {
		this(deaths, restoresUsed, downedUntilMs, downedPausedMs, 0L, pendingKill, lastKnownName);
	}

	public PlayerRecord {
		if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
		if (restoresUsed < 0) throw new IllegalArgumentException("restoresUsed must be >= 0");
		if (downedUntilMs < 0) throw new IllegalArgumentException("downedUntilMs must be >= 0");
		if (downedPausedMs < 0) throw new IllegalArgumentException("downedPausedMs must be >= 0");
		if (legacyDeadlineTicks < 0) throw new IllegalArgumentException("legacyDeadlineTicks must be >= 0");
		if (pendingKill) { // the death is already counted: a clock of any kind would count it twice
			downedUntilMs = 0L;
			legacyDeadlineTicks = 0L;
		}
		if (downedUntilMs > 0) legacyDeadlineTicks = 0L; // a wall-clock deadline supersedes an unconverted legacy one
		if (downedUntilMs == 0 && legacyDeadlineTicks == 0) downedPausedMs = 0L; // a pause only means something while downed
		if (lastKnownName == null) lastKnownName = "";
	}

	public int maxHearts() { return Math.max(Rules.FLOOR_HEARTS, Rules.BASE_HEARTS - Rules.HEARTS_LOST_PER_DEATH * deaths); }
	public double maxHealth() { return maxHearts() * 2.0; }
	public boolean finalLife() { return deaths >= Rules.FINAL_LIFE_DEATHS; }
	public boolean eliminated() { return deaths >= Rules.ELIMINATION_DEATHS; }
	public int restoreCost() { return restoresUsed + 1; }
	public boolean canRestore() { return deaths > 0; }
	public boolean isDowned() { return downedUntilMs > 0 || legacyDeadlineTicks > 0; }
	/** Downed with a deadline still in 0.1.3 world ticks; never expires as such, the tick loop converts it first. */
	public boolean hasLegacyClock() { return legacyDeadlineTicks > 0; }
	/** True while a revive channel holds the clock; a paused clock never expires and shows a frozen remainder. */
	public boolean isDownedPaused() { return isDowned() && downedPausedMs > 0; }
	public boolean downedExpired(long nowMs) { return downedUntilMs > 0 && !isDownedPaused() && nowMs >= downedUntilMs; }
	public long downedMillisRemaining(long nowMs) {
		if (!isDowned()) return 0L;
		if (isDownedPaused()) return downedPausedMs;
		return hasLegacyClock() ? 0L : Math.max(0L, downedUntilMs - nowMs);
	}

	public PlayerRecord withDeath() { return new PlayerRecord(deaths + 1, restoresUsed, downedUntilMs, downedPausedMs, legacyDeadlineTicks, pendingKill, lastKnownName); }
	public PlayerRecord withRestore() {
		if (!canRestore()) throw new IllegalStateException("cannot restore at 0 deaths");
		return new PlayerRecord(deaths - 1, restoresUsed + 1, downedUntilMs, downedPausedMs, legacyDeadlineTicks, pendingKill, lastKnownName);
	}
	/** A fresh clock is never paused and replaces any legacy one. */
	public PlayerRecord withDownedUntil(long untilMs) { return new PlayerRecord(deaths, restoresUsed, untilMs, 0L, 0L, pendingKill, lastKnownName); }
	public PlayerRecord withDownedCleared() { return withDownedUntil(0L); }
	/**
	 * Freeze the clock with whatever is left on it (at least one millisecond). Only while downed with a running wall-clock
	 * deadline; a legacy clock has no known remainder until it has been converted.
	 */
	public PlayerRecord withDownedPaused(long nowMs) {
		if (downedUntilMs == 0 || isDownedPaused()) throw new IllegalStateException("the clock is not running");
		return new PlayerRecord(deaths, restoresUsed, downedUntilMs, Math.max(1L, downedUntilMs - nowMs), 0L, pendingKill, lastKnownName);
	}
	/** Run the clock again from what was left on it, measured from {@code nowMs}. Only while paused. A remainder is exact, so this also converts a paused legacy clock. */
	public PlayerRecord withDownedResumed(long nowMs) {
		if (!isDownedPaused()) throw new IllegalStateException("the clock is not paused");
		return new PlayerRecord(deaths, restoresUsed, nowMs + downedPausedMs, 0L, 0L, pendingKill, lastKnownName);
	}
	/**
	 * Convert a 0.1.3 world-tick deadline to wall-clock time. Overworld game time is persisted, so the remainder is exact;
	 * a clock that had already run out under 0.1.3 rules becomes a deadline of {@code nowMs}, i.e. expired now.
	 */
	public PlayerRecord withLegacyClockConverted(long nowMs, long overworldGameTime) {
		if (!hasLegacyClock()) throw new IllegalStateException("no legacy clock to convert");
		long remainingTicks = Math.max(0L, legacyDeadlineTicks - overworldGameTime);
		return withDownedUntil(nowMs + remainingTicks * LEGACY_MS_PER_TICK);
	}
	public PlayerRecord withPendingKill(boolean pending) { return new PlayerRecord(deaths, restoresUsed, downedUntilMs, downedPausedMs, legacyDeadlineTicks, pending, lastKnownName); }
	public PlayerRecord withName(String name) { return new PlayerRecord(deaths, restoresUsed, downedUntilMs, downedPausedMs, legacyDeadlineTicks, pendingKill, name); }
	public PlayerRecord withCounters(int newDeaths, int newRestores) { return new PlayerRecord(newDeaths, newRestores, downedUntilMs, downedPausedMs, legacyDeadlineTicks, pendingKill, lastKnownName); }
}
