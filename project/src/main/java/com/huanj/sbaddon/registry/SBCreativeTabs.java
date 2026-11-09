package com.huanj.sbaddon.registry;

import com.huanj.sbaddon.SBAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式标签页。
 * <p>
 * 必须注册：JEI / REI 的物品索引来自创造标签页的内容，物品若不属于任何标签页，
 * 即使已正常注册进物品注册表，在 JEI 里也搜不到、创造模式里也拿不到。
 */
public final class SBCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SBAddon.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sbaddon"))
                    .icon(() -> SBItems.CLASSIFY_UPGRADE.get().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(SBItems.CLASSIFY_UPGRADE.get()))
                    .build());

    private SBCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
