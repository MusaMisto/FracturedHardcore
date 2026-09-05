package com.fracturedhardcore.hcheart;

import com.fracturedhardcore.hcheart.death.DeathEvents;
import com.fracturedhardcore.hcheart.downed.DownedEvents;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.jspecify.annotations.Nullable;

public final class HcHeartMod implements ModInitializer {
	private static @Nullable Services services;

	public static @Nullable Services services() { return services; }

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> services = Services.create(server));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> { if (services != null) services.state().scoreboard().ensureObjective(); });
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (services != null) services.state().flush(); });
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> services = null);

		ServerPlayerEvents.JOIN.register(JoinHandler::onJoin);
		ServerPlayerEvents.LEAVE.register(player -> {
			if (services == null) return;
			services.revive().cancelFor(player.getUUID());
			services.downed().onPlayerLeft(player);
		});
		DownedEvents.register();
		DeathEvents.register();
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (services == null) return;
			services.downed().tick();
			services.revive().tick();
		});
		HcHeart.LOGGER.info("Fractured Hardcore loaded");
	}
}
