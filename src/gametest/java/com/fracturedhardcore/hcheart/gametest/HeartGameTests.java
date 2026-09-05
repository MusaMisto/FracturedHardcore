package com.fracturedhardcore.hcheart.gametest;

import java.util.List;
import java.util.Optional;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;

public class HeartGameTests {
	private static Optional<RecipeHolder<CraftingRecipe>> craft(GameTestHelper helper, CraftingInput input) {
		return helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
	}

	private static InteractionResult use(ServerPlayer p) {
		return p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
	}

	@GameTest
	public void recipeLoadsAndCraftsAHeart(GameTestHelper helper) {
		ItemStack e = new ItemStack(Items.ECHO_SHARD), n = new ItemStack(Items.NETHERITE_SCRAP), star = new ItemStack(Items.NETHER_STAR);
		CraftingInput input = CraftingInput.of(3, 3, List.of(e, n, e, n, star, n, e, n, e));
		Optional<RecipeHolder<CraftingRecipe>> recipe = craft(helper, input);
		helper.assertTrue(recipe.isPresent(), "crimson heart recipe matched");
		helper.assertValueEqual(recipe.get().id().identifier(), HeartItem.RECIPE_ID, "matched our recipe");
		ItemStack result = recipe.get().value().assemble(input);
		helper.assertTrue(HeartItem.isHeart(result), "result carries the hcheart tag");
		helper.assertValueEqual(result.getCount(), 1, "one heart");
		helper.succeed();
	}

	@GameTest
	public void heartIsNeverACraftingIngredient(GameTestHelper helper) {
		ItemStack heart = HeartItem.create(1), plain = new ItemStack(Items.NETHER_STAR), glass = new ItemStack(Items.GLASS), obsidian = new ItemStack(Items.OBSIDIAN);
		helper.assertFalse(Ingredient.of(Items.NETHER_STAR).test(heart), "Ingredient rejects a Heart");
		helper.assertTrue(Ingredient.of(Items.NETHER_STAR).test(plain), "Ingredient accepts a plain star");
		helper.assertFalse(craft(helper, CraftingInput.of(3, 3, List.of(glass, glass, glass, glass, heart, glass, obsidian, obsidian, obsidian))).isPresent(), "beacon recipe refuses a Heart");
		helper.assertTrue(craft(helper, CraftingInput.of(3, 3, List.of(glass, glass, glass, glass, plain, glass, obsidian, obsidian, obsidian))).isPresent(), "beacon recipe still works with a plain star");
		helper.succeed();
	}

	@GameTest
	public void heartCannotBeFedToABeacon(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_b", new Vec3(4, 2, 4));
		try {
			// Vanilla's beacon_payment_items tag does not contain nether stars (the star is a beacon *recipe* ingredient, guarded by
			// IngredientMixin). The slot guard is a safety net for datapacks that extend the payment tag; check it does not break the slot.
			BeaconMenu menu = new BeaconMenu(0, p.getInventory());
			helper.assertFalse(menu.getSlot(0).mayPlace(HeartItem.create(1)), "payment slot rejects a Heart");
			helper.assertTrue(menu.getSlot(0).mayPlace(new ItemStack(Items.DIAMOND)), "payment slot still accepts a vanilla payment item");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 80)
	public void consumingRestoresALevelWithEscalatingCost(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_c", new Vec3(4, 2, 4));
		Hc.state().set(p.getUUID(), 2, 0, "test");
		JoinHandler.onJoin(p);
		p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(3));
		helper.assertTrue(use(p).consumesAction(), "first use accepted (cost 1)");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "deaths 2 -> 1");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 1, "restores 1");
		helper.assertValueEqual(HeartItem.count(p), 2, "one heart consumed");
		helper.assertValueEqual((double) p.getMaxHealth(), 16.0, "8 hearts");
		helper.assertValueEqual(p.getHealth(), 16.0f, "refilled");
		helper.assertFalse(use(p).consumesAction(), "immediate re-use blocked by cooldown");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "unchanged during cooldown");
		helper.runAfterDelay(25, () -> {
			try {
				helper.assertTrue(use(p).consumesAction(), "second use accepted (cost 2)");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "deaths 1 -> 0");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 2, "restores 2");
				helper.assertValueEqual(HeartItem.count(p), 0, "two more hearts consumed");
				helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "back to 10 hearts");
			} finally {
				TestPlayers.leave(p);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void consumingRefusedWhenShortOrFull(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_s", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 1, 2, "test"); // cost is 3
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(2));
			helper.assertFalse(use(p).consumesAction(), "refused with 2 of 3 hearts");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "unchanged");
			helper.assertValueEqual(HeartItem.count(p), 2, "nothing consumed");
			Hc.state().set(p.getUUID(), 0, 0, "test");
			JoinHandler.onJoin(p);
			helper.assertFalse(use(p).consumesAction(), "refused at full health");
			helper.assertValueEqual(HeartItem.count(p), 2, "nothing consumed");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}

	@GameTest
	public void consumingLiftsFinalLifeAndDrawsFromAnySlot(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_f", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 3, 1, "test"); // cost 2
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(1));
			p.getInventory().setItem(20, HeartItem.create(1)); // a second heart elsewhere in the inventory
			helper.assertTrue(use(p).consumesAction(), "accepted");
			helper.assertFalse(Hc.state().get(p.getUUID()).finalLife(), "final life lifted");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "6 hearts");
			helper.assertValueEqual(HeartItem.count(p), 0, "both hearts consumed");
		} finally {
			TestPlayers.leave(p);
		}
		helper.succeed();
	}
}
