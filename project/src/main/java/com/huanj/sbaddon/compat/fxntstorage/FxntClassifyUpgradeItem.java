package com.huanj.sbaddon.compat.fxntstorage;

import net.fxnt.fxntstorage.item.upgrades.UpgradeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.TooltipContext;

import java.util.List;

/**
 * FXNT Storage 版「分类扩展升级」物品。
 * <p>
 * 继承 FXNT 自己的 {@link UpgradeItem} 是刻意的：
 * {@code UpgradeSlot#mayPlace} 会把它强转成 {@code UpgradeItem}，不继承就会 ClassCastException。
 * 物品本身不做任何事 —— 生效逻辑全部在客户端布局（{@code BackpackScreenMixin}）
 * 和服务端数据包（{@code FxntClassifyAction}）里。
 */
public class FxntClassifyUpgradeItem extends UpgradeItem {

    public FxntClassifyUpgradeItem(Properties properties) {
        super(properties, "fxnt_classify_upgrade");
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.sbaddon.fxnt_classify_upgrade.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.sbaddon.fxnt_classify_upgrade.usage")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
