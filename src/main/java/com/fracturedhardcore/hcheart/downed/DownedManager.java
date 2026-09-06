package com.fracturedhardcore.hcheart.downed;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Entry, per-tick enforcement, bleed-out, teardown and crash recovery of the downed state. */
public final class DownedManager {
	public static final Identifier SPEED_ID = HcHeart.id("downed_speed");
	public static final Identifier JUMP_ID = HcHeart.id("downed_jump");
	private static final double SWEEP_RADIUS = 48.0;

	private final MinecraftServer server;
	private final HeartStateService state;
	private ReviveManager revive;
	private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

	public DownedManager(MinecraftServer server, HeartStateService state) {
		this.server = server;
		this.state = state;
	}

	public void attachRevive(ReviveManager revive) { this.revive = revive; }

	public boolean isDowned(ServerPlayer player) { return state.get(player.getUUID()).isDowned(); }

	/** Mixin entry point: a cheap map lookup, safe before the server has started. */
	public static boolean isDownedPlayer(Player player) {
		Services s = HcHeartMod.services();
		return s != null && player instanceof ServerPlayer sp && s.downed().isDowned(sp);
	}

	public void enter(ServerPlayer player) {
		long until = state.now() + Rules.DOWNED_DURATION_TICKS;
		PlayerRecord rec = state.enterDowned(player.getUUID(), until); // persist FIRST
		player.setHealth(1.0f);
		player.clearFire();
		player.stopUsingItem();
		applyPresentation(player, rec);
		sweepTargets(player);
		String where = player.level().dimension().identifier().getPath() + " at "
				+ player.blockPosition().getX() + ", " + player.blockPosition().getY() + ", " + player.blockPosition().getZ();
		server.getPlayerList().broadcastSystemMessage(
				Text.warn(name(player) + " is downed in " + where + " — " + Text.mmss(Rules.DOWNED_DURATION_TICKS) + " to revive them."), false);
		player.sendSystemMessage(Text.gold("You are downed. Crawl to safety — a friend can right-click you to revive you. The clock stops while they do."), false);
		player.sendSystemMessage(Text.info("Nobody around? ").append(Text.link("[Give up]", "/hc giveup", "Skip the clock and accept the death now"))
				.append(Text.info(" or type /hc giveup to accept the death now.")), false);
	}

	/**
	 * Player-initiated bleed-out (/hc giveup confirm). Same death path as the clock running out, so the penalty is identical.
	 * @return false if the player is not downed; nothing happens then.
	 */
	public boolean giveUp(ServerPlayer player) {
		PlayerRecord rec = state.get(player.getUUID());
		if (!rec.isDowned() || player.isDeadOrDying()) return false;
		state.audit().log(player.getUUID(), name(player), "GAVE_UP", Text.mmss(rec.downedTicksRemaining(state.now())) + " left on the clock");
		server.getPlayerList().broadcastSystemMessage(Text.warn(name(player) + " gave up and accepted the death."), false);
		bleedOut(player);
		return true;
	}

	/** A revive channel started on this player: freeze the clock with what is left on it. Idempotent. */
	public void pauseClock(UUID target, String reason) {
		PlayerRecord rec = state.get(target);
		if (rec.isDowned() && !rec.isDownedPaused()) state.pauseDowned(target, reason);
	}

	/** The channel ended without a revive: the clock runs again from where it stopped. Idempotent; the player may be offline. */
	public void resumeClock(UUID target, String reason) {
		if (state.get(target).isDownedPaused()) state.resumeDowned(target, reason);
	}

	/** Re-apply presentation from persisted state (join after crash/relog). */
	public void reenter(ServerPlayer player, PlayerRecord rec) {
		player.setHealth(1.0f);
		applyPresentation(player, rec);
		sweepTargets(player);
	}

	/** Full teardown: state + presentation + any revive channel. Idempotent. */
	public void clear(ServerPlayer player, String reason) {
		if (state.get(player.getUUID()).isDowned()) state.clearDowned(player.getUUID(), reason);
		clearPresentation(player);
		if (revive != null) revive.cancelTarget(player.getUUID());
	}

