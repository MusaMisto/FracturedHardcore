package com.fracturedhardcore.hcheart.death;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.downed.Text;
import com.fracturedhardcore.hcheart.health.HealthService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class DeathEvents {
	private DeathEvents() {}

	public static void register() {
		// Fires at ServerPlayer.die TAIL: the death is committed. A totem save never reaches here, so it is never miscounted.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return;
			s.downed().clear(player, "died");
			if (s.state().get(player.getUUID()).pendingKill()) { // bled out offline: counted and announced when the clock ran out
				s.state().applyPendingKill(player.getUUID());
				return;
			}
			PlayerRecord rec = s.state().recordDeath(player.getUUID());
			Component line = Messages.othersDeathLine(player.getGameProfile().name(), rec);
			for (ServerPlayer other : s.server().getPlayerList().getPlayers()) {
				if (other == player) continue;
				other.sendSystemMessage(line, false);
				Sounds.playTo(other, SoundEvents.BELL_RESONATE, SoundSource.MASTER, 0.3f, 0.6f);
			}
		});
		// Fires at PlayerList.respawn TAIL with the new entity fully in the world. alive == true is an End-portal trip, not a death.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			Services s = HcHeartMod.services();
			if (s == null) return;
			s.downed().onViewerRespawned(oldPlayer, newPlayer);
			if (alive) return;
			PlayerRecord rec = s.state().get(newPlayer.getUUID());
			HealthService.normalize(newPlayer, rec);
			HealthService.refill(newPlayer);
			if (rec.eliminated()) {
				newPlayer.connection.send(new ClientboundSetTitleTextPacket(Text.warn("Your run is over.")));
				newPlayer.connection.send(new ClientboundSetSubtitleTextPacket(Text.info("You may spectate the world.")));
				newPlayer.sendSystemMessage(Messages.eliminated(), false);
			} else {
				Sounds.playTo(newPlayer, SoundEvents.BELL_RESONATE, SoundSource.MASTER, 1.0f, 0.6f);
				for (Component line : Messages.selfDeathLines(rec)) newPlayer.sendSystemMessage(line, false);
			}
			s.state().scoreboard().sync(rec);
		});
	}
}
