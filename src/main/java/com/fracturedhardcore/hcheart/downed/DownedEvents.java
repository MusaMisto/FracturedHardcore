package com.fracturedhardcore.hcheart.downed;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

public final class DownedEvents {
	private DownedEvents() {}

	public static void register() {
		// Fabric redirects the second isDeadOrDying() in LivingEntity.hurtServer: health is already <= 0 here, before the totem check and die().
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return true;
			PlayerRecord rec = s.state().get(player.getUUID());
			boolean bypass = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
			return switch (DeathRules.onLethalDamage(rec, bypass)) {
				case TRUE_DEATH -> true;
				case ENTER_DOWNED -> {
					s.downed().enter(player); // sets health to 1; the entity would otherwise die next tick
					yield false;
				}
			};
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return true;
			return !DeathRules.blocksDamageWhileDowned(s.state().get(player.getUUID()), source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
		});
		// Interaction lock. Registered before the Heart and revive handlers so a downed actor is refused first.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> lock(player));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> lock(player));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> lock(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> lock(player));
		UseItemCallback.EVENT.register((player, level, hand) -> lock(player));
	}

	private static InteractionResult lock(Player player) {
		if (player instanceof ServerPlayer sp && DownedManager.isDownedPlayer(sp)) {
			sp.sendSystemMessage(Text.warn("You are downed and cannot do that."), true);
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}
}
