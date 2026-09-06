package com.fracturedhardcore.hcheart.join;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.health.HealthService;
import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.minecraft.server.level.ServerPlayer;

/** Runs on every join. Everything else in the mod assumes this has run. */
public final class JoinHandler {
	private JoinHandler() {}

	public static void onJoin(ServerPlayer player) {
		Services s = HcHeartMod.services();
		if (s == null) return;
		PlayerRecord rec = s.state().getOrCreate(player.getUUID(), player.getGameProfile().name());

		// 1+2. Own the base value and reapply the penalty purely from stored state.
		HealthService.normalize(player, rec);

		// 3. Rescue anyone stranded in spectator by stock hardcore rules (unless their run is over).
		if (DeathRules.shouldRescueFromSpectator(rec, player.isSpectator())) {
			player = RespawnService.rescueFromSpectator(player);
		}

		// 4. A clock paused by a revive channel that no longer exists (crash mid-revive) runs again from what was left.
		if (rec.isDownedPaused() && !s.revive().isChanneling(player.getUUID())) rec = s.state().resumeDowned(player.getUUID(), "join");

		// 5. Resolve downed state against world time; clear stale presentation otherwise.
		if (rec.isDowned()) {
			if (rec.downedExpired(s.state().now())) s.downed().bleedOut(player);
			else s.downed().reenter(player, rec);
		} else {
			s.downed().clearPresentation(player);
		}
		s.downed().onViewerJoined(player);

		// 6. Hearts crafted before 0.1.2 get their texture key (cosmetic, idempotent).
		HeartItem.stampAll(player);

		// 7. Scoreboard.
		s.state().scoreboard().sync(rec);
	}
}
