package com.huanj.sbaddon.compat.fxntstorage;

import com.huanj.sbaddon.compat.fxntstorage.net.FxntClassifyAction;
import com.huanj.sbaddon.registry.SBItems;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/** FXNT Storage 兼容内容：物品 + 网络包 + 创造标签页。只在 FXNT 在场时才被加载。 */
public final class FxntCompatContent {

    public static final DeferredItem<FxntClassifyUpgradeItem> CLASSIFY_UPGRADE =
            SBItems.ITEMS.registerItem("fxnt_classify_upgrade",
                    FxntClassifyUpgradeItem::new, new Item.Properties().stacksTo(1));

    private FxntCompatContent() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(FxntCompatContent::onBuildTabContents);
        modEventBus.addListener(FxntCompatContent::onRegisterPayloads);
    }

    private static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        ResourceKey<CreativeModeTab> key = event.getTabKey();
        // 挂到 FXNT 自己的创造标签页（fxntstorage:creative_mode_tab），
        // 这样 JEI/REI 也能索引到，玩家不用指令找物品
        if (FxntCompat.MOD_ID.equals(key.location().getNamespace())) {
            event.accept(CLASSIFY_UPGRADE.get());
        }
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                FxntClassifyAction.TYPE, FxntClassifyAction.STREAM_CODEC, FxntClassifyAction::handle);
    }
}
