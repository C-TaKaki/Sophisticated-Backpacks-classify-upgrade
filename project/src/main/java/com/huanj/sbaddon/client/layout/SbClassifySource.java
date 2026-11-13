package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

import java.util.List;
import java.util.Map;

/** 精妙背包的 {@link ClassifySource} 实现：数据全部取自存储菜单与分类升级容器。 */
public record SbClassifySource(StorageContainerMenuBase<?> menu, ClassifyUpgradeContainer container)
        implements ClassifySource {

    @Override
    public Object identity() {
        return menu;
    }

    @Override
    public int storageSlots() {
        return menu.getNumberOfStorageInventorySlots();
    }

    @Override
    public ItemStack stackAt(int index) {
        return menu.getSlot(index).getItem();
    }

    @Override
    public List<ItemStack> samples() {
        return container.getSampleStacks();
    }

    @Override
    public Map<String, String> customNames() {
        return container.getCustomNames();
    }
}
