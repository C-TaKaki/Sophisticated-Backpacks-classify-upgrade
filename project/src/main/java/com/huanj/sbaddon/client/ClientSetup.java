package com.huanj.sbaddon.client;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeWrapper;
import com.huanj.sbaddon.upgrade.ClassifyUpgrades;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeGuiManager;

/** 客户端注册：把分类页签挂到升级容器类型上。 */
@EventBusSubscriber(modid = SBAddon.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> UpgradeGuiManager
                .<ClassifyUpgradeWrapper, ClassifyUpgradeContainer, ClassifyTab>registerTab(
                        ClassifyUpgrades.CONTAINER_TYPE,
                        (container, position, screen) -> new ClassifyTab(container, position, screen)));
    }

    /** 演示屏热键（F8）。不需要演示屏时连同 ClientKeybinds 一起删。 */
    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ClientKeybinds.OPEN_DEMO);
    }
}
