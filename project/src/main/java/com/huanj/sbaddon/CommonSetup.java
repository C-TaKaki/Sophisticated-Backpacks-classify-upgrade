package com.huanj.sbaddon;

import com.huanj.sbaddon.net.ClassifyRenameAction;
import com.huanj.sbaddon.upgrade.ClassifyUpgrades;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 通用初始化：物品已注册完成后，才能安全注册升级类型与容器类型。 */
@EventBusSubscriber(modid = SBAddon.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CommonSetup {

    private CommonSetup() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ClassifyUpgrades::init);
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                ClassifyRenameAction.TYPE, ClassifyRenameAction.STREAM_CODEC, ClassifyRenameAction::handle);
    }
}
