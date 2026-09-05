package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class DownedGameTests {
	static void lethal(ServerPlayer p) { p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f); }

	@GameTest
	public void lethalDamageEntersDownedInsteadOfDeath(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed1", new Vec3(4, 2, 4));
		try {
			lethal(p);
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "player survives");
			helper.assertValueEqual(p.getHealth(), 1.0f, "health pinned at 1");
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "downed flag persisted");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).downedUntilTick(), Hc.state().now() + 3600L, "180 s timer against world time");
			helper.assertTrue(p.hasGlowingTag(), "glowing");
			helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "prone");
			helper.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(DownedManager.SPEED_ID), "slowed");
			helper.assertTrue(p.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(DownedManager.JUMP_ID), "no jump");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "no death counted");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void downedPlayerIsImmuneToOrdinaryDamage(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed2", new Vec3(4, 2, 4));
		try {
			lethal(p);
			boolean hurt = p.hurtServer(p.level(), p.level().damageSources().generic(), 5f);
			helper.assertFalse(hurt, "damage rejected");
			helper.assertValueEqual(p.getHealth(), 1.0f, "still 1 HP");
			helper.assertTrue(p.isAlive(), "alive");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void bypassDamageKillsDownedPlayerForReal(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed3", new Vec3(4, 2, 4));
		try {
			lethal(p);
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			helper.assertTrue(p.isDeadOrDying(), "dead");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
			helper.assertFalse(Hc.state().get(p.getUUID()).isDowned(), "downed cleared");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void mobsDropAndCannotReacquireDownedTarget(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed4", new Vec3(4, 2, 4));
		Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 2, 1));
		zombie.setTarget(p);
		helper.assertTrue(zombie.getTarget() == p, "zombie targets a healthy player");
		helper.assertTrue(zombie.canAttack(p), "canAttack before");
		lethal(p);
		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(zombie.getTarget() == null, "target cleared by sweep");
				helper.assertFalse(zombie.canAttack(p), "cannot re-target a downed player");
				helper.assertFalse(TargetingConditions.forCombat().test(helper.getLevel(), zombie, p), "TargetingConditions rejects");
				zombie.setTarget(p);
				helper.assertTrue(zombie.getTarget() == null, "setTarget filtered out");
			} finally {
				TestPlayers.leave(p);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void wardenCannotTargetDownedPlayer(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed5", new Vec3(4, 2, 4));
		try {
			Warden warden = helper.spawnWithNoFreeWill(EntityTypes.WARDEN, new BlockPos(1, 2, 1));
			helper.assertTrue(warden.canTargetEntity(p), "warden can target a healthy survival player");
			lethal(p);
			helper.assertFalse(warden.canTargetEntity(p), "warden ignores downed player");
			helper.assertFalse(warden.canAttack(p), "warden canAttack false");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void bleedOutKillsAndCountsDeath(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed6", new Vec3(4, 2, 4));
		lethal(p);
		Hc.state().enterDowned(p.getUUID(), Hc.state().now() + 20); // shorten the 180 s clock for the test
		helper.runAfterDelay(30, () -> {
			try {
				helper.assertTrue(p.isDeadOrDying(), "bled out");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
				helper.assertFalse(Hc.state().get(p.getUUID()).isDowned(), "downed cleared");
				helper.assertFalse(p.hasGlowingTag(), "glow removed");
			} finally {
				TestPlayers.leave(p);
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void proneHitboxDoesNotSuffocateInOneBlockGap(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed7", new Vec3(4.5, 1, 4.5));
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE); // ceiling directly above the feet block: a 1-block gap
		lethal(p);
		helper.runAfterDelay(40, () -> {
			try {
				helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "still prone");
				helper.assertFalse(p.isInWall(), "eyes not inside a block");
				helper.assertTrue(p.isAlive(), "alive");
				helper.assertValueEqual(p.getHealth(), 1.0f, "no suffocation damage");
			} finally {
				TestPlayers.leave(p);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void reloginResolvesDownedState(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed8", new Vec3(4, 2, 4));
		try {
			lethal(p);
			Hc.downed().clearPresentation(p); // simulate a crash: state kept, presentation lost
			helper.assertFalse(p.hasGlowingTag(), "presentation gone");
			JoinHandler.onJoin(p); // rejoin with time remaining
			helper.assertTrue(p.hasGlowingTag(), "re-entered downed");
			helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "prone again");
			Hc.state().enterDowned(p.getUUID(), 1L); // rejoin after the clock expired
			JoinHandler.onJoin(p);
			helper.assertTrue(p.isDeadOrDying(), "bled out on join");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
