package com.huanj.sbaddon.registry;

import com.huanj.sbaddon.SBAddon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * 把本模组物品挂到精妙背包自己的创造模式标签页（{@code sophisticatedbackpacks:main}）。
 * <p>
 * 不自己新建标签页：这样物品和背包、升级显示在一起，JEI / REI 也能正常索引到
 * （它们只索引创造标签页的内容，不在任何标签页里的物品搜不到、创造模式也拿不到）。
 * <p>
 * 用事件而不是直接改 SB 的 DeferredRegister：SB 的标签页由它自己注册，
 * 我们只在它构建内容时追加，互不干扰。
 */
public final class SBCreativeTabs {

    /** 精妙背包创造标签页的命名空间（{@code sophisticatedbackpacks:main}）。 */
    private static final String SB_NAMESPACE = "sophisticatedbackpacks";

    private SBCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(SBCreativeTabs::onBuildContents);
    }

    private static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (SB_NAMESPACE.equals(event.getTabKey().location().getNamespace())) {
            event.accept(SBItems.CLASSIFY_UPGRADE.get());
        }
    }
}
