package com.fracturedhardcore.hcheart.core;

/** Tunables. Pure constants; no Minecraft imports. */
public final class Rules {
	public static final int BASE_HEARTS = 10;
	public static final int FLOOR_HEARTS = 4;
	public static final int HEARTS_LOST_PER_DEATH = 2;
	public static final int FINAL_LIFE_DEATHS = 3;
	public static final int ELIMINATION_DEATHS = 4;
	public static final long DOWNED_DURATION_TICKS = 180L * 20L;
	public static final int REVIVE_DURATION_TICKS = 8 * 20;
	public static final int REVIVE_MIN_FOOD = 6;
	public static final int REVIVE_FOOD_COST = 6;
	public static final double REVIVE_MAX_REVIVER_DRIFT = 2.0;
	public static final double REVIVE_MAX_SEPARATION = 4.0;
	public static final double DOWNED_SPEED_MULTIPLIER = -0.5;
	public static final double DOWNED_JUMP_MULTIPLIER = -1.0;
	public static final int HEART_USE_COOLDOWN_TICKS = 20;

	private Rules() {}
}
