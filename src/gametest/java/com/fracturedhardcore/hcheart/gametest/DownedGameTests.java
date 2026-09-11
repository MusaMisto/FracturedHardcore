package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;

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
			long until = Hc.state().get(p.getUUID()).downedUntilMs();
			helper.assertTrue(Math.abs(until - (Hc.state().now() + 180_000L)) < 100L, "180 s wall-clock deadline, got " + until);
			helper.assertTrue(p.hasGlowingTag(), "glowing");
			helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "prone");
			helper.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(DownedManager.SPEED_ID), "slowed");
			helper.assertTrue(Math.abs(p.getAttributeValue(Attributes.MOVEMENT_SPEED) - 0.1 * 0.25) < 1e-9, "quarter of the 0.1 base speed");
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
		Hc.state().enterDowned(p.getUUID(), Hc.state().now() - 1); // the 180 s clock has just run out
		helper.runAfterDelay(2, () -> {
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
	public void pausedClockResumesOnJoinAndByTheTickRepair(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed9", new Vec3(4, 2, 4));
		try {
			lethal(p);
			Hc.state().enterDowned(p.getUUID(), Hc.state().now() + 10_000L);
			Hc.state().pauseDowned(p.getUUID(), "test");
			helper.assertTrue(Hc.state().get(p.getUUID()).isDownedPaused(), "paused");
			JoinHandler.onJoin(p); // relog after a crash mid-revive: no channel exists any more
			PlayerRecord rec = Hc.state().get(p.getUUID());
			helper.assertFalse(rec.isDownedPaused(), "resumed on join");
			helper.assertTrue(Math.abs(rec.downedUntilMs() - (Hc.state().now() + 10_000L)) < 100L, "from what was left");
			helper.assertTrue(p.isAlive() && p.hasGlowingTag(), "still downed and alive");
			Hc.state().pauseDowned(p.getUUID(), "test");
			Hc.downed().tick(); // the per-tick repair catches a stale pause too
			helper.assertFalse(Hc.state().get(p.getUUID()).isDownedPaused(), "resumed by the tick repair");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	/**
	 * Production bug (0.1.3): a player whose clock expired while offline joined and was "self-revived". Vanilla 26.2 makes a
	 * player invulnerable to everything, generic_kill included, until the client reports loaded; the join handler runs before
	 * that, so the bleed-out kill cannot land at JOIN time. The state must survive that and the kill must land once the client
	 * has loaded, counted exactly once.
	 */
	@GameTest(maxTicks = 40)
	public void expiredClockOnJoinBeforeClientLoadsIsNotAFreeRevive(GameTestHelper helper) {
		UUID id = UUID.randomUUID();
		Hc.state().getOrCreate(id, "downed10");
		Hc.state().enterDowned(id, Hc.state().now() - 1); // expired, as after a night offline
		ServerPlayer p = TestPlayers.join(helper, "downed10", new Vec3(4, 2, 4), id, false);
		helper.assertTrue(p.isInvulnerableTo(helper.getLevel(), p.damageSources().genericKill()), "vanilla: unkillable until the client loads");
		helper.assertTrue(Hc.state().get(id).isDowned(), "downed state kept: no free revive because the kill could not land yet");
		helper.assertValueEqual(Hc.state().get(id).deaths(), 0, "nothing counted yet");
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(p.isAlive() && Hc.state().get(id).isDowned(), "still pending while the client is loading");
			TestPlayers.markClientLoaded(p);
		});
		helper.runAfterDelay(10, () -> {
			try {
				helper.assertTrue(p.isDeadOrDying(), "killed once the client had loaded");
				helper.assertValueEqual(Hc.state().get(id).deaths(), 1, "counted exactly once");
				helper.assertFalse(Hc.state().get(id).isDowned(), "downed cleared by the death");
			} finally {
				TestPlayers.leave(p);
			}
			helper.succeed();
		});
	}

	/**
	 * Production bug (0.1.3): a downed player who disconnected was skipped by the tick loop, so their boss bar froze and the
	 * clock was only resolved when they came back. The brief: the clock runs regardless; at zero they are dead, online or not.
	 * Own batch: it jumps the shared wall clock.
	 */
	@GameTest(environment = "hcheart-gametest:clock_offline", maxTicks = 100)
	public void offlineDownedPlayerBleedsOutOnTime(GameTestHelper helper) {
		ServerPlayer viewer = TestPlayers.join(helper, "off_viewer", new Vec3(2, 2, 2));
		UUID id = UUID.randomUUID();
		ServerPlayer d = TestPlayers.join(helper, "off_downed", new Vec3(4, 2, 4), id, true);
		long[] offset = {0L};
		Hc.state().setClock(() -> System.currentTimeMillis() + offset[0]);
		Runnable restore = () -> {
			Hc.state().setClock(System::currentTimeMillis);
			TestPlayers.leave(viewer);
		};
		try {
			lethal(d);
			helper.assertTrue(Hc.state().get(id).isDowned(), "downed");
			helper.assertTrue(String.valueOf(Hc.downed().barName(id)).contains("3:00"), "bar starts at 3:00");
			TestPlayers.leave(d); // disconnects while downed
			offset[0] = 30_000L; // half a minute passes with them offline
		} catch (RuntimeException | AssertionError e) {
			restore.run();
			throw e;
		}
		helper.runAfterDelay(21, () -> { // at least one once-a-second bar refresh has happened
			ServerPlayer back = null;
			try {
				String bar = Hc.downed().barName(id);
				helper.assertTrue(bar != null && bar.matches(".*2:[23]\\d.*"), "bar keeps counting while they are offline: " + bar);
				TestPlayers.drainSent(viewer);
				offset[0] = 181_000L; // the clock runs out while they are still offline
				Hc.downed().tick();
				PlayerRecord rec = Hc.state().get(id);
				helper.assertValueEqual(rec.deaths(), 1, "death counted at expiry, offline");
				helper.assertFalse(rec.isDowned(), "no longer downed");
				helper.assertTrue(rec.pendingKill(), "the vanilla kill is owed on their next join");
				helper.assertTrue(Hc.downed().barName(id) == null, "bar gone");
				var sent = TestPlayers.drainSent(viewer);
				boolean told = sent.stream()
						.anyMatch(p -> p instanceof ClientboundSystemChatPacket c && c.content().getString().contains("off_downed bled out while offline"));
				helper.assertTrue(told, "everyone online was told; viewer got " + sent.size() + " packets: "
						+ sent.stream().map(p -> p instanceof ClientboundSystemChatPacket c ? "chat[" + c.content().getString() + "]" : p.getClass().getSimpleName()).distinct().toList());
				back = TestPlayers.join(helper, "off_downed", new Vec3(4, 2, 4), id, false);
				Hc.downed().tick();
				helper.assertTrue(back.isAlive() && Hc.state().get(id).pendingKill(), "not killable before the client loads; still owed");
				TestPlayers.markClientLoaded(back);
				Hc.downed().tick();
				helper.assertTrue(back.isDeadOrDying(), "killed once the client had loaded");
				helper.assertValueEqual(Hc.state().get(id).deaths(), 1, "still one death: never counted twice");
				helper.assertFalse(Hc.state().get(id).pendingKill(), "settled");
			} finally {
				if (back != null) TestPlayers.leave(back);
				restore.run();
			}
			helper.succeed();
		});
	}

	/** Upgrade path: records written by 0.1.3 (world-tick deadlines) are converted exactly on the first tick, or owe the death. */
	@GameTest
	public void legacyTickClocksConvertOnTheFirstTick(GameTestHelper helper) {
		long worldTime = helper.getLevel().getServer().overworld().getGameTime();
		UUID running = UUID.randomUUID(), expired = UUID.randomUUID(), paused = UUID.randomUUID();
		Hc.state().getOrCreate(running, "legacy_running");
		Hc.state().getOrCreate(expired, "legacy_expired");
		Hc.state().getOrCreate(paused, "legacy_paused");
		try {
			Hc.state().importLegacyDowned(running, worldTime + 100, 0);   // 100 ticks left under 0.1.3
			Hc.state().importLegacyDowned(expired, worldTime - 1, 0);     // had already run out under 0.1.3
			Hc.state().importLegacyDowned(paused, worldTime + 1, 381);    // mid-revive at the crash: 381 ticks frozen
			Hc.downed().tick();
			PlayerRecord r = Hc.state().get(running);
			helper.assertTrue(r.isDowned() && !r.hasLegacyClock(), "converted to a wall-clock deadline");
			long left = r.downedMillisRemaining(Hc.state().now());
			helper.assertTrue(left > 4_800L && left <= 5_000L, "100 ticks = 5 s, got " + left);
			PlayerRecord e = Hc.state().get(expired);
			helper.assertValueEqual(e.deaths(), 1, "a clock that had run out owes the death");
			helper.assertTrue(e.pendingKill() && !e.isDowned(), "offline: kill owed on next join");
			PlayerRecord p = Hc.state().get(paused);
			helper.assertTrue(p.isDowned() && !p.hasLegacyClock() && !p.isDownedPaused(), "paused remainder resumed as a wall-clock deadline");
			long pl = p.downedMillisRemaining(Hc.state().now());
			helper.assertTrue(pl > 18_900L && pl <= 19_050L, "381 ticks = 19.05 s, got " + pl);
		} finally {
			for (UUID id : new UUID[] {running, expired, paused}) Hc.state().set(id, 0, 0, "test cleanup");
		}
		helper.succeed();
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
			Hc.state().enterDowned(p.getUUID(), Hc.state().now() - 1); // rejoin after the clock expired
			JoinHandler.onJoin(p);
			helper.assertTrue(p.isAlive() && Hc.state().get(p.getUUID()).isDowned(), "join never resolves an expired clock itself (the client may not have loaded)");
			Hc.downed().tick();
			helper.assertTrue(p.isDeadOrDying(), "the tick lands the kill once vanilla accepts damage");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
