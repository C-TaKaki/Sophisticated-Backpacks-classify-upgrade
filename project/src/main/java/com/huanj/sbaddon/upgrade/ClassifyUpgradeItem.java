package com.huanj.sbaddon.upgrade;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeCountLimitConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeGroup;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeItemBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeType;

import java.util.List;
import javax.annotation.Nullable;

/**
 * 分类扩展升级物品。
 * <p>
 * 该物品本身不做任何事，作用只有一个：被插入背包后，让 {@link ClassifyUpgradeContainer} 与
 * {@code ClassifyTab} 出现在背包界面里 —— SB（Sophisticated Backpacks）没有公开的「加页签」API，
 * 升级物品是唯一官方扩展点。
 */
public class ClassifyUpgradeItem extends UpgradeItemBase<ClassifyUpgradeWrapper> {

    /** 每个背包最多装 1 个，避免页签重复。 */
    private static final IUpgradeCountLimitConfig LIMITS = new IUpgradeCountLimitConfig() {
        @Override
        public int getMaxUpgradesPerStorage(String storageType, net.minecraft.resources.ResourceLocation upgradeId) {
            return 1;
        }

        @Override
        public int getMaxUpgradesInGroupPerStorage(String storageType, UpgradeGroup upgradeGroup) {
            return 1;
        }
    };

    public ClassifyUpgradeItem() {
        super(LIMITS);
    }

    /** TODO 调试用，定位「无法插入升级槽」后可删。 */
    @Override
    public net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeSlotChangeResult canAddUpgradeTo(
            net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper storageWrapper,
            net.minecraft.world.item.ItemStack upgradeStack, boolean firstLevelStorage, boolean isClientSide) {
        com.huanj.sbaddon.SBAddon.LOGGER.info("[sbaddon] >>> canAddUpgradeTo 进入 item={} count={} class={}",
                upgradeStack.getItem(), upgradeStack.getCount(), getClass().getName());
        net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeSlotChangeResult result;
        try {
            result = super.canAddUpgradeTo(storageWrapper, upgradeStack, firstLevelStorage, isClientSide);
        } catch (Throwable t) {
            com.huanj.sbaddon.SBAddon.LOGGER.error("[sbaddon] canAddUpgradeTo 抛异常", t);
            throw t;
        }
        com.huanj.sbaddon.SBAddon.LOGGER.info(
                "[sbaddon] <<< canAddUpgradeTo: storageType={}, firstLevel={}, client={}, perStorage={}, result={} {}",
                storageWrapper.getStorageType(), firstLevelStorage, isClientSide,
                getUpgradesPerStorage(storageWrapper.getStorageType()), result.successful(),
                result.getErrorMessage().map(c -> c.getString()).orElse("-"));
        return result;
    }

    @Override
    public UpgradeType<ClassifyUpgradeWrapper> getType() {
        UpgradeType<ClassifyUpgradeWrapper> type = ClassifyUpgrades.UPGRADE_TYPE;
        if (type == null) {
            // 走到这里说明注册顺序错了：UpgradeContainerRegistry 的 key 会变成 minecraft:air，页签静默消失。
            throw new IllegalStateException("sbaddon: ClassifyUpgrades 尚未初始化（FMLCommonSetupEvent 未执行）");
        }
        return type;
    }

    @Override
    public List<IUpgradeItem.UpgradeConflictDefinition> getUpgradeConflicts() {
        return List.of();
    }

    @Override
    public Component getName() {
        return Component.translatable("item.sbaddon.classify_upgrade");
    }

    /** 供客户端页签图标 / 容器使用，避免各处重复写注册名。 */
    @Nullable
    public static DeferredItem<ClassifyUpgradeItem> deferredItem() {
        return com.huanj.sbaddon.registry.SBItems.CLASSIFY_UPGRADE;
    }

    public static Item item() {
        return com.huanj.sbaddon.registry.SBItems.CLASSIFY_UPGRADE.get();
    }
}
