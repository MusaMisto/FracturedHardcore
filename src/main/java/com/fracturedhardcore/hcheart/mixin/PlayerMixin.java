package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** All hostile targeting funnels through LivingEntity.canAttack(target) -> target.canBeSeenAsEnemy(). */
	@Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
	private void hcheart$untargetableWhileDowned(CallbackInfoReturnable<Boolean> cir) {
		if (DownedManager.isDownedPlayer((Player) (Object) this)) cir.setReturnValue(false);
	}

	/** Vanilla recomputes the pose every tick; keep the server pose prone so the hitbox stays 0.6 blocks tall. */
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void hcheart$proneWhileDowned(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (DownedManager.isDownedPlayer(self)) {
			if (self.getPose() != Pose.SWIMMING) self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
