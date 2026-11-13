package com.huanj.sbaddon.compat.fxntstorage.mixin;

import net.fxnt.fxntstorage.backpack.client.menu.slot.UpgradeSlot;
import net.fxnt.fxntstorage.backpack.inventory.BackpackSlotLayout;
import net.fxnt.fxntstorage.backpack.inventory.IBackpackContainer;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyUpgradeItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 允许把分类升级放进 FXNT 背包的升级槽。
 * <p>
 * 原实现把物品强转成 {@code UpgradeItem} 并要求命中 {@code fxntstorage:backpack_upgrade} 标签；
 * 我们的物品确实继承自 {@code UpgradeItem}（标签也补了），所以这里只需要额外保证
 * 「同一个背包只装一个」这条约束不被破坏。
 */
@Mixin(UpgradeSlot.class)
public abstract class UpgradeSlotMixin {

    private static final BackpackSlotLayout SBADDON_LAYOUT = BackpackSlotLayout.createLayout();

    @Shadow
    @Final
    private IBackpackContainer backpack;

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void sbaddon$allowClassifyUpgrade(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!(stack.getItem() instanceof FxntClassifyUpgradeItem)) {
            return;
        }
        UpgradeSlot self = (UpgradeSlot) (Object) this;
        for (int i : SBADDON_LAYOUT.upgrades().range()) {
            if (i == self.getContainerSlot()) {
                continue;
            }
            if (backpack.getItemHandler().getStackInSlot(i).getItem() instanceof FxntClassifyUpgradeItem) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }
}
