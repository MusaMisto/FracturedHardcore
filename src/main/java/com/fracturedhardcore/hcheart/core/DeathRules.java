package com.fracturedhardcore.hcheart.core;

/** Every death-path decision, as pure functions of the record. */
public final class DeathRules {
	/** TOTEM: let vanilla run; its totem check fires and the player survives, so no death and no downed state. */
	public enum Outcome { ENTER_DOWNED, TRUE_DEATH, TOTEM }

	private DeathRules() {}

	/**
	 * Called when a player's health would drop to zero.
	 *
	 * @param holdsDeathProtection a Totem of Undying (any item with the death_protection component) is in either hand; vanilla
	 *                             fires it for every source except the bypass kind, and the mod must never pre-empt it
	 */
	public static Outcome onLethalDamage(PlayerRecord rec, boolean bypassesInvulnerability, boolean holdsDeathProtection) {
		if (rec.pendingKill()) return Outcome.TRUE_DEATH;     // bled out offline: this death was already counted; any kill settles it
		if (bypassesInvulnerability) return Outcome.TRUE_DEATH; // void, /kill, bleed-out: a totem does not fire for these in vanilla either
		if (rec.isDowned()) return Outcome.TRUE_DEATH;           // only bypass damage reaches here anyway
		if (holdsDeathProtection) return Outcome.TOTEM;          // the totem is consumed exactly as in vanilla; downed never enters into it
		if (rec.finalLife()) return Outcome.TRUE_DEATH;
		return Outcome.ENTER_DOWNED;
	}

	public static boolean blocksDamageWhileDowned(PlayerRecord rec, boolean bypassesInvulnerability) { return rec.isDowned() && !bypassesInvulnerability; }
	public static boolean respawnsAsSpectator(PlayerRecord rec) { return rec.eliminated(); }
	public static boolean shouldRescueFromSpectator(PlayerRecord rec, boolean isSpectator) { return isSpectator && !rec.eliminated(); }
}
