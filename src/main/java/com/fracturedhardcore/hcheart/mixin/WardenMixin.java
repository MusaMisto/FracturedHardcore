package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Warden targets through anger/vibrations and its own canTargetEntity (also used by its canAttack), not the standard target goals. */
@Mixin(Warden.class)
public abstract class WardenMixin {
	@Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
	private void hcheart$ignoreDownedPlayers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player player && DownedManager.isDownedPlayer(player)) cir.setReturnValue(false);
	}
}
