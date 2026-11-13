package com.huanj.sbaddon.compat.fxntstorage.client;

import com.huanj.sbaddon.client.layout.ClassifySource;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyData;
import net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** FXNT 背包菜单的 {@link ClassifySource} 实现：只把物品存储区（0..107）交给分类布局。 */
public record FxntClassifySource(BackpackMenu menu, List<ItemStack> samples, Map<String, String> names)
        implements ClassifySource {

    @Override
    public Object identity() {
        return menu;
    }

    @Override
    public int storageSlots() {
        return menu.layout.items().getCount();
    }

    @Override
    public ItemStack stackAt(int index) {
        return menu.getSlot(index).getItem();
    }

    @Override
    public List<ItemStack> samples() {
        return samples;
    }

    @Override
    public Map<String, String> customNames() {
        return names;
    }

    /** 供客户端每帧取数据用：读一次升级物品的快照。 */
    public static FxntClassifyData.Snapshot snapshot(ItemStack upgradeStack, net.minecraft.core.HolderLookup.Provider registries) {
        if (upgradeStack.isEmpty() || registries == null) {
            return FxntClassifyData.Snapshot.empty();
        }
        return FxntClassifyData.snapshot(upgradeStack, registries);
    }
}
