package com.huanj.sbaddon.compat.fxntstorage;

import com.huanj.sbaddon.SBAddon;
import net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 在 FXNT 背包菜单里定位分类升级物品与物品槽的工具集合。 */
public final class FxntClassifyMenu {

    private FxntClassifyMenu() {
    }

    /** 升级槽里那个分类升级物品所在的槽；没装返回 null。 */
    public static Slot findUpgradeSlot(BackpackMenu menu) {
        for (int i : menu.layout.upgrades().range()) {
            Slot slot = menu.getSlot(i);
            if (slot.getItem().getItem() instanceof FxntClassifyUpgradeItem) {
                return slot;
            }
        }
        return null;
    }

    /** 物品存储区（108 格）里的全部栈，用于一键分类。 */
    public static List<ItemStack> storageStacks(BackpackMenu menu) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i : menu.layout.items().range()) {
            stacks.add(menu.getSlot(i).getItem());
        }
        return stacks;
    }

    public static void logMissingMenu() {
        SBAddon.LOGGER.warn("[sbaddon] 分类数据包到达时玩家未打开 FXNT 背包菜单，已忽略");
    }
}
