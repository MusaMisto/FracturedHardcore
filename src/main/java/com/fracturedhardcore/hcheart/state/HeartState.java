package com.fracturedhardcore.hcheart.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World-level store: UUID -> PlayerRecord. Lives in the world's data folder. */
public final class HeartState extends SavedData {
	public static final Codec<HeartState> CODEC = RecordCodecBuilder.create(i -> i.group(
			HeartCodecs.PLAYERS.optionalFieldOf("players", Map.of()).forGetter(s -> Map.copyOf(s.players))
	).apply(i, HeartState::new));

	/**
	 * SAVED_DATA_COMMAND_STORAGE is registered as an opaque (DSL::remainder) type in every vanilla schema, so no data
	 * fixer will ever rewrite our fields. It is the type vanilla uses for arbitrary user NBT (/data storage).
	 */
	public static final SavedDataType<HeartState> TYPE = new SavedDataType<>(HcHeart.id("players"), HeartState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<UUID, PlayerRecord> players = new HashMap<>();

	public HeartState() {}

	private HeartState(Map<UUID, PlayerRecord> players) { this.players.putAll(players); }

	public PlayerRecord get(UUID id) { return players.getOrDefault(id, PlayerRecord.FRESH); }
	public boolean contains(UUID id) { return players.containsKey(id); }
	public void put(UUID id, PlayerRecord rec) { players.put(id, rec); setDirty(); }
	public void clear() { players.clear(); setDirty(); }
	public Map<UUID, PlayerRecord> view() { return Collections.unmodifiableMap(players); }
}
