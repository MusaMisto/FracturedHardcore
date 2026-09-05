package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Beacons accept any Nether Star; a Crimson Heart must never vanish into one. */
@Mixin(targets = "net.minecraft.world.inventory.BeaconMenu$PaymentSlot")
public abstract class BeaconPaymentSlotMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void hcheart$rejectHearts(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (HeartItem.isHeart(stack)) cir.setReturnValue(false);
	}
}
