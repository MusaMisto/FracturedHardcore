package com.fracturedhardcore.hcheart.downed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.death.Sounds;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import org.jspecify.annotations.Nullable;

/** Entry, per-tick enforcement, bleed-out, teardown and crash recovery of the downed state. */
public final class DownedManager {
	public static final Identifier SPEED_ID = HcHeart.id("downed_speed");
	public static final Identifier JUMP_ID = HcHeart.id("downed_jump");
	private static final double SWEEP_RADIUS = 48.0;

	private final MinecraftServer server;
	private final HeartStateService state;
	private ReviveManager revive;
	private final Map<UUID, ServerBossEvent> bars = new HashMap<>();
	/** Players whose kill is waiting for vanilla to accept damage (client still loading); used only to log once. */
	private final Set<UUID> killWaiting = new HashSet<>();
	/** Records that need work this tick (downed or owing a kill). Reused so an idle tick allocates nothing. */
	private final List<UUID> active = new ArrayList<>();

	public DownedManager(MinecraftServer server, HeartStateService state) {
		this.server = server;
		this.state = state;
	}

	public void attachRevive(ReviveManager revive) { this.revive = revive; }

	/**
	 * Downed, or bled out offline and awaiting the owed kill ({@link PlayerRecord#pendingKill()}): incapacitated either way
	 * (interaction lock, no Heart, cannot revive, ignored by mobs).
	 */
	public boolean isDowned(ServerPlayer player) {
		PlayerRecord rec = state.get(player.getUUID());
		return rec.isDowned() || rec.pendingKill();
	}

	/** Mixin entry point: a cheap map lookup, safe before the server has started. */
	public static boolean isDownedPlayer(Player player) {
		Services s = HcHeartMod.services();
		return s != null && player instanceof ServerPlayer sp && s.downed().isDowned(sp);
	}

	public void enter(ServerPlayer player) {
		long until = state.now() + Rules.DOWNED_DURATION_MS;
		PlayerRecord rec = state.enterDowned(player.getUUID(), until); // persist FIRST
		player.setHealth(1.0f);
		player.clearFire();
		player.stopUsingItem();
		applyPresentation(player, rec);
		sweepTargets(player);
		String where = player.level().dimension().identifier().getPath() + " at "
				+ player.blockPosition().getX() + ", " + player.blockPosition().getY() + ", " + player.blockPosition().getZ();
		server.getPlayerList().broadcastSystemMessage(
				Text.warn(name(player) + " is downed in " + where + " — " + Text.mmss(Rules.DOWNED_DURATION_MS) + " to revive them."), false);
		player.sendSystemMessage(Text.gold("You are downed. Crawl to safety — a friend can right-click you to revive you. The clock stops while they do."), false);
		player.sendSystemMessage(Text.info("Nobody around? ").append(Text.link("[Give up]", "/hc giveup", "Skip the clock and accept the death now"))
				.append(Text.info(" or type /hc giveup to accept the death now.")), false);
	}

	/**
	 * Player-initiated bleed-out (/hc giveup confirm). Same death path as the clock running out, so the penalty is identical.
	 * The kill is attempted first; the audit line and the broadcast follow only if it landed, so a refused kill (client still
	 * loading, changing dimension, another mod cancelling the damage) announces nothing and changes nothing.
	 *
	 * @return true if the player is dead now; false if they are not downed or the kill could not land right now
	 */
	public boolean giveUp(ServerPlayer player) {
		PlayerRecord rec = state.get(player.getUUID());
		if (!rec.isDowned() || player.isDeadOrDying()) return false;
		String left = Text.mmss(rec.downedMillisRemaining(state.now()));
		if (!bleedOut(player)) return false;
		state.audit().log(player.getUUID(), name(player), "GAVE_UP", left + " was left on the clock");
		server.getPlayerList().broadcastSystemMessage(Text.warn(name(player) + " gave up and accepted the death."), false);
		return true;
	}

	/** A revive channel started on this player: freeze the clock with what is left on it. Idempotent. */
	public void pauseClock(UUID target, String reason) {
		PlayerRecord rec = state.get(target);
		if (rec.isDowned() && !rec.isDownedPaused() && !rec.hasLegacyClock()) state.pauseDowned(target, reason);
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
		killWaiting.remove(player.getUUID());
	}

