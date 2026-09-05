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
}
