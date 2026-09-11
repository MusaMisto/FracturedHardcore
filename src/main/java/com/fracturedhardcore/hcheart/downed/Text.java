package com.fracturedhardcore.hcheart.downed;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class Text {
	private Text() {}

	/** m:ss from milliseconds, rounded up so 1 ms left still reads 0:01 and a full clock reads 3:00. */
	public static String mmss(long ms) {
		long s = (Math.max(0, ms) + 999) / 1000;
		return String.format("%d:%02d", s / 60, s % 60);
	}
	public static MutableComponent info(String s) { return Component.literal(s).withStyle(ChatFormatting.GRAY); }
	public static MutableComponent warn(String s) { return Component.literal(s).withStyle(ChatFormatting.RED); }
	public static MutableComponent good(String s) { return Component.literal(s).withStyle(ChatFormatting.GREEN); }
	public static MutableComponent gold(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }

	/** A clickable chat label that runs {@code command} for the clicking player (vanilla run_command click event). */
	public static MutableComponent link(String label, String command, String hover) {
		return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}
}
