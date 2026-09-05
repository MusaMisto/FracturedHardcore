package com.fracturedhardcore.hcheart.scoreboard;

import java.util.Collection;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Mirrors currentDeaths into the `deaths_hc` objective shown in the tab list. */
public final class ScoreboardService {
	public static final String OBJECTIVE = "deaths_hc";
	private final MinecraftServer server;

	public ScoreboardService(MinecraftServer server) { this.server = server; }

	public Objective ensureObjective() {
		ServerScoreboard sb = server.getScoreboard();
		Objective objective = sb.getObjective(OBJECTIVE);
		if (objective == null) {
			objective = sb.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("Deaths"), ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		if (sb.getDisplayObjective(DisplaySlot.LIST) == null) sb.setDisplayObjective(DisplaySlot.LIST, objective);
		return objective;
	}

	public void sync(PlayerRecord rec) {
		if (rec.lastKnownName().isEmpty()) return;
		server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(rec.lastKnownName()), ensureObjective()).set(rec.deaths());
	}

	public void clear(Collection<String> names) {
		Objective objective = ensureObjective();
		for (String name : names) {
			if (!name.isEmpty()) server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective).set(0);
		}
	}
}
