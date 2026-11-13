package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.ClassifyViewState;
import net.neoforged.neoforge.client.gui.widget.ScrollPanel;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.InventoryScrollPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 滚动条拇指的拖拽/释放逻辑在 NeoForge 的 {@code ScrollPanel} 里（SB 的
 * {@code InventoryScrollPanel} 只重写了 {@code mouseClicked}），所以在这里拦。
 * <p>
 * 只对 SB 的库存滚动面板生效，且只在分类视图接管排版期间 —— 别的模组用同一个
 * {@code ScrollPanel} 不受影响。
 */
@Mixin(ScrollPanel.class)
public abstract class ScrollPanelMixin {

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onDrag(double mouseX, double mouseY, int button, double dragX, double dragY,
                                CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel && (Object) this instanceof InventoryScrollPanel) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onRelease(double mouseX, double mouseY, int button,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel && (Object) this instanceof InventoryScrollPanel) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (ClassifyViewState.lockScrollPanel && (Object) this instanceof InventoryScrollPanel) {
            cir.setReturnValue(false);
        }
    }
}
