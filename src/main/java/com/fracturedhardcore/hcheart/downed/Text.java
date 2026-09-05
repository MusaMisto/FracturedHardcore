package com.fracturedhardcore.hcheart.downed;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class Text {
	private Text() {}

	public static String mmss(long ticks) {
		long s = Math.max(0, ticks) / 20;
		return String.format("%d:%02d", s / 60, s % 60);
	}
	public static MutableComponent info(String s) { return Component.literal(s).withStyle(ChatFormatting.GRAY); }
	public static MutableComponent warn(String s) { return Component.literal(s).withStyle(ChatFormatting.RED); }
	public static MutableComponent good(String s) { return Component.literal(s).withStyle(ChatFormatting.GREEN); }
	public static MutableComponent gold(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }
}
