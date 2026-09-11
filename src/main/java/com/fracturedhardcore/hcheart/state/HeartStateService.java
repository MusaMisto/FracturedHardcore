package com.fracturedhardcore.hcheart.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.scoreboard.ScoreboardService;
import net.minecraft.server.MinecraftServer;

/**
 * The ONLY code path that mutates player records. Every mutation is persisted and flushed to disk synchronously,
 * written to the audit log, and mirrored to the scoreboard before returning.
 */
public final class HeartStateService {
	private final MinecraftServer server;
	private final HeartState state;
	private final AuditLog audit;
	private final ScoreboardService scoreboard;
	private LongSupplier clock = System::currentTimeMillis;

	public HeartStateService(MinecraftServer server, HeartState state, AuditLog audit, ScoreboardService scoreboard) {
		this.server = server;
		this.state = state;
		this.audit = audit;
		this.scoreboard = scoreboard;
	}

	public static HeartStateService load(MinecraftServer server) {
		HeartState state = server.getDataStorage().computeIfAbsent(HeartState.TYPE);
		AuditLog audit = new AuditLog(server.getServerDirectory().resolve("logs").resolve("hcheart-audit.log"));
		HcHeart.LOGGER.info("Loaded Fractured Hardcore state for {} player(s)", state.view().size());
		return new HeartStateService(server, state, audit, new ScoreboardService(server));
	}

	public PlayerRecord get(UUID id) { return state.get(id); }
	public Map<UUID, PlayerRecord> all() { return state.view(); }
	public AuditLog audit() { return audit; }
	public ScoreboardService scoreboard() { return scoreboard; }
	/**
	 * Wall-clock time in epoch milliseconds: the clock every downed deadline is measured against. World time is deliberately
	 * not used: it stops while the server is empty (vanilla pause-when-empty) or down, and the clock must run regardless.
	 */
	public long now() { return clock.getAsLong(); }

	/** Test seam: swap the clock, e.g. jump it forward to expire a deadline deterministically. Restore it afterwards. */
	public void setClock(LongSupplier clock) { this.clock = clock; }

	/** First-join init (0 deaths, 0 restores) and name refresh. */
	public PlayerRecord getOrCreate(UUID id, String name) {
		PlayerRecord rec = state.get(id);
		if (!state.contains(id)) return commit(id, rec.withName(name), "INIT", "first join");
		if (!rec.lastKnownName().equals(name)) return commit(id, rec.withName(name), "RENAME", "was '" + rec.lastKnownName() + "'");
		return rec;
	}

	/** A death that vanilla just carried out (AFTER_DEATH). */
	public PlayerRecord recordDeath(UUID id) { return death(id, false); }

	/** The clock ran out while the player was offline: the death counts now; the vanilla kill is applied on their next join. */
	public PlayerRecord recordOfflineBleedOut(UUID id) { return death(id, true); }

	/** The one recipe for what a counted death does to a record. */
	private PlayerRecord death(UUID id, boolean killOwed) {
		PlayerRecord rec = state.get(id).withDeath().withDownedCleared().withPendingKill(killOwed);
		return commit(id, rec, "DEATH", deathDetails(rec) + (killOwed ? " bled out offline, kill owed on next join" : ""));
	}

	/**
	 * Convert a deadline written by 0.1.3 or older (overworld game ticks) to wall-clock time. Game time is persisted, so the
	 * remainder is exact; a clock that had already run out under 0.1.3 rules comes back expired and is resolved by the caller.
	 */
	public PlayerRecord convertLegacyClock(UUID id, long overworldGameTime) {
		PlayerRecord before = state.get(id);
		PlayerRecord rec = before.withLegacyClockConverted(now(), overworldGameTime);
		long left = rec.downedMillisRemaining(now());
		return commit(id, rec, "DOWNED", "converted 0.1.3 clock (until tick " + before.legacyDeadlineTicks() + " at tick " + overworldGameTime + "): "
				+ (left > 0 ? left / 1000 + " s left" : "had already run out"));
	}

