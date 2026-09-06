package com.fracturedhardcore.hcheart.heart;

import java.util.List;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.death.Messages;
import com.fracturedhardcore.hcheart.health.HealthService;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

/**
 * The Crimson Heart is a Nether Star with custom_data {hcheart: true}. Nothing is registered.
 * Identity is the custom_data tag only. The custom_model_data string is cosmetic: the optional resource pack
 * (resourcepack/) selects the heart texture by it and falls back to the vanilla star without the pack.
 */
public final class HeartItem {
	public static final String TAG = "hcheart";
	/** Must match the "when" case in resourcepack/assets/minecraft/items/nether_star.json (checked by ResourcePackTest). */
	public static final String MODEL_KEY = "hcheart:crimson_heart";
	private static final CustomModelData MODEL = new CustomModelData(List.of(), List.of(), List.of(MODEL_KEY), List.of());
	public static final Identifier RECIPE_ID = HcHeart.id("crimson_heart");

	private HeartItem() {}

	public static boolean isHeart(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.NETHER_STAR)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(TAG, false);
	}

	/** Same components as the recipe result. Used by /hc give and tests. */
	public static ItemStack create(int count) {
		ItemStack stack = new ItemStack(Items.NETHER_STAR, count);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(TAG, true));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Crimson Heart").withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)));
		stack.set(DataComponents.RARITY, Rarity.EPIC);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.CUSTOM_MODEL_DATA, MODEL);
		return stack;
	}

	public static boolean hasModel(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && data.strings().contains(MODEL_KEY);
	}

	/** Gives a Heart that predates the texture (0.1.2) its model key. Cosmetic; never changes identity or count. */
	public static boolean stampModel(ItemStack stack) {
		if (!isHeart(stack) || hasModel(stack)) return false;
		stack.set(DataComponents.CUSTOM_MODEL_DATA, MODEL);
		return true;
	}

	/** Stamps every Heart in the inventory and ender chest. Idempotent; run on join. Returns how many were stamped. */
	public static int stampAll(ServerPlayer player) {
		int n = 0;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (stampModel(inv.getItem(i))) n++;
		for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) if (stampModel(player.getEnderChestInventory().getItem(i))) n++;
		return n;
	}

	public static int count(ServerPlayer player) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isHeart(inv.getItem(i))) n += inv.getItem(i).getCount();
		}
		return n;
	}

	/** Removes {@code amount} hearts, held stack first, then any inventory slot. Returns how many were removed. */
	public static int remove(ServerPlayer player, ItemStack heldFirst, int amount) {
		int left = amount;
		if (isHeart(heldFirst)) {
			int take = Math.min(left, heldFirst.getCount());
			heldFirst.shrink(take);
			left -= take;
		}
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!isHeart(s)) continue;
			int take = Math.min(left, s.getCount());
			s.shrink(take);
			left -= take;
		}
		return amount - left;
	}

	public static void register() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (!isHeart(stack)) return InteractionResult.PASS;
			if (level.isClientSide()) return InteractionResult.SUCCESS; // never reached on a dedicated server; avoids a hand flicker when installed on a client
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return consume(sp, stack);
		});
	}

	static InteractionResult consume(ServerPlayer sp, ItemStack stack) {
		Services s = HcHeartMod.services();
		if (s == null) return InteractionResult.PASS;
		if (s.downed().isDowned(sp)) return InteractionResult.FAIL;
		if (sp.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL; // guards double-send use packets
		PlayerRecord rec = s.state().get(sp.getUUID());
		if (!rec.canRestore()) {
			sp.sendSystemMessage(Messages.alreadyFull(), true);
			return InteractionResult.FAIL;
		}
		int cost = rec.restoreCost();
		int have = count(sp);
		if (have < cost) {
			sp.sendSystemMessage(Messages.needHearts(cost, have), true);
			return InteractionResult.FAIL;
		}

		PlayerRecord after = s.state().restore(sp.getUUID()); // 1. persist + flush FIRST
		HealthService.normalize(sp, after);                   // 2. recompute + reapply the cap
		HealthService.refill(sp);
		int removed = remove(sp, stack, cost);                 // 3. THEN take the hearts
		if (removed != cost) {
			s.state().audit().log(sp.getUUID(), sp.getGameProfile().name(), "RESTORE_SHORT", "expected " + cost + " removed " + removed);
		}
		sp.getCooldowns().addCooldown(new ItemStack(Items.NETHER_STAR), Rules.HEART_USE_COOLDOWN_TICKS);
		sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
		sp.level().sendParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 1.0, sp.getZ(), 12, 0.6, 0.6, 0.6, 0.0);
		s.server().getPlayerList().broadcastSystemMessage(Messages.restoreBroadcast(sp.getGameProfile().name(), cost, after), false);
		return InteractionResult.SUCCESS;
	}
}
