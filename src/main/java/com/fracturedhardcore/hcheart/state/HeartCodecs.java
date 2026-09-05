package com.fracturedhardcore.hcheart.state;

import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

public final class HeartCodecs {
	private HeartCodecs() {}

	public static final Codec<PlayerRecord> PLAYER_RECORD = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("deaths", 0).forGetter(PlayerRecord::deaths),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("restores_used", 0).forGetter(PlayerRecord::restoresUsed),
			Codec.LONG.optionalFieldOf("downed_until", 0L).forGetter(PlayerRecord::downedUntilTick),
			Codec.STRING.optionalFieldOf("name", "").forGetter(PlayerRecord::lastKnownName)
	).apply(i, PlayerRecord::new));

	public static final Codec<Map<UUID, PlayerRecord>> PLAYERS = Codec.unboundedMap(UUIDUtil.STRING_CODEC, PLAYER_RECORD);
}