	/** Writes a record exactly as the 0.1.3 codec would have loaded it. Exists for the upgrade gametest; production records take the codec path. */
	public PlayerRecord importLegacyDowned(UUID id, long deadlineTicks, long pausedTicks) {
		PlayerRecord old = state.get(id);
		PlayerRecord rec = new PlayerRecord(old.deaths(), old.restoresUsed(), 0L, pausedTicks * PlayerRecord.LEGACY_MS_PER_TICK, deadlineTicks, false, old.lastKnownName());
		return commit(id, rec, "DOWNED", "0.1.3-format import until tick " + deadlineTicks + (pausedTicks > 0 ? " paused " + pausedTicks + " ticks" : ""));
	}

	/** The owed kill landed (AFTER_DEATH): nothing to count, just settle the flag. */
	public PlayerRecord applyPendingKill(UUID id) { return commit(id, state.get(id).withPendingKill(false), "DEATH_APPLIED", "owed kill landed after join"); }

	private static String deathDetails(PlayerRecord rec) {
		String flag = rec.eliminated() ? " ELIMINATED" : rec.finalLife() ? " FINAL_LIFE" : "";
		return "deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + flag;
	}

	public PlayerRecord restore(UUID id) {
		PlayerRecord before = state.get(id);
		PlayerRecord rec = before.withRestore();
		return commit(id, rec, "RESTORE", "cost=" + before.restoreCost() + " deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + " nextCost=" + rec.restoreCost());
	}

	public PlayerRecord enterDowned(UUID id, long untilMs) { return commit(id, state.get(id).withDownedUntil(untilMs), "DOWNED", "until=" + Instant.ofEpochMilli(untilMs)); }

	public PlayerRecord clearDowned(UUID id, String reason) { return commit(id, state.get(id).withDownedCleared(), "DOWNED_CLEARED", reason); }

	/** A revive channel holds the clock: what is left on it is stored and the deadline stops mattering until resume. */
	public PlayerRecord pauseDowned(UUID id, String reason) {
		PlayerRecord rec = state.get(id).withDownedPaused(now());
		return commit(id, rec, "DOWNED_PAUSED", rec.downedPausedMs() + " ms left · " + reason);
	}

	/** The channel ended without a revive: the clock runs again from what was left on it. */
	public PlayerRecord resumeDowned(UUID id, String reason) {
		PlayerRecord rec = state.get(id).withDownedResumed(now());
		return commit(id, rec, "DOWNED_RESUMED", "until=" + Instant.ofEpochMilli(rec.downedUntilMs()) + " · " + reason);
	}

	public PlayerRecord set(UUID id, int deaths, int restores, String actor) {
		return commit(id, state.get(id).withCounters(deaths, restores).withDownedCleared().withPendingKill(false), "SET", "deaths=" + deaths + " restores=" + restores + " by " + actor);
	}

	public void resetAll(String actor) {
		List<String> names = new ArrayList<>();
		state.view().forEach((id, rec) -> {
			names.add(rec.lastKnownName());
			audit.log(id, rec.lastKnownName(), "RESET", "was deaths=" + rec.deaths() + " restores=" + rec.restoresUsed() + " by " + actor);
		});
		state.clear();
		flush();
		scoreboard.clear(names);
	}

	private PlayerRecord commit(UUID id, PlayerRecord rec, String event, String details) {
		state.put(id, rec);
		flush();
		audit.log(id, rec.lastKnownName(), event, details);
		scoreboard.sync(rec);
		return rec;
	}

	/** Synchronous write of all dirty saved data. Cheap: only called on rare state changes. */
	public void flush() {
		try {
			server.getDataStorage().saveAndJoin();
		} catch (RuntimeException e) {
			HcHeart.LOGGER.error("Failed to flush Fractured Hardcore state", e);
		}
	}
}
