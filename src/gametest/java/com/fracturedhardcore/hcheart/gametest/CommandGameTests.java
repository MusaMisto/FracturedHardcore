package com.fracturedhardcore.hcheart.gametest;

import java.util.UUID;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class CommandGameTests {
	private static void run(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	/** Run a command as the player themselves (no op permission). */
	private static void runAs(ServerPlayer p, String command) {
		MinecraftServer server = p.level().getServer();
		server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
	}

	@GameTest
	public void setWritesCountersAndReappliesLiveState(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_set", new Vec3(4, 2, 4));
		try {
			run(helper, "hc set cmd_set 2 1");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 2, "deaths");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 1, "restores");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "live max health re-derived");
			run(helper, "hc set cmd_set 4 0");
			helper.assertValueEqual(p.gameMode(), GameType.SPECTATOR, "eliminated by admin -> spectator");
			run(helper, "hc set cmd_set 0 0");
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "rescued");
			helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "full cap");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	/** Runs in its own batch: `hc reset all` is global and would wipe the records of players in concurrently running tests. */
	@GameTest(environment = "hcheart-gametest:isolated")
	public void resetAllWipesEveryoneAndNormalisesOnlinePlayers(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_reset", new Vec3(4, 2, 4));
		try {
			run(helper, "hc set cmd_reset 3 2");
			p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(18.0);
			p.setGameMode(GameType.SPECTATOR);
			run(helper, "hc reset all");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "deaths wiped");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 0, "restores wiped");
			helper.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base normalised");
			helper.assertValueEqual(p.getHealth(), 20.0f, "full health");
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "back to survival");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void giveUpWhileDownedIsATrueDeath(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_giveup", new Vec3(4, 2, 4));
		try {
			DownedGameTests.lethal(p);
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "downed");
			runAs(p, "hc giveup"); // step 1: prompt only
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "the prompt alone never kills");
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "still downed after the prompt");
			runAs(p, "hc giveup confirm"); // step 2
			helper.assertTrue(p.isDeadOrDying(), "dead");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "counted as a true death");
			helper.assertFalse(Hc.state().get(p.getUUID()).isDowned(), "downed cleared");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	/** Vanilla rejects every kind of damage until the client reports loaded: a give-up then must refuse cleanly, announcing nothing. */
	@GameTest
	public void giveUpWhileUnkillableAnnouncesNothing(GameTestHelper helper) {
		ServerPlayer viewer = TestPlayers.join(helper, "cmd_gu_view", new Vec3(2, 2, 2));
		UUID id = UUID.randomUUID();
		Hc.state().getOrCreate(id, "cmd_giveup3");
		Hc.state().enterDowned(id, Hc.state().now() + 100_000L);
		ServerPlayer p = TestPlayers.join(helper, "cmd_giveup3", new Vec3(4, 2, 4), id, false);
		try {
			TestPlayers.drainSent(viewer);
			TestPlayers.drainSent(p);
			runAs(p, "hc giveup confirm");
			helper.assertTrue(p.isAlive() && Hc.state().get(id).isDowned(), "still downed and alive");
			helper.assertValueEqual(Hc.state().get(id).deaths(), 0, "nothing counted");
			helper.assertTrue(TestPlayers.drainSent(p).stream().anyMatch(k -> k instanceof ClientboundSystemChatPacket c && c.content().getString().contains("Try again")), "told to retry, not 'not downed'");
			helper.assertFalse(TestPlayers.drainSent(viewer).stream().anyMatch(k -> k instanceof ClientboundSystemChatPacket c && c.content().getString().contains("gave up")), "nobody was told they gave up");
		} finally {
			TestPlayers.leave(p);
			TestPlayers.leave(viewer);
		}
		helper.succeed();
	}

	@GameTest
	public void giveUpIsRefusedWhenNotDowned(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_giveup2", new Vec3(4, 2, 4));
		try {
			runAs(p, "hc giveup confirm");
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "alive");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "no death counted");
			helper.assertValueEqual(p.getHealth(), 20.0f, "health untouched");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void giveHandsOutHeartsAndInfoRuns(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_give", new Vec3(4, 2, 4));
		try {
			run(helper, "hc give cmd_give 3");
			helper.assertValueEqual(HeartItem.count(p), 3, "three hearts given");
			run(helper, "hc give cmd_give");
			helper.assertValueEqual(HeartItem.count(p), 4, "default count is 1");
			run(helper, "hc info cmd_give");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
