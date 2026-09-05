package com.fracturedhardcore.hcheart.health;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Owns the max-health attribute: base is always 20, the penalty is one transient modifier recomputed from state. */
public final class HealthService {
	public static final Identifier PENALTY_ID = HcHeart.id("heart_penalty");

	private HealthService() {}

	/** Idempotent. Safe to call on every join, every respawn, and after every state change. */
	public static void normalize(ServerPlayer player, PlayerRecord rec) {
		AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
		if (attr == null) return;
		attr.setBaseValue(20.0);
		attr.removeModifier(PENALTY_ID);
		double delta = rec.maxHealth() - 20.0;
		if (delta != 0.0) attr.addTransientModifier(new AttributeModifier(PENALTY_ID, delta, AttributeModifier.Operation.ADD_VALUE));
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	public static void refill(ServerPlayer player) { player.setHealth(player.getMaxHealth()); }
}
