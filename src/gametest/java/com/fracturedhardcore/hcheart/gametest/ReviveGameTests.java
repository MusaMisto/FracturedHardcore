package com.fracturedhardcore.hcheart.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class ReviveGameTests {
	private static ServerPlayer downedTarget(GameTestHelper helper, String name) {
		ServerPlayer t = TestPlayers.join(helper, name, new Vec3(4, 2, 4));
		t.getFoodData().setFoodLevel(17); // < 18 so natural regeneration does not touch saturation during the test
		t.getFoodData().setSaturation(0f);
		DownedGameTests.lethal(t);
		return t;
	}

	private static ServerPlayer reviver(GameTestHelper helper, String name) {
		ServerPlayer r = TestPlayers.join(helper, name, new Vec3(5, 2, 4));
		r.getFoodData().setFoodLevel(20);
		r.getFoodData().setSaturation(5f);
		return r;
	}

	@GameTest(maxTicks = 220)
	public void reviveSucceedsWithNoPenalty(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t1");
		ServerPlayer r = reviver(helper, "rv_r1");
		helper.assertTrue(Hc.revive().tryStart(r, t).consumesAction(), "channel started");
		helper.runAfterDelay(170, () -> {
			try {
				helper.assertFalse(Hc.state().get(t.getUUID()).isDowned(), "target no longer downed");
				helper.assertValueEqual(Hc.state().get(t.getUUID()).deaths(), 0, "no death counted");
				helper.assertValueEqual(t.getHealth(), t.getMaxHealth(), "health restored");
				helper.assertFalse(t.hasGlowingTag(), "glow cleared");
				helper.assertTrue(t.getPose() != Pose.SWIMMING, "pose cleared");
				helper.assertValueEqual(r.getFoodData().getSaturationLevel(), 0f, "reviver saturation drained first (5 points)");
				helper.assertValueEqual(r.getFoodData().getFoodLevel(), 19, "reviver food drained by the sixth point");
				helper.assertValueEqual(t.getFoodData().getFoodLevel(), 11, "target lost 6 food points");
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel gone");
			} finally {
				TestPlayers.leave(t);
				TestPlayers.leave(r);
			}
			helper.succeed();
		});
	}

	/** Own batch: it jumps the shared wall clock, which would expire every other test's downed player. */
	@GameTest(environment = "hcheart-gametest:clock_pause")
	public void reviveChannelPausesTheClockAndABreakResumesIt(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t8");
		ServerPlayer r = reviver(helper, "rv_r8");
		long[] offset = {0L};
		Hc.state().setClock(() -> System.currentTimeMillis() + offset[0]);
		try {
			Hc.state().enterDowned(t.getUUID(), Hc.state().now() + 2_000L); // 2 s left: far less than the 8 s channel
			helper.assertTrue(Hc.revive().tryStart(r, t).consumesAction(), "channel started");
			PlayerRecord rec = Hc.state().get(t.getUUID());
			helper.assertTrue(rec.isDownedPaused(), "clock paused the moment the channel started");
			helper.assertTrue(rec.downedPausedMs() > 1_900L && rec.downedPausedMs() <= 2_000L, "with what was left on it: " + rec.downedPausedMs());
			offset[0] = 60_000L; // a minute passes: the deadline is long gone
			Hc.downed().tick();
			helper.assertTrue(t.isAlive() && !t.isDeadOrDying(), "still alive while being revived");
			helper.assertTrue(Hc.state().get(t.getUUID()).isDowned(), "still downed");
			helper.assertValueEqual(Hc.state().get(t.getUUID()).deaths(), 0, "no death");
			helper.setBlock(new BlockPos(1, 1, 4), Blocks.STONE);
			Vec3 away = helper.absoluteVec(new Vec3(1, 2, 4)); // 4 blocks from the start position: breaks the channel
			r.teleportTo(helper.getLevel(), away.x, away.y, away.z, Set.of(), 0f, 0f, false);
			Hc.revive().tick();
			helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broke");
			rec = Hc.state().get(t.getUUID());
			helper.assertFalse(rec.isDownedPaused(), "clock resumed");
			long left = rec.downedMillisRemaining(Hc.state().now());
			helper.assertTrue(left > 1_900L && left <= 2_000L, "resumed from the 2 s that were left, got " + left);
			Hc.downed().tick();
			helper.assertTrue(t.isAlive(), "the resumed clock has not run out yet");
			offset[0] = 63_000L; // three more seconds
			Hc.downed().tick();
			helper.assertTrue(t.isDeadOrDying(), "bled out once the resumed clock ran down");
			helper.assertValueEqual(Hc.state().get(t.getUUID()).deaths(), 1, "death counted");
		} finally {
			Hc.state().setClock(System::currentTimeMillis);
			TestPlayers.leave(t);
			TestPlayers.leave(r);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 220)
	public void reviveNotesRiseAndEndInAChime(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t7");
		ServerPlayer r = reviver(helper, "rv_r7");
		helper.assertTrue(Hc.revive().tryStart(r, t).consumesAction(), "channel started");
		TestPlayers.drainSent(r); // discard join and downed traffic
		helper.runAfterDelay(170, () -> {
			try {
				List<Float> pitches = new ArrayList<>();
				boolean chime = false;
				for (Packet<?> p : TestPlayers.drainSent(r)) {
					if (!(p instanceof ClientboundSoundPacket s)) continue;
					if (s.getSound().value() == SoundEvents.NOTE_BLOCK_PLING.value()) pitches.add(s.getPitch());
					if (s.getSound().value() == SoundEvents.AMETHYST_BLOCK_CHIME) chime = true;
				}
				helper.assertValueEqual(pitches.size(), 20, "one note every 8 ticks over the 160-tick channel");
				for (int i = 1; i < pitches.size(); i++) helper.assertTrue(pitches.get(i) >= pitches.get(i - 1), "pitch never falls");
				helper.assertTrue(pitches.get(0) < 0.6f, "starts near the bottom of the note-block range");
				helper.assertValueEqual(pitches.get(19), 2.0f, "ends at the top");
				helper.assertTrue(chime, "completion chime reached the reviver");
				helper.assertFalse(Hc.state().get(t.getUUID()).isDowned(), "revived");
			} finally {
				TestPlayers.leave(t);
				TestPlayers.leave(r);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void reviveRefusedWhenReviverIsHungry(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t2");
		ServerPlayer r = reviver(helper, "rv_r2");
		try {
			r.getFoodData().setFoodLevel(5);
			helper.assertValueEqual(Hc.revive().tryStart(r, t), InteractionResult.FAIL, "refused");
			helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "no channel");
		} finally {
			TestPlayers.leave(t);
			TestPlayers.leave(r);
		}
		helper.succeed();
	}

	@GameTest
	public void onlyOneReviverPerTarget(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t3");
		ServerPlayer r1 = reviver(helper, "rv_r3a");
		ServerPlayer r2 = reviver(helper, "rv_r3b");
		try {
			helper.assertTrue(Hc.revive().tryStart(r1, t).consumesAction(), "first reviver starts");
			helper.assertValueEqual(Hc.revive().tryStart(r2, t), InteractionResult.FAIL, "second reviver refused");
			helper.assertTrue(Hc.revive().tryStart(r1, t).consumesAction(), "first reviver re-click is harmless");
		} finally {
			TestPlayers.leave(t);
			TestPlayers.leave(r1);
			TestPlayers.leave(r2);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverMoves(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t4");
		ServerPlayer r = reviver(helper, "rv_r4");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> {
			Vec3 away = helper.absoluteVec(new Vec3(1, 2, 1));
			r.teleportTo(helper.getLevel(), away.x, away.y, away.z, Set.of(), 0f, 0f, false);
		});
		helper.runAfterDelay(15, () -> {
			try {
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken");
				helper.assertTrue(Hc.state().get(t.getUUID()).isDowned(), "target still downed");
			} finally {
				TestPlayers.leave(t);
				TestPlayers.leave(r);
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverIsHurt(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t5");
		ServerPlayer r = reviver(helper, "rv_r5");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> r.hurtServer(r.level(), r.level().damageSources().generic(), 1f));
		helper.runAfterDelay(15, () -> {
			try {
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken by damage");
			} finally {
				TestPlayers.leave(t);
				TestPlayers.leave(r);
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverStarves(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t6");
		ServerPlayer r = reviver(helper, "rv_r6");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> r.getFoodData().setFoodLevel(0));
		helper.runAfterDelay(15, () -> {
			try {
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken by hunger");
			} finally {
				TestPlayers.leave(t);
				TestPlayers.leave(r);
			}
			helper.succeed();
		});
	}
}
