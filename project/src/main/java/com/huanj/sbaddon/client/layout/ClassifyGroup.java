package com.huanj.sbaddon.client.layout;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 分类页里的一个分组。
 * <p>
 * 以前分组就是"模组命名空间"，现在统一成"任意物品谓词"：
 * 模组组的谓词是 {@code namespace 相等}，属性过滤器组的谓词来自 Create 的过滤器本体。
 * 这样布局层不需要区分两种来源，只按顺序取第一个命中项。
 *
 * @param id        稳定标识：{@code mod:<namespace>} / {@code attr:<hash>}；也用于折叠状态与取物空洞归属
 * @param icon      横幅左侧图标
 * @param label     横幅文字
 * @param predicate 物品归属判定
 */
public record ClassifyGroup(String id, ItemStack icon, Component label, Predicate<ItemStack> predicate) {
}
