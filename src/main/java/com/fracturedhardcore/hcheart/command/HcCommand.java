package com.fracturedhardcore.hcheart.command;

import java.util.Collection;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.death.Messages;
import com.fracturedhardcore.hcheart.downed.Text;
import com.fracturedhardcore.hcheart.health.HealthService;
import com.fracturedhardcore.hcheart.heart.HeartItem;
import com.fracturedhardcore.hcheart.join.RespawnService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

public final class HcCommand {
	private static final PermissionCheck OP = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);

	private HcCommand() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(Commands.literal("hc")
				.then(Commands.literal("info")
						.executes(HcCommand::infoSelf)
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).requires(Commands.hasPermission(OP)).executes(HcCommand::info)))
				.then(Commands.literal("giveup")
						.executes(HcCommand::giveUpPrompt)
						.then(Commands.literal("confirm").executes(HcCommand::giveUp)))
				.then(Commands.literal("set").requires(Commands.hasPermission(OP))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.then(Commands.argument("deaths", IntegerArgumentType.integer(0))
										.then(Commands.argument("restores", IntegerArgumentType.integer(0)).executes(HcCommand::set)))))
				.then(Commands.literal("reset").requires(Commands.hasPermission(OP))
						.then(Commands.literal("all").executes(HcCommand::resetAll)))
				.then(Commands.literal("give").requires(Commands.hasPermission(OP))
						.then(Commands.argument("player", EntityArgument.player())
								.executes(ctx -> give(ctx, 1))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))));
	}

	private static Services services(CommandContext<CommandSourceStack> ctx) {
		Services s = HcHeartMod.services();
		if (s == null) ctx.getSource().sendFailure(Component.literal("Fractured Hardcore is not ready yet."));
		return s;
	}

	private static int infoSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		ctx.getSource().sendSuccess(() -> describe(s, self.getGameProfile().name(), s.state().get(self.getUUID())), false);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		for (NameAndId profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
			PlayerRecord rec = s.state().get(profile.id());
			ctx.getSource().sendSuccess(() -> describe(s, profile.name(), rec), false);
		}
		return 1;
	}

	/** Step 1 of 2: explain the price and hand out the confirm link. Never kills on its own. */
	private static int giveUpPrompt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		PlayerRecord rec = s.state().get(self.getUUID());
		if (!rec.isDowned()) {
			ctx.getSource().sendFailure(Text.warn("You are not downed."));
			return 0;
		}
		PlayerRecord after = rec.withDeath();
		String tail = after.finalLife() ? " hearts and be on your final life." : " hearts.";
		self.sendSystemMessage(Text.warn("Giving up is a real death. You would respawn with " + after.maxHearts() + tail), false);
		self.sendSystemMessage(Text.link("[Confirm: give up]", "/hc giveup confirm", "Die now and take the penalty")
				.append(Text.info(" or type /hc giveup confirm.")), false);
		return 1;
	}

	/** Step 2 of 2: bleed out now. Refused (no effect) unless the player is downed. */
	private static int giveUp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		if (!s.state().get(self.getUUID()).isDowned()) {
			ctx.getSource().sendFailure(Text.warn("You are not downed."));
			return 0;
		}
		if (!s.downed().giveUp(self)) {
			ctx.getSource().sendFailure(Text.warn("Not possible right now (your client is still loading). Try again in a moment."));
			return 0;
		}
		return 1;
	}

	private static Component describe(Services s, String name, PlayerRecord rec) {
		String status = rec.pendingKill() ? "DEAD (bled out offline; dies on next join)" : rec.eliminated() ? "ELIMINATED" : rec.finalLife() ? "final life" : "alive";
		String downed = rec.isDowned() ? " · DOWNED, " + Text.mmss(rec.downedMillisRemaining(s.state().now())) + " left" + (rec.isDownedPaused() ? " (clock paused: being revived)" : "") : "";
		return Text.info(name + ": deaths " + rec.deaths() + " · restores used " + rec.restoresUsed() + " · max " + rec.maxHearts()
				+ " hearts · next Heart costs " + Messages.hearts(rec.restoreCost()) + " · " + status + downed);
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		int deaths = IntegerArgumentType.getInteger(ctx, "deaths");
		int restores = IntegerArgumentType.getInteger(ctx, "restores");
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
		for (NameAndId profile : profiles) {
			s.state().getOrCreate(profile.id(), profile.name());
			PlayerRecord rec = s.state().set(profile.id(), deaths, restores, ctx.getSource().getTextName());
			ServerPlayer online = s.server().getPlayerList().getPlayer(profile.id());
			if (online != null) applyLive(s, online);
			ctx.getSource().sendSuccess(() -> describe(s, profile.name(), rec), true);
		}
		return profiles.size();
	}

	private static int resetAll(CommandContext<CommandSourceStack> ctx) {
		Services s = services(ctx);
		if (s == null) return 0;
		int count = s.state().all().size();
		s.state().resetAll(ctx.getSource().getTextName());
		for (ServerPlayer online : s.server().getPlayerList().getPlayers()) {
			s.state().getOrCreate(online.getUUID(), online.getGameProfile().name());
			applyLive(s, online);
			HealthService.refill(online);
		}
		ctx.getSource().sendSuccess(() -> Text.gold("Fractured Hardcore: wiped " + count + " record(s); everyone is back to 10 hearts."), true);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		Services s = services(ctx);
		if (s == null) return 0;
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		ItemStack hearts = HeartItem.create(count);
		if (!target.getInventory().add(hearts)) target.drop(hearts, false);
		s.state().audit().log(target.getUUID(), target.getGameProfile().name(), "GIVE", count + " heart(s) by " + ctx.getSource().getTextName());
		ctx.getSource().sendSuccess(() -> Text.gold("Gave " + Messages.hearts(count) + " to " + target.getGameProfile().name() + "."), true);
		return count;
	}

	/** Re-derive everything for an online player after a manual state change (mirrors the join handler). */
	public static void applyLive(Services s, ServerPlayer player) {
		PlayerRecord rec = s.state().get(player.getUUID());
		s.downed().clear(player, "admin");
		HealthService.normalize(player, rec);
		if (rec.eliminated()) {
			if (!player.isSpectator()) player.setGameMode(GameType.SPECTATOR);
		} else if (player.isSpectator()) {
			RespawnService.rescueFromSpectator(player);
		}
		s.state().scoreboard().sync(rec);
	}
}
