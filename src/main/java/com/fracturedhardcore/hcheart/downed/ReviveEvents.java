package com.fracturedhardcore.hcheart.downed;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public final class ReviveEvents {
	private ReviveEvents() {}

	/** Right-clicking a downed player starts the channel. Registered after the downed interaction lock. */
	public static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			Services s = HcHeartMod.services();
			if (s == null || level.isClientSide() || !(player instanceof ServerPlayer reviver) || !(entity instanceof ServerPlayer target)) {
				return InteractionResult.PASS;
			}
			return s.revive().tryStart(reviver, target);
		});
	}
}
