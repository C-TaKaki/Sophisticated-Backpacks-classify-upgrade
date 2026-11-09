package com.huanj.sbaddon.upgrade;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.registry.SBItems;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerRegistry;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeType;

/**
 * 升级类型与容器类型的注册点。
 * <p>
 * 必须在物品注册完成之后执行（FMLCommonSetupEvent），否则 {@link UpgradeContainerRegistry} 的 key
 * 会退化成 {@code minecraft:air}，页签静默消失且不报错。
 */
public final class ClassifyUpgrades {

    public static final ResourceLocation ITEM_ID = ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "classify_upgrade");

    public static UpgradeType<ClassifyUpgradeWrapper> UPGRADE_TYPE;
    public static UpgradeContainerType<ClassifyUpgradeWrapper, ClassifyUpgradeContainer> CONTAINER_TYPE;

    private ClassifyUpgrades() {
    }

    public static void init() {
        if (UPGRADE_TYPE != null) {
            return;
        }

        UPGRADE_TYPE = new UpgradeType<>(ClassifyUpgradeWrapper::new);

        CONTAINER_TYPE = new UpgradeContainerType<>(ClassifyUpgradeContainer::new);
        UpgradeContainerRegistry.register(ITEM_ID, CONTAINER_TYPE);

        // 校验：注册 key 必须和物品注册名一致，早失败早发现
        ResourceLocation actual = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(SBItems.CLASSIFY_UPGRADE.get());
        if (!ITEM_ID.equals(actual)) {
            throw new IllegalStateException("sbaddon: 物品注册名 " + actual + " 与容器注册 key " + ITEM_ID + " 不一致，页签不会出现");
        }

        SBAddon.LOGGER.info("[sbaddon] 分类升级已注册，容器 key = {}", ITEM_ID);
    }
}