	/** Presentation only (glow, pose, modifiers, boss bar). Used on join when not downed to clear crash leftovers. */
	public void clearPresentation(ServerPlayer player) {
		player.setGlowingTag(false);
		removeModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID);
		removeModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID);
		if (player.getPose() == Pose.SWIMMING) player.setPose(Pose.STANDING);
		removeBar(player.getUUID());
	}

	/**
	 * The only downed→dead route besides bypass damage. generic_kill bypasses invulnerability, totems and armour, with one
	 * exception: vanilla 26.2 rejects ALL damage until the client reports loaded ({@code ServerPlayer.isInvulnerableTo}), and
	 * the join handler runs before that. So this never touches state: it returns false and the caller retries next tick.
	 * Clearing the downed state on a failed kill was the 0.1.3 "self-revive" bug.
	 *
	 * @return true if the player is dead now
	 */
	public boolean bleedOut(ServerPlayer player) {
		if (player.isDeadOrDying()) return true;
		var kill = player.damageSources().genericKill();
		if (player.isInvulnerableTo(player.level(), kill)) {
			if (killWaiting.add(player.getUUID())) HcHeart.LOGGER.info("Bleed-out of {} waits for their client to finish loading", name(player));
			return false;
		}
		player.hurtServer(player.level(), kill, Float.MAX_VALUE);
		if (!player.isDeadOrDying()) {
			if (killWaiting.add(player.getUUID())) HcHeart.LOGGER.error("Bleed-out kill did not take for {}; state kept, retrying every tick", name(player));
			return false;
		}
		killWaiting.remove(player.getUUID());
		return true;
	}

	/**
	 * Driven by the RECORDS, not by who is online: a downed player who disconnected keeps bleeding, their bar keeps counting for
	 * everyone else, and when the clock runs out the death is counted then and there. Walking the online players only was the
	 * 0.1.3 "frozen bar" bug.
	 */
	public void tick() {
		active.clear();
		state.all().forEach((id, rec) -> { if (rec.isDowned() || rec.pendingKill()) active.add(id); }); // the map's own forEach: no per-record allocation
		if (active.isEmpty()) return;
		boolean second = server.getTickCount() % 20 == 0;
		long overworldTime = server.overworld().getGameTime();
		for (UUID id : active) { // the map is no longer being iterated: commits below are safe
			PlayerRecord rec = state.get(id);
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (rec.pendingKill()) {
				if (player != null) bleedOut(player); // lands once vanilla accepts damage; AFTER_DEATH settles the owed death
				continue;
			}
			if (!rec.isDowned()) continue;
			if (rec.isDownedPaused() && (revive == null || !revive.isChanneling(id))) {
				rec = state.resumeDowned(id, "no revive channel holds it (repair)"); // e.g. the channel died with a crash; also converts a paused 0.1.3 clock exactly
			}
			if (rec.hasLegacyClock()) { // written by 0.1.3 or older in world ticks; world time is persisted, so the remainder is exact
				rec = state.convertLegacyClock(id, overworldTime);
				HcHeart.LOGGER.info("Downed clock of {} came from 0.1.3 (world ticks): {}", rec.lastKnownName(),
						rec.downedExpired(state.now()) ? "it had already run out, the death is owed" : Text.mmss(rec.downedMillisRemaining(state.now())) + " left, converted");
			}
			long now = state.now(); // read AFTER the repairs above: they stamp deadlines with the service clock, and a deadline of "now" must count as expired now
			if (rec.downedExpired(now)) {
				if (player == null) bleedOutOffline(id);
				else bleedOut(player); // no-op once dead; otherwise lands as soon as vanilla accepts damage
				continue;
			}
			boolean live = player != null && !player.isDeadOrDying();
			if (live) {
				if (player.getHealth() != 1.0f) player.setHealth(1.0f);
				if (!player.hasGlowingTag()) player.setGlowingTag(true);
				if (player.getPose() != Pose.SWIMMING) player.setPose(Pose.SWIMMING);
				if (second) sweepTargets(player);
			}
			if (second) {
				if (live && !bars.containsKey(id)) applyPresentation(player, rec); // presentation lost, e.g. with a crash; refreshes the bar too
				else refreshBar(id, rec, now); // from the record alone: keeps counting while they are offline
			}
		}
	}

	/** The clock ran out while the player was offline: count the death now; the vanilla kill is owed on their next join. */
	private void bleedOutOffline(UUID id) {
		PlayerRecord rec = state.recordOfflineBleedOut(id);
		removeBar(id);
		String tail = rec.finalLife() ? " · now on their final life" : "";
		Component line = Text.warn(rec.lastKnownName() + " bled out while offline · deaths " + rec.deaths() + " · " + rec.maxHearts() + " hearts" + tail);
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			other.sendSystemMessage(line, false);
			Sounds.playTo(other, SoundEvents.BELL_RESONATE, SoundSource.MASTER, 0.3f, 0.6f);
		}
	}

	/** Current bar text for a downed player, or null when there is no bar. Diagnostics and tests. */
	public @Nullable String barName(UUID id) {
		ServerBossEvent bar = bars.get(id);
		return bar == null ? null : bar.getName().getString();
	}

	public void onViewerJoined(ServerPlayer viewer) { bars.values().forEach(bar -> bar.addPlayer(viewer)); }

	/** Respawn (death or End portal) creates a new ServerPlayer; swap it into every bar so no stale entity keeps receiving packets. */
	public void onViewerRespawned(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		bars.values().forEach(bar -> {
			bar.removePlayer(oldPlayer);
			bar.addPlayer(newPlayer);
		});
	}

	public void onPlayerLeft(ServerPlayer player) {
		bars.values().forEach(bar -> bar.removePlayer(player));
		killWaiting.remove(player.getUUID());
	}

	private void applyPresentation(ServerPlayer player, PlayerRecord rec) {
		player.setGlowingTag(true);
		applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID, Rules.DOWNED_SPEED_MULTIPLIER);
		applyModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID, Rules.DOWNED_JUMP_MULTIPLIER);
		player.setPose(Pose.SWIMMING);
		refreshBar(player.getUUID(), rec, state.now());
	}

	/** Create or update the bar from the record alone (the player may be offline) and show it to everyone online. */
	private void refreshBar(UUID id, PlayerRecord rec, long now) {
		ServerBossEvent bar = bars.computeIfAbsent(id,
				k -> new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
		long remaining = rec.downedMillisRemaining(now);
		String hint = rec.isDownedPaused() ? "clock paused while being revived" : "right-click to revive";
		bar.setName(Component.literal("☠ " + rec.lastKnownName() + " is downed · " + Text.mmss(remaining) + " · " + hint));
		bar.setProgress((float) remaining / (float) Rules.DOWNED_DURATION_MS);
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) bar.addPlayer(viewer); // idempotent; covers late joiners
	}

	private void removeBar(UUID id) {
		ServerBossEvent bar = bars.remove(id);
		if (bar != null) bar.removeAllPlayers();
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
