package com.fracturedhardcore.hcheart.death;

import java.util.ArrayList;
import java.util.List;

import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.downed.Text;
import net.minecraft.network.chat.Component;

public final class Messages {
	private Messages() {}

	public static String hearts(int n) { return n + (n == 1 ? " Heart" : " Hearts"); }

	/** Chat (not action bar) so it persists and can be scrolled back. */
	public static List<Component> selfDeathLines(PlayerRecord rec) {
		List<Component> lines = new ArrayList<>();
		lines.add(Text.warn("You died. Deaths: " + rec.deaths() + " · Max health reduced to " + rec.maxHearts() + " hearts."));
		lines.add(Text.info("Craft a Crimson Heart to restore a level. Next restoration costs " + hearts(rec.restoreCost()) + "."));
		if (rec.deaths() == Rules.FINAL_LIFE_DEATHS - 1) lines.add(Text.gold("One more death puts you on your final life."));
		else if (rec.finalLife()) lines.add(Text.warn("You are now on your final life. The next death is permanent."));
		return lines;
	}

	public static Component othersDeathLine(String name, PlayerRecord rec) {
		if (rec.eliminated()) return Text.warn(name + "'s run has ended.");
		if (rec.finalLife()) return Text.warn(name + " died and is now on their final life (4 hearts).");
		return Text.info(name + " died · deaths " + rec.deaths() + " · " + rec.maxHearts() + " hearts");
	}

	public static Component eliminated() { return Text.warn("Your run is over. You may spectate the world."); }

	public static Component restoreBroadcast(String name, int cost, PlayerRecord after) {
		return Text.gold(name + " consumed " + hearts(cost) + " and is back to " + after.maxHearts() + " hearts. Their next restoration costs " + hearts(after.restoreCost()) + ".");
	}

	public static Component needHearts(int cost, int have) { return Text.warn("You need " + hearts(cost) + " to restore a level (you have " + have + ")."); }

	public static Component alreadyFull() { return Text.warn("You are already at full health."); }
}
