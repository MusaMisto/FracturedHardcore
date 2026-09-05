package com.fracturedhardcore.hcheart;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HcHeart {
	public static final String MOD_ID = "hcheart";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HcHeart() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
