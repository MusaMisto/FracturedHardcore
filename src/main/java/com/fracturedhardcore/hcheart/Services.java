package com.fracturedhardcore.hcheart;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.downed.ReviveManager;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.server.MinecraftServer;

/** Per-server runtime objects. Created when the server starts, dropped when it stops. */
public record Services(MinecraftServer server, HeartStateService state, DownedManager downed, ReviveManager revive) {
	public static Services create(MinecraftServer server) {
		HeartStateService state = HeartStateService.load(server);
		DownedManager downed = new DownedManager(server, state);
		ReviveManager revive = new ReviveManager(server, state, downed);
		downed.attachRevive(revive);
		return new Services(server, state, downed, revive);
	}
}