	/** Presentation only (glow, pose, modifiers, boss bar). Used on join when not downed to clear crash leftovers. */
	public void clearPresentation(ServerPlayer player) {
		player.setGlowingTag(false);
		removeModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID);
		removeModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID);
		if (player.getPose() == Pose.SWIMMING) player.setPose(Pose.STANDING);
		ServerBossEvent bar = bars.remove(player.getUUID());
		if (bar != null) bar.removeAllPlayers();
	}

	/** The only downed→dead route besides bypass damage. generic_kill bypasses invulnerability, totems and armour. */
	public void bleedOut(ServerPlayer player) {
		player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
		if (!player.isDeadOrDying()) {
			HcHeart.LOGGER.error("Bleed-out kill did not take for {}; clearing downed state", name(player));
			clear(player, "bleed-out fallback");
		}
	}

	public void tick() {
		long now = state.now();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerRecord rec = state.get(player.getUUID());
			if (!rec.isDowned() || player.isDeadOrDying()) continue;
			if (rec.isDownedPaused() && (revive == null || !revive.isChanneling(player.getUUID()))) {
				rec = state.resumeDowned(player.getUUID(), "no revive channel holds it (repair)"); // e.g. the channel died with a crash
			}
			if (rec.downedExpired(now)) {
				bleedOut(player);
				continue;
			}
			if (player.getHealth() != 1.0f) player.setHealth(1.0f);
			if (!player.hasGlowingTag()) player.setGlowingTag(true);
			if (player.getPose() != Pose.SWIMMING) player.setPose(Pose.SWIMMING);
			if (now % 20 == 0) {
				sweepTargets(player);
				ServerBossEvent bar = bars.get(player.getUUID());
				if (bar == null) {
					applyPresentation(player, rec);
				} else {
					updateBar(player, rec, bar, now);
					for (ServerPlayer viewer : server.getPlayerList().getPlayers()) bar.addPlayer(viewer); // idempotent; covers late joiners
				}
			}
		}
	}

	public void onViewerJoined(ServerPlayer viewer) { bars.values().forEach(bar -> bar.addPlayer(viewer)); }

	/** Respawn (death or End portal) creates a new ServerPlayer; swap it into every bar so no stale entity keeps receiving packets. */
	public void onViewerRespawned(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		bars.values().forEach(bar -> {
			bar.removePlayer(oldPlayer);
			bar.addPlayer(newPlayer);
		});
	}

	public void onPlayerLeft(ServerPlayer player) { bars.values().forEach(bar -> bar.removePlayer(player)); }

	private void applyPresentation(ServerPlayer player, PlayerRecord rec) {
		player.setGlowingTag(true);
		applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID, Rules.DOWNED_SPEED_MULTIPLIER);
		applyModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID, Rules.DOWNED_JUMP_MULTIPLIER);
		player.setPose(Pose.SWIMMING);
		ServerBossEvent bar = bars.computeIfAbsent(player.getUUID(),
				id -> new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
		updateBar(player, rec, bar, state.now());
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) bar.addPlayer(viewer);
	}

	private void updateBar(ServerPlayer player, PlayerRecord rec, ServerBossEvent bar, long now) {
		long remaining = rec.downedTicksRemaining(now);
		String hint = rec.isDownedPaused() ? "clock paused while being revived" : "right-click to revive";
		bar.setName(Component.literal("☠ " + name(player) + " is downed · " + Text.mmss(remaining) + " · " + hint));
		bar.setProgress((float) remaining / (float) Rules.DOWNED_DURATION_TICKS);
	}

	private void sweepTargets(ServerPlayer player) {
		AABB box = player.getBoundingBox().inflate(SWEEP_RADIUS);
		for (Mob mob : player.level().getEntitiesOfClass(Mob.class, box, m -> m.getTarget() == player)) mob.setTarget(null);
		for (Warden warden : player.level().getEntitiesOfClass(Warden.class, box)) warden.clearAnger(player);
	}

	private static void applyModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
		AttributeInstance attr = player.getAttribute(attribute);
		if (attr == null) return;
		attr.removeModifier(id);
		attr.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	private static void removeModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
		AttributeInstance attr = player.getAttribute(attribute);
		if (attr != null) attr.removeModifier(id);
	}

	private static String name(ServerPlayer player) { return player.getGameProfile().name(); }
}
