package com.huanj.sbaddon;

import com.huanj.sbaddon.registry.SBItems;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SBAddon.MOD_ID)
public class SBAddon {

    public static final String MOD_ID = "sbaddon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SBAddon(IEventBus modEventBus, ModContainer modContainer) {
        SBItems.register(modEventBus);
        // 升级类型 / 容器类型在 FMLCommonSetupEvent 里注册（见 CommonSetup），
        // 因为 UpgradeContainerRegistry 的 key 必须是物品注册名，物品那时才真正注册完。
        LOGGER.info("[sbaddon] Sophisticated Backpacks Addon 已加载");
    }
}
