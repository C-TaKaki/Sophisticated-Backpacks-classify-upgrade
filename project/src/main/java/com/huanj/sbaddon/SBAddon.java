package com.huanj.sbaddon;

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
        LOGGER.info("[sbaddon] Sophisticated Backpacks Addon 已加载");
    }
}
