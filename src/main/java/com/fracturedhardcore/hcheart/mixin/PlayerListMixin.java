package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client shows "Respawn" vs "Spectate world" (and hardcore hearts) from the {@code hardcore} flag of the login packet,
 * which vanilla fills from levelData.isHardcore(). Send true only for players on their final life.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelData;isHardcore()Z"))
	private boolean hcheart$hardcoreUiOnlyOnFinalLife(LevelData levelData, Operation<Boolean> original, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		Services s = HcHeartMod.services();
		if (s == null) return original.call(levelData);
		return DeathRules.showsHardcoreUi(s.state().getOrCreate(player.getUUID(), player.getGameProfile().name()));
	}
}
