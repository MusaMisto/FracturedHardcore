package com.fracturedhardcore.hcheart.core;

/** Every death-path decision, as pure functions of the record. */
public final class DeathRules {
	public enum Outcome { ENTER_DOWNED, TRUE_DEATH }

	private DeathRules() {}

	/** Called when a player's health would drop to zero. */
	public static Outcome onLethalDamage(PlayerRecord rec, boolean bypassesInvulnerability) {
		if (bypassesInvulnerability) return Outcome.TRUE_DEATH; // void, /kill, bleed-out
		if (rec.isDowned()) return Outcome.TRUE_DEATH;           // only bypass damage reaches here anyway
		if (rec.finalLife()) return Outcome.TRUE_DEATH;
		return Outcome.ENTER_DOWNED;
	}

	public static boolean blocksDamageWhileDowned(PlayerRecord rec, boolean bypassesInvulnerability) { return rec.isDowned() && !bypassesInvulnerability; }
	public static boolean respawnsAsSpectator(PlayerRecord rec) { return rec.eliminated(); }
	public static boolean shouldRescueFromSpectator(PlayerRecord rec, boolean isSpectator) { return isSpectator && !rec.eliminated(); }
}
