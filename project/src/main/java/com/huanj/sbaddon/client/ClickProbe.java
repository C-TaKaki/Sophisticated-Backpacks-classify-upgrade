package com.huanj.sbaddon.client;

import com.huanj.sbaddon.SBAddon;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * TODO 临时诊断：打印背包界面里每一次鼠标点击命中的槽位，定位「插不进升级槽」。
 * 定位完删掉这个类。
 */
@EventBusSubscriber(modid = SBAddon.MOD_ID, value = Dist.CLIENT)
public final class ClickProbe {

    private ClickProbe() {
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null) {
            return;
        }
        SBAddon.LOGGER.info("[sbaddon-probe] 点击槽位 index={} 实现类={} 槽内物品={} 手上物品={} isActive={} mayPlace(hand)={}",
                slot.index,
                slot.getClass().getSimpleName(),
                slot.getItem().getItem(),
                screen.getMenu().getCarried().getItem(),
                slot.isActive(),
                slot.mayPlace(screen.getMenu().getCarried()));

        var carried = screen.getMenu().getCarried();
        if (!carried.isEmpty()) {
            String handlerInfo = "-";
            boolean handlerAccepts = false;
            try {
                if (slot instanceof net.neoforged.neoforge.items.SlotItemHandler ish) {
                    var handler = ish.getItemHandler();
                    handlerInfo = handler.getClass().getName() + "(size=" + handler.getSlots() + ")";
                    handlerAccepts = handler.isItemValid(ish.getSlotIndex(), carried);
                }
            } catch (Throwable t) {
                handlerInfo = "err:" + t;
            }
            SBAddon.LOGGER.info("[sbaddon-probe] 手上物品类={} isUpgradeItem={} isOurItem={} count={} 槽位handler={} handler.isItemValid={}",
                    carried.getItem().getClass().getName(),
                    carried.getItem() instanceof net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem,
                    carried.getItem() instanceof com.huanj.sbaddon.upgrade.ClassifyUpgradeItem,
                    carried.getCount(), handlerInfo, handlerAccepts);
        }
    }
}
