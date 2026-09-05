package com.fracturedhardcore.hcheart.core;

/** Persistent per-player state. Immutable; everything else in the mod is derived from these four values. */
public record PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, String lastKnownName) {
	public static final PlayerRecord FRESH = new PlayerRecord(0, 0, 0L, "");

	public PlayerRecord {
		if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
		if (restoresUsed < 0) throw new IllegalArgumentException("restoresUsed must be >= 0");
		if (downedUntilTick < 0) throw new IllegalArgumentException("downedUntilTick must be >= 0");
		if (lastKnownName == null) lastKnownName = "";
	}

	public int maxHearts() { return Math.max(Rules.FLOOR_HEARTS, Rules.BASE_HEARTS - Rules.HEARTS_LOST_PER_DEATH * deaths); }
	public double maxHealth() { return maxHearts() * 2.0; }
	public boolean finalLife() { return deaths >= Rules.FINAL_LIFE_DEATHS; }
	public boolean eliminated() { return deaths >= Rules.ELIMINATION_DEATHS; }
	public int restoreCost() { return restoresUsed + 1; }
	public boolean canRestore() { return deaths > 0; }
	public boolean isDowned() { return downedUntilTick > 0; }
	public boolean downedExpired(long nowTick) { return isDowned() && nowTick >= downedUntilTick; }
	public long downedTicksRemaining(long nowTick) { return isDowned() ? Math.max(0L, downedUntilTick - nowTick) : 0L; }

	public PlayerRecord withDeath() { return new PlayerRecord(deaths + 1, restoresUsed, downedUntilTick, lastKnownName); }
	public PlayerRecord withRestore() {
		if (!canRestore()) throw new IllegalStateException("cannot restore at 0 deaths");
		return new PlayerRecord(deaths - 1, restoresUsed + 1, downedUntilTick, lastKnownName);
	}
	public PlayerRecord withDownedUntil(long tick) { return new PlayerRecord(deaths, restoresUsed, tick, lastKnownName); }
	public PlayerRecord withDownedCleared() { return withDownedUntil(0L); }
	public PlayerRecord withName(String name) { return new PlayerRecord(deaths, restoresUsed, downedUntilTick, name); }
	public PlayerRecord withCounters(int newDeaths, int newRestores) { return new PlayerRecord(newDeaths, newRestores, downedUntilTick, lastKnownName); }
}
