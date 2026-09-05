package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recipe matching ignores components; without this a Heart + glass + obsidian crafts a beacon. */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
	@Inject(method = "test", at = @At("HEAD"), cancellable = true)
	private void hcheart$heartsAreNotIngredients(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (HeartItem.isHeart(stack)) cir.setReturnValue(false);
	}
}
