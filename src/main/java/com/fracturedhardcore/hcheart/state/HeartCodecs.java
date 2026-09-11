package com.fracturedhardcore.hcheart.state;

import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

public final class HeartCodecs {
	private HeartCodecs() {}

	/**
	 * Fields written by 0.1.4+: deaths, restores_used, downed_until_ms, downed_paused_ms, pending_kill, name. Fields from
	 * 0.1.3 and older are still read: downed_until (deadline in overworld game ticks; kept on the record until the first
	 * server tick converts it, so it survives a save in between) and downed_paused (remainder in ticks; a duration, converted
	 * on load). DFU omits a field whose value equals its default, so a converted record never carries the old keys.
	 */
	public static final Codec<PlayerRecord> PLAYER_RECORD = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("deaths", 0).forGetter(PlayerRecord::deaths),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("restores_used", 0).forGetter(PlayerRecord::restoresUsed),
			Codec.LONG.optionalFieldOf("downed_until_ms", 0L).forGetter(PlayerRecord::downedUntilMs),
			Codec.LONG.optionalFieldOf("downed_paused_ms", 0L).forGetter(PlayerRecord::downedPausedMs),
			Codec.LONG.optionalFieldOf("downed_until", 0L).forGetter(PlayerRecord::legacyDeadlineTicks),
			Codec.LONG.optionalFieldOf("downed_paused", 0L).forGetter(r -> 0L),
			Codec.BOOL.optionalFieldOf("pending_kill", false).forGetter(PlayerRecord::pendingKill),
			Codec.STRING.optionalFieldOf("name", "").forGetter(PlayerRecord::lastKnownName)
	).apply(i, HeartCodecs::fromFields));

	private static PlayerRecord fromFields(int deaths, int restores, long untilMs, long pausedMs, long legacyUntilTicks, long legacyPausedTicks, boolean pendingKill, String name) {
		long paused = pausedMs > 0 ? pausedMs : legacyPausedTicks * PlayerRecord.LEGACY_MS_PER_TICK;
		return new PlayerRecord(deaths, restores, untilMs, paused, legacyUntilTicks, pendingKill, name); // precedence rules live in the record
	}

	public static final Codec<Map<UUID, PlayerRecord>> PLAYERS = Codec.unboundedMap(UUIDUtil.STRING_CODEC, PLAYER_RECORD);
}
