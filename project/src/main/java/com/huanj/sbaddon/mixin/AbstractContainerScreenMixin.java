package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.ClassifyTab;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 样本槽里真正存的是玩家拖进来的样本物品，但界面上要显示该模组**创造标签页的图标**。
 * <p>
 * 槽位自身的物品渲染发生在页签控件之后（会被盖住），所以这里直接掐掉，图标由
 * {@link ClassifyTab} 自己画。只有在分类页签打开时才生效，其它时候样本槽不可见也无所谓。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void sbaddon$hideSampleSlotItem(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        if (slot instanceof ClassifyUpgradeContainer.SampleSlot && ClassifyTab.getActiveTab() != null) {
            ci.cancel();
        }
    }
}
