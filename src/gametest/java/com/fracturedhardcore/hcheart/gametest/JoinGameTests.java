package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.health.HealthService;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class JoinGameTests {
	@GameTest
	public void joinResetsBaseValueAndAppliesPenalty(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "skillux", new Vec3(4, 2, 4));
		try {
			p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(18.0); // the /attribute damage from the brief
			Hc.state().set(p.getUUID(), 2, 0, "test");
			JoinHandler.onJoin(p); // simulate a rejoin
			helper.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base value");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "max health at 2 deaths");
			helper.assertTrue(p.getHealth() <= 12.0f, "health clamped to the new cap");
			helper.assertTrue(p.getAttribute(Attributes.MAX_HEALTH).hasModifier(HealthService.PENALTY_ID), "penalty modifier present");
			Hc.state().set(p.getUUID(), 0, 0, "test");
			JoinHandler.onJoin(p);
			helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "max health at 0 deaths");
			helper.assertFalse(p.getAttribute(Attributes.MAX_HEALTH).hasModifier(HealthService.PENALTY_ID), "no modifier at 0 deaths");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void joinRescuesStrandedSpectator(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "stranded", new Vec3(4, 2, 4));
		try {
			p.setGameMode(GameType.SPECTATOR);
			p.setHealth(3.0f);
			JoinHandler.onJoin(p);
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "game mode");
			helper.assertValueEqual(p.getHealth(), p.getMaxHealth(), "health refilled");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void joinLeavesEliminatedInSpectator(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "eliminated", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 4, 0, "test");
			p.setGameMode(GameType.SPECTATOR);
			JoinHandler.onJoin(p);
			helper.assertValueEqual(p.gameMode(), GameType.SPECTATOR, "stays spectator");
			helper.assertValueEqual((double) p.getMaxHealth(), 8.0, "floor of 4 hearts");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
