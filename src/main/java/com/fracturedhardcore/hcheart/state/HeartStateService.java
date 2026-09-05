package com.fracturedhardcore.hcheart.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	/** Overworld game time: the clock every downed timer is measured against. */
	public long now() { return server.overworld().getGameTime(); }

	/** First-join init (0 deaths, 0 restores) and name refresh. */
	public PlayerRecord getOrCreate(UUID id, String name) {
		PlayerRecord rec = state.get(id);
		if (!state.contains(id)) return commit(id, rec.withName(name), "INIT", "first join");
		if (!rec.lastKnownName().equals(name)) return commit(id, rec.withName(name), "RENAME", "was '" + rec.lastKnownName() + "'");
		return rec;
	}

	public PlayerRecord recordDeath(UUID id) {
		PlayerRecord rec = state.get(id).withDeath().withDownedCleared();
		String flag = rec.eliminated() ? " ELIMINATED" : rec.finalLife() ? " FINAL_LIFE" : "";
		return commit(id, rec, "DEATH", "deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + flag);
	}

	public PlayerRecord restore(UUID id) {
		PlayerRecord before = state.get(id);
		PlayerRecord rec = before.withRestore();
		return commit(id, rec, "RESTORE", "cost=" + before.restoreCost() + " deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + " nextCost=" + rec.restoreCost());
	}

	public PlayerRecord enterDowned(UUID id, long untilTick) { return commit(id, state.get(id).withDownedUntil(untilTick), "DOWNED", "until=" + untilTick); }

	public PlayerRecord clearDowned(UUID id, String reason) { return commit(id, state.get(id).withDownedCleared(), "DOWNED_CLEARED", reason); }

	public PlayerRecord set(UUID id, int deaths, int restores, String actor) {
		return commit(id, state.get(id).withCounters(deaths, restores).withDownedCleared(), "SET", "deaths=" + deaths + " restores=" + restores + " by " + actor);
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
