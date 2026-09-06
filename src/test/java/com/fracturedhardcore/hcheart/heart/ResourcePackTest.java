package com.fracturedhardcore.hcheart.heart;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The optional resource pack under resourcepack/ must stay in step with the mod: same model key, valid references, 26.2 format. */
class ResourcePackTest {
	private static final Path ROOT = Path.of("resourcepack");

	@Test void packMetaTargetsThe26_2ResourceFormat() throws IOException {
		JsonObject pack = json("pack.mcmeta").getAsJsonObject("pack");
		assertEquals(88, pack.getAsJsonArray("min_format").get(0).getAsInt());
		assertEquals(88, pack.get("max_format").getAsInt());
		assertTrue(Files.exists(ROOT.resolve("pack.png")));
	}

	@Test void netherStarSelectsTheHeartModelByTheModsKey() throws IOException {
		JsonObject model = json("assets/minecraft/items/nether_star.json").getAsJsonObject("model");
		assertEquals("minecraft:select", model.get("type").getAsString());
		assertEquals("minecraft:custom_model_data", model.get("property").getAsString());
		assertEquals(0, model.get("index").getAsInt());
		JsonObject only = model.getAsJsonArray("cases").get(0).getAsJsonObject();
		assertEquals(HeartItem.MODEL_KEY, only.get("when").getAsString());
		assertEquals("hcheart:item/crimson_heart", only.getAsJsonObject("model").get("model").getAsString());
		assertEquals("minecraft:item/nether_star", model.getAsJsonObject("fallback").get("model").getAsString(), "no pack key -> vanilla star");
	}

	@Test void heartModelPointsAtA16x16Texture() throws IOException {
		JsonObject model = json("assets/hcheart/models/item/crimson_heart.json");
		assertEquals("minecraft:item/generated", model.get("parent").getAsString());
		assertEquals("hcheart:item/crimson_heart", model.getAsJsonObject("textures").get("layer0").getAsString());
		byte[] png = Files.readAllBytes(ROOT.resolve("assets/hcheart/textures/item/crimson_heart.png"));
		assertEquals(0x89, png[0] & 0xff);
		assertEquals("PNG", new String(png, 1, 3));
		ByteBuffer ihdr = ByteBuffer.wrap(png, 16, 8);
		assertEquals(16, ihdr.getInt(), "width");
		assertEquals(16, ihdr.getInt(), "height");
	}

	private static JsonObject json(String rel) throws IOException {
		return JsonParser.parseString(Files.readString(ROOT.resolve(rel))).getAsJsonObject();
	}
}
