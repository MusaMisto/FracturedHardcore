package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class DeathGameTests {
	private static void clickRespawn(ServerPlayer p) {
		p.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
	}

	@GameTest
	public void trueDeathReducesHeartsOnRespawn(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death1", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			helper.assertTrue(p.isDeadOrDying(), "dead");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted once");
			end = helper.getLevel().getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
			helper.assertValueEqual((double) end.getMaxHealth(), 16.0, "8 hearts after first death");
			helper.assertValueEqual(end.getHealth(), 16.0f, "respawned at the new maximum");
			helper.assertValueEqual(end.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base value owned");
			helper.assertValueEqual(end.gameMode(), GameType.SURVIVAL, "survival");
		} finally {
			TestPlayers.leave(end);
		}
		helper.succeed();
	}

	@GameTest
	public void respawnButtonKeepsSurvivalUntilElimination(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death2", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			Hc.state().set(p.getUUID(), 2, 0, "test");
			JoinHandler.onJoin(p);
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			clickRespawn(p);
			end = p.connection.player;
			helper.assertTrue(end != p, "new player entity after respawn");
			helper.assertValueEqual(end.gameMode(), GameType.SURVIVAL, "third death still respawns");
			helper.assertValueEqual((double) end.getMaxHealth(), 8.0, "4 hearts on final life");
			helper.assertTrue(Hc.state().get(end.getUUID()).finalLife(), "final life flag");
		} finally {
			TestPlayers.leave(end);
		}
		helper.succeed();
	}

	@GameTest
	public void finalLifeDiesOutrightAndEliminationSpectates(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death3", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			Hc.state().set(p.getUUID(), 3, 0, "test");
			JoinHandler.onJoin(p);
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f); // ordinary damage: no downed safety net
			helper.assertTrue(p.isDeadOrDying(), "final life has no safety net");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 4, "eliminated");
			clickRespawn(p);
			end = p.connection.player;
			helper.assertValueEqual(end.gameMode(), GameType.SPECTATOR, "stock hardcore spectator behaviour after elimination");
		} finally {
			TestPlayers.leave(end);
		}
		helper.succeed();
	}

	@GameTest
	public void totemIsFinalLifeInsurance(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death4", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 3, 0, "test");
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f);
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "totem saved the player");
			helper.assertTrue(p.getOffhandItem().isEmpty(), "totem consumed");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 3, "no death counted for a totem save");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void totemIsNotConsumedBeforeFinalLife(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death5", new Vec3(4, 2, 4));
		try {
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f);
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "downed takes precedence");
			helper.assertTrue(p.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "totem kept");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
