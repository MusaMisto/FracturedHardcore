package com.fracturedhardcore.hcheart.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class HeartCodecsTest {
	@Test void playersMapRoundTrips() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		Map<UUID, PlayerRecord> in = Map.of(a, new PlayerRecord(2, 1, 12345L, "skillux"), b, PlayerRecord.FRESH);
		Tag encoded = HeartCodecs.PLAYERS.encodeStart(NbtOps.INSTANCE, in).getOrThrow();
		Map<UUID, PlayerRecord> out = HeartCodecs.PLAYERS.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertEquals(in, out);
	}
	@Test void missingFieldsUseDefaults() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("deaths", 3);
		PlayerRecord rec = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(new PlayerRecord(3, 0, 0L, ""), rec);
	}
	@Test void negativeDeathsFailToParseRatherThanCorruptState() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("deaths", -2);
		assertTrue(HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, tag).isError());
	}
	@Test void pausedClockRoundTripsAndOldFilesLoadUnpaused() {
		PlayerRecord paused = new PlayerRecord(1, 0, 500L, 120L, "p");
		Tag encoded = HeartCodecs.PLAYER_RECORD.encodeStart(NbtOps.INSTANCE, paused).getOrThrow();
		assertEquals(paused, HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, encoded).getOrThrow());
		CompoundTag old = new CompoundTag();
		old.putLong("downed_until", 500L);
		PlayerRecord loaded = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, old).getOrThrow();
		assertTrue(loaded.isDowned() && !loaded.isDownedPaused(), "a pre-0.1.3 file loads downed with the clock running");
	}
	@Test void tickBasedFilesFrom013LoadAsLegacyClocks() {
		CompoundTag running = new CompoundTag();
		running.putInt("deaths", 1);
		running.putLong("downed_until", 2_180_974L); // BisonPapa's real 0.1.3 record
		PlayerRecord rec = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, running).getOrThrow();
		assertTrue(rec.isDowned() && rec.hasLegacyClock(), "still downed, deadline kept in ticks");
		assertEquals(2_180_974L, rec.legacyDeadlineTicks());
		assertFalse(rec.downedExpired(Long.MAX_VALUE));
		assertEquals(1, rec.deaths());
		Tag saved = HeartCodecs.PLAYER_RECORD.encodeStart(NbtOps.INSTANCE, rec).getOrThrow();
		assertEquals(rec, HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, saved).getOrThrow(), "an unconverted legacy clock survives a save");
		CompoundTag paused = new CompoundTag();
		paused.putLong("downed_until", 1_799_555L);
		paused.putLong("downed_paused", 381L);
		PlayerRecord p = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, paused).getOrThrow();
		assertTrue(p.isDownedPaused() && p.hasLegacyClock(), "a paused remainder is a duration and survives");
		assertEquals(381L * 50L, p.downedPausedMs(), "converted at 50 ms per tick");
		assertEquals(p.withDownedResumed(1_000_000L).downedUntilMs(), 1_000_000L + 381L * 50L, "resume yields a real wall-clock deadline");
		Tag reencoded = HeartCodecs.PLAYER_RECORD.encodeStart(NbtOps.INSTANCE, new PlayerRecord(0, 0, 1_700_000_000_000L, "x")).getOrThrow();
		PlayerRecord back = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, reencoded).getOrThrow();
		assertEquals(1_700_000_000_000L, back.downedUntilMs(), "new files are wall-clock and never mistaken for legacy");
		assertFalse(back.hasLegacyClock());
	}
	@Test void pendingKillRoundTripsAndDefaultsToFalse() {
		PlayerRecord owed = new PlayerRecord(2, 0, 0L, 0L, true, "p");
		Tag encoded = HeartCodecs.PLAYER_RECORD.encodeStart(NbtOps.INSTANCE, owed).getOrThrow();
		assertEquals(owed, HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, encoded).getOrThrow());
		assertFalse(HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().pendingKill());
	}
}
