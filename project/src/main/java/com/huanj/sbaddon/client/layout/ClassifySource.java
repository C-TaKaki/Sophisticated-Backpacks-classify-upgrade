package com.huanj.sbaddon.client.layout;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 分类布局的输入源。
 * <p>
 * 布局算法本身只关心「有多少个存储槽、每个槽里是什么、分类样本是什么」，
 * 不关心宿主是精妙背包还是别的背包模组 —— 这里把宿主差异收敛成这一层接口，
 * 各兼容实现（{@code SbClassifySource} / FXNT 的适配器）负责提供数据。
 */
public interface ClassifySource {

    /** 宿主菜单实例，仅用于识别「换了一个背包」；只比引用，不参与相等判断。 */
    Object identity();

    /** 参与分类的存储槽数量（对应菜单槽位 0..n-1）。 */
    int storageSlots();

    /** 第 index 个存储槽当前装的物品。 */
    ItemStack stackAt(int index);

    /** 分类样本（槽位顺序即分类顺序）。 */
    List<ItemStack> samples();

    /** 玩家改过的分类名（分组 key → 名字），可为空表。 */
    Map<String, String> customNames();
}
