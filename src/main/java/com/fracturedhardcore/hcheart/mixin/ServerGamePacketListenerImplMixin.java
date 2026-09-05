package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla: after PERFORM_RESPAWN, {@code if (server.isHardcore()) player.setGameMode(SPECTATOR)}. We spectate only when eliminated. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow public ServerPlayer player;

	@WrapOperation(method = "handleClientCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isHardcore()Z"))
	private boolean hcheart$spectateOnlyWhenEliminated(MinecraftServer server, Operation<Boolean> original) {
		Services s = HcHeartMod.services();
		if (s == null) return original.call(server);
		return DeathRules.respawnsAsSpectator(s.state().get(this.player.getUUID()));
	}
}
