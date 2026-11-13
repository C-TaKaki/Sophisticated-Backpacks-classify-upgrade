package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.ClassifyViewState;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.InventoryScrollPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 分类视图下把 SB 自带的滚动面板变成「只负责画物品、不滚动」。
 * <p>
 * 面板本身不能摘掉：物品是它在 {@code drawPanel} 里画出来的。所以：
 * <ul>
 *   <li>{@code getContentHeight()} 返回 0 —— {@code ScrollPanel.render} 只有在
 *       内容高于可视区时才画滚动条，同时滚动范围也归零；</li>
 *   <li>鼠标三个事件直接吃掉，拇指/轨道拖不动。</li>
 * </ul>
 */
@Mixin(InventoryScrollPanel.class)
public abstract class InventoryScrollPanelMixin {

    @Inject(method = "getContentHeight", at = @At("RETURN"), cancellable = true)
    private void sbaddon$noScroll(CallbackInfoReturnable<Integer> cir) {
        if (ClassifyViewState.lockScrollPanel) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onClick(double mouseX, double mouseY, int button,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onDrag(double mouseX, double mouseY, int button, double dragX, double dragY,
                                CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onRelease(double mouseX, double mouseY, int button,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel) {
            cir.setReturnValue(false);
        }
    }
}
