package com.fracturedhardcore.hcheart.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real ServerPlayers with a dead-end connection, placed through PlayerList.placeNewPlayer so the mod's JOIN handler runs. */
final class TestPlayers {
	private static final Map<UUID, EmbeddedChannel> channels = new HashMap<>();

	private TestPlayers() {}

	static ServerPlayer join(GameTestHelper helper, String name, Vec3 relativePos) {
		return join(helper, name, relativePos, UUID.randomUUID(), true);
	}

	/**
	 * @param id           fixed UUID so a test can seed the record before the join handler runs
	 * @param clientLoaded false leaves the player exactly as a real join sees it at JOIN time: the client has not sent
	 *                     PlayerLoaded yet, so vanilla treats the player as invulnerable to everything. Call
	 *                     {@link #markClientLoaded} later to simulate the client finishing its load.
	 */
	static ServerPlayer join(GameTestHelper helper, String name, Vec3 relativePos, UUID id, boolean clientLoaded) {
		MinecraftServer server = helper.getLevel().getServer();
		GameProfile profile = new GameProfile(id, name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation()) {
			@Override
			public boolean isClientAuthoritative() { return false; }
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		channels.put(profile.id(), new EmbeddedChannel(connection));
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		if (clientLoaded) markClientLoaded(player);
		player.setGameMode(GameType.SURVIVAL);
		// Solid footing: the default test structure is empty air.
		helper.setBlock(new BlockPos(Mth.floor(relativePos.x), Mth.floor(relativePos.y) - 1, Mth.floor(relativePos.z)), Blocks.STONE);
		Vec3 abs = helper.absoluteVec(relativePos);
		player.teleportTo(helper.getLevel(), abs.x, abs.y, abs.z, Set.of(), 0f, 0f, false);
		// A real connection is ticked by the network listener, which calls Player.tick() (cooldowns, food, pose...).
		// The mock connection is not registered there, so tick it from the test instead.
		ServerGamePacketListenerImpl listener = player.connection;
		helper.onEachTick(() -> {
			ServerPlayer current = listener.player;
			if (current != null && !current.isRemoved() && server.getPlayerList().getPlayer(current.getUUID()) == current) listener.tick();
		});
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		return player;
	}

	/** 26.2 keeps a player invulnerable until its client reports loaded; a real client sends this packet, the mock must too. */
	static void markClientLoaded(ServerPlayer player) {
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
	}

	/**
	 * Packets the server wrote to this mock client since the last drain. The channel has no encoder, so these are the raw
	 * packet objects. Vanilla suspends flushing on every connection for the duration of a server tick and flushes at its
	 * end, so a packet written earlier in the current tick is still in the write buffer: flush first, then read.
	 */
	static List<Packet<?>> drainSent(ServerPlayer player) {
		List<Packet<?>> out = new ArrayList<>();
		EmbeddedChannel ch = channels.get(player.getUUID());
		if (ch == null) return out;
		ch.flushOutbound();
		Object o;
		while ((o = ch.readOutbound()) != null) if (o instanceof Packet<?> p) out.add(p);
		return out;
	}

	static void leave(ServerPlayer player) {
		channels.remove(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
			server.getPlayerList().remove(player);
		}
	}
}
