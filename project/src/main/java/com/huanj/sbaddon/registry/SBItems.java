package com.huanj.sbaddon.registry;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SBItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SBAddon.MOD_ID);

    /** 分类扩展升级物品。注册名必须与 CommonSetup 里 UpgradeContainerRegistry 的 key 完全一致。 */
    public static final DeferredItem<ClassifyUpgradeItem> CLASSIFY_UPGRADE =
            ITEMS.registerItem("classify_upgrade", props -> new ClassifyUpgradeItem());

    private SBItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
