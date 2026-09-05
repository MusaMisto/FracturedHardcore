package com.fracturedhardcore.hcheart.join;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;

public final class RespawnService {
	private RespawnService() {}

	/** Order matters: game mode, teleport, health, effects. Returns the (possibly re-created) player. */
	public static ServerPlayer rescueFromSpectator(ServerPlayer player) {
		player.setGameMode(GameType.SURVIVAL);
		TeleportTransition transition = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		ServerPlayer moved = player.teleport(transition);
		ServerPlayer target = moved != null ? moved : player;
		target.setHealth(target.getMaxHealth());
		target.removeAllEffects();
		target.getFoodData().setFoodLevel(20);
		target.getFoodData().setSaturation(5f);
		return target;
	}
}
