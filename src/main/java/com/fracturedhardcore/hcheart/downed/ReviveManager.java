package com.fracturedhardcore.hcheart.downed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.core.ReviveRules;
import com.fracturedhardcore.hcheart.core.ReviveRules.BreakReason;
import com.fracturedhardcore.hcheart.core.ReviveRules.Hunger;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One 8-second channel per downed target; first reviver wins. Ticked from END_SERVER_TICK. */
public final class ReviveManager {
	private static final class Channel {
		final UUID reviver;
		final UUID target;
		final Vec3 start;
		int elapsed;
		int lastReviverHurtTime;
		int lastTargetHurtTime;

		Channel(ServerPlayer reviver, ServerPlayer target) {
			this.reviver = reviver.getUUID();
			this.target = target.getUUID();
			this.start = reviver.position();
			this.lastReviverHurtTime = reviver.hurtTime;
			this.lastTargetHurtTime = target.hurtTime;
		}
	}

	private final MinecraftServer server;
	private final HeartStateService state;
	private final DownedManager downed;
	private final Map<UUID, Channel> byTarget = new HashMap<>();

	public ReviveManager(MinecraftServer server, HeartStateService state, DownedManager downed) {
		this.server = server;
		this.state = state;
		this.downed = downed;
	}

	public boolean isChanneling(UUID target) { return byTarget.containsKey(target); }

	public InteractionResult tryStart(ServerPlayer reviver, ServerPlayer target) {
		if (reviver == target || !state.get(target.getUUID()).isDowned()) return InteractionResult.PASS;
		if (downed.isDowned(reviver) || reviver.isSpectator() || reviver.isDeadOrDying()) return InteractionResult.FAIL;
		Channel existing = byTarget.get(target.getUUID());
		if (existing != null) {
			if (existing.reviver.equals(reviver.getUUID())) return InteractionResult.SUCCESS;
			ServerPlayer other = server.getPlayerList().getPlayer(existing.reviver);
			reviver.sendSystemMessage(Text.warn(name(target) + " is already being revived by " + (other != null ? name(other) : "someone else") + "."), true);
			return InteractionResult.FAIL;
		}
		if (!ReviveRules.canStart(reviver.getFoodData().getFoodLevel())) {
			reviver.sendSystemMessage(Text.warn("You need at least " + Rules.REVIVE_MIN_FOOD + " food points (3 drumsticks) to revive someone."), true);
			return InteractionResult.FAIL;
		}
		byTarget.put(target.getUUID(), new Channel(reviver, target));
		downed.pauseClock(target.getUUID(), "revive by " + name(reviver)); // persisted: a crash mid-channel keeps the time that was left
		reviver.sendSystemMessage(Text.good("Reviving " + name(target) + "… stay within 2 blocks."), true);
		target.sendSystemMessage(Text.good(name(reviver) + " is reviving you… hold still."), true);
		return InteractionResult.SUCCESS;
	}

