package com.fracturedhardcore.hcheart.gametest;

import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real ServerPlayers with a dead-end connection, placed through PlayerList.placeNewPlayer so the mod's JOIN handler runs. */
final class TestPlayers {
	private TestPlayers() {}

	static ServerPlayer join(GameTestHelper helper, String name, Vec3 relativePos) {
		MinecraftServer server = helper.getLevel().getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation()) {
			@Override
			public boolean isClientAuthoritative() { return false; }
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		markClientLoaded(player);
		player.setGameMode(GameType.SURVIVAL);
		Vec3 abs = helper.absoluteVec(relativePos);
		player.teleportTo(helper.getLevel(), abs.x, abs.y, abs.z, Set.of(), 0f, 0f, false);
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		return player;
	}

	/** 26.2 keeps a player invulnerable until its client reports loaded; a real client sends this packet, the mock must too. */
	static void markClientLoaded(ServerPlayer player) {
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
	}

	static void leave(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
			server.getPlayerList().remove(player);
		}
	}
}
