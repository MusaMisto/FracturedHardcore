package com.fracturedhardcore.hcheart.core;

/**
 * Persistent per-player state. Immutable; everything else in the mod is derived from these five values.
 * {@code downedPausedTicks} is what is left on the bleed-out clock while a revive channel holds it (0 = the clock is running).
 */
public record PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, long downedPausedTicks, String lastKnownName) {
	public static final PlayerRecord FRESH = new PlayerRecord(0, 0, 0L, 0L, "");

	/** A record whose clock is not paused. */
	public PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, String lastKnownName) {
		this(deaths, restoresUsed, downedUntilTick, 0L, lastKnownName);
	}

	public PlayerRecord {
		if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
		if (restoresUsed < 0) throw new IllegalArgumentException("restoresUsed must be >= 0");
		if (downedUntilTick < 0) throw new IllegalArgumentException("downedUntilTick must be >= 0");
		if (downedPausedTicks < 0) throw new IllegalArgumentException("downedPausedTicks must be >= 0");
		if (downedUntilTick == 0) downedPausedTicks = 0L; // a pause only means something while downed
		if (lastKnownName == null) lastKnownName = "";
	}

	public int maxHearts() { return Math.max(Rules.FLOOR_HEARTS, Rules.BASE_HEARTS - Rules.HEARTS_LOST_PER_DEATH * deaths); }
	public double maxHealth() { return maxHearts() * 2.0; }
	public boolean finalLife() { return deaths >= Rules.FINAL_LIFE_DEATHS; }
	public boolean eliminated() { return deaths >= Rules.ELIMINATION_DEATHS; }
	public int restoreCost() { return restoresUsed + 1; }
	public boolean canRestore() { return deaths > 0; }
	public boolean isDowned() { return downedUntilTick > 0; }
	/** True while a revive channel holds the clock; a paused clock never expires and shows a frozen remainder. */
	public boolean isDownedPaused() { return isDowned() && downedPausedTicks > 0; }
	public boolean downedExpired(long nowTick) { return isDowned() && !isDownedPaused() && nowTick >= downedUntilTick; }
	public long downedTicksRemaining(long nowTick) {
		if (!isDowned()) return 0L;
		return isDownedPaused() ? downedPausedTicks : Math.max(0L, downedUntilTick - nowTick);
	}

	public PlayerRecord withDeath() { return new PlayerRecord(deaths + 1, restoresUsed, downedUntilTick, downedPausedTicks, lastKnownName); }
	public PlayerRecord withRestore() {
		if (!canRestore()) throw new IllegalStateException("cannot restore at 0 deaths");
		return new PlayerRecord(deaths - 1, restoresUsed + 1, downedUntilTick, downedPausedTicks, lastKnownName);
	}
	/** A fresh clock is never paused. */
	public PlayerRecord withDownedUntil(long tick) { return new PlayerRecord(deaths, restoresUsed, tick, 0L, lastKnownName); }
	public PlayerRecord withDownedCleared() { return withDownedUntil(0L); }
	/** Freeze the clock with whatever is left on it (at least one tick). Only while downed with the clock running. */
	public PlayerRecord withDownedPaused(long nowTick) {
		if (!isDowned() || isDownedPaused()) throw new IllegalStateException("the clock is not running");
		return new PlayerRecord(deaths, restoresUsed, downedUntilTick, Math.max(1L, downedUntilTick - nowTick), lastKnownName);
	}
	/** Run the clock again from what was left on it, measured from {@code nowTick}. Only while paused. */
	public PlayerRecord withDownedResumed(long nowTick) {
		if (!isDownedPaused()) throw new IllegalStateException("the clock is not paused");
		return new PlayerRecord(deaths, restoresUsed, nowTick + downedPausedTicks, 0L, lastKnownName);
	}
	public PlayerRecord withName(String name) { return new PlayerRecord(deaths, restoresUsed, downedUntilTick, downedPausedTicks, name); }
	public PlayerRecord withCounters(int newDeaths, int newRestores) { return new PlayerRecord(newDeaths, newRestores, downedUntilTick, downedPausedTicks, lastKnownName); }
}