	public void tick() {
		if (byTarget.isEmpty()) return;
		for (Channel ch : List.copyOf(byTarget.values())) {
			ServerPlayer reviver = server.getPlayerList().getPlayer(ch.reviver);
			ServerPlayer target = server.getPlayerList().getPlayer(ch.target);
			if (reviver == null || target == null) {
				end(ch, BreakReason.PLAYER_LEFT, reviver, target);
				continue;
			}
			boolean reviverHurt = reviver.hurtTime > ch.lastReviverHurtTime;
			boolean targetHurt = target.hurtTime > ch.lastTargetHurtTime;
			ch.lastReviverHurtTime = reviver.hurtTime;
			ch.lastTargetHurtTime = target.hurtTime;
			boolean reviverAvailable = reviver.isAlive() && !reviver.isSpectator() && !downed.isDowned(reviver) && reviver.level() == target.level();
			BreakReason reason = ReviveRules.check(reviver.position().distanceToSqr(ch.start), reviver.distanceToSqr(target), reviverHurt, targetHurt,
					reviver.getFoodData().getFoodLevel(), target.getFoodData().getFoodLevel(), state.get(ch.target).isDowned(), reviverAvailable);
			if (reason != BreakReason.NONE) {
				end(ch, reason, reviver, target);
				continue;
			}
			ch.elapsed++;
			if (ReviveRules.drainsAt(ch.elapsed)) {
				drainOne(reviver);
				drainOne(target);
			}
			if (ReviveRules.playsNoteAt(ch.elapsed)) {
				target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.NOTE_BLOCK_PLING, SoundSource.PLAYERS, 0.8f, ReviveRules.notePitch(ch.elapsed));
			}
			if (ReviveRules.isComplete(ch.elapsed)) {
				succeed(ch, reviver, target);
				continue;
			}
			if (ch.elapsed % 4 == 0) {
				int pct = ReviveRules.progressPercent(ch.elapsed);
				reviver.sendSystemMessage(Text.good("Reviving " + name(target) + "… " + pct + "%"), true);
				target.sendSystemMessage(Text.good(name(reviver) + " is reviving you… " + pct + "%"), true);
			}
		}
	}

	/** Cancels any channel the player takes part in (used on disconnect). */
	public void cancelFor(UUID player) {
		for (Channel ch : List.copyOf(byTarget.values())) {
			if (ch.target.equals(player) || ch.reviver.equals(player)) {
				end(ch, BreakReason.PLAYER_LEFT, server.getPlayerList().getPlayer(ch.reviver), server.getPlayerList().getPlayer(ch.target));
			}
		}
	}

	/** Cancels the channel on a target that stopped being downed (death, admin reset). */
	public void cancelTarget(UUID target) {
		Channel ch = byTarget.remove(target);
		if (ch == null) return;
		downed.resumeClock(target, "channel cancelled");
		ServerPlayer reviver = server.getPlayerList().getPlayer(ch.reviver);
		if (reviver != null) reviver.sendSystemMessage(Text.warn("Revive interrupted: they are no longer downed."), true);
	}

	private void succeed(Channel ch, ServerPlayer reviver, ServerPlayer target) {
		byTarget.remove(ch.target);
		downed.clear(target, "revived by " + name(reviver));
		target.setHealth(target.getMaxHealth());
		// Completion chime: a bright amethyst ring with a light level-up sparkle on top.
		target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.2f);
		target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.5f);
		server.getPlayerList().broadcastSystemMessage(Text.good(name(reviver) + " revived " + name(target) + "!"), false);
		state.audit().log(target.getUUID(), name(target), "REVIVED", "by " + name(reviver));
	}

	private void end(Channel ch, BreakReason reason, @Nullable ServerPlayer reviver, @Nullable ServerPlayer target) {
		byTarget.remove(ch.target);
		downed.resumeClock(ch.target, "revive broke: " + reason); // by UUID: works even if the downed player just disconnected
		ServerPlayer at = target != null ? target : reviver;
		if (at != null) at.level().playSound(null, at.getX(), at.getY(), at.getZ(), SoundEvents.NOTE_BLOCK_BASS, SoundSource.PLAYERS, 0.8f, 0.5f); // low note: the scale broke
		String why = switch (reason) {
			case REVIVER_MOVED -> "the reviver moved away.";
			case TOO_FAR_APART -> "you are too far apart.";
			case REVIVER_HURT -> "the reviver took damage.";
			case TARGET_HURT -> "the downed player took damage.";
			case REVIVER_STARVING -> "the reviver ran out of food.";
			case TARGET_STARVING -> "the downed player ran out of food.";
			case TARGET_NOT_DOWNED -> "they are no longer downed.";
			case REVIVER_UNAVAILABLE -> "the reviver can no longer help.";
			case PLAYER_LEFT -> "a player left.";
			case NONE -> "";
		};
		if (reviver != null) reviver.sendSystemMessage(Text.warn("Revive interrupted: " + why), true);
		if (target != null) target.sendSystemMessage(Text.warn("Revive interrupted: " + why), true);
	}

	private static void drainOne(ServerPlayer player) {
		FoodData food = player.getFoodData();
		Hunger after = new Hunger(food.getFoodLevel(), food.getSaturationLevel()).drainOne();
		food.setFoodLevel(after.food());
		food.setSaturation(after.saturation());
	}

	private static String name(ServerPlayer p) { return p.getGameProfile().name(); }
}
