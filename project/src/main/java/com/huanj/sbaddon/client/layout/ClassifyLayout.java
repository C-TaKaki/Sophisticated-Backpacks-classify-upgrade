package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.client.ModNames;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 分类视图的纯计算层：给定背包内容 + 白名单 + 滚动行数，算出「每个存储槽画在哪」「横幅画在哪」。
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │ [图标] 模组名                      │  ← 横幅占满一整行
 *   ├──────────────────────────────────┤
 *   │ [物品][物品][物品][空][空]...      │  ← 该组物品按行流式排，行尾空格也是真实槽位
 *   └──────────────────────────────────┘
 * </pre>
 * 组内行尾的空格会填上真实空槽，玩家把物品放进去后下一帧自动归类到对应组。
 * 超出可视行数的内容随滚动偏移移动，滚出范围的槽位直接隐藏。
 */
public final class ClassifyLayout {

    public static final int CELL = 18;

    /** 分类横幅与下方物品格子之间的空隙（已按需求取消）。 */
    public static final int BANNER_GAP = 0;

    /** 横幅贴图四边向内缩进的像素数（0 = 不缩，铺满整行）。 */
    public static final int BANNER_INSET = 0;

    private ClassifyLayout() {
    }

    public record Banner(int x, int y, int width, ItemStack icon, Component label, String id) {
    }

    public record Placement(int slotIndex, int x, int y) {
    }

    public record Result(List<Banner> banners, List<Placement> placements, int totalRows) {
        public static Result empty() {
            return new Result(List.of(), List.of(), 0);
        }
    }

    /** 一个分类块：分组信息 + 它名下的存储槽（含取物空洞）。 */
    private record Block(ClassifyGroup group, List<Integer> items) {
    }

    /**
     * @param collapsed 收起的分类分组（横幅右侧的按钮切换）；收起后该组只留横幅，物品不占位，
     *                  后面的组顺着往上提 —— 过渡由调用方插值
     */
    public static Result compute(ClassifySource source, int cols, int rows, int scrollRows,
                                 Predicate<ItemStack> filter, Predicate<String> collapsed,
                                 int originX, int originY) {
        int storageSlots = source.storageSlots();
        if (storageSlots <= 0 || cols <= 0 || rows <= 0) {
            return Result.empty();
        }
        Predicate<String> collapsedGroups = collapsed == null ? groupId -> false : collapsed;

        List<ItemStack> samples = source.samples();
        // 分组来源统一：模组样本 → 按命名空间；属性过滤器样本 → 按过滤器自带的匹配逻辑。
        // Create 缺席 / 过滤器不可解析时该样本不产生分组，模组分类照常工作。
        List<ClassifyGroup> groups = ClassifyGroups.build(samples, Minecraft.getInstance().level,
                source.customNames());

        // 删掉的分类不能再靠"本次会话活跃过"继续挂横幅：每帧按当前配置收缩一次，
        // 这样增删分类立刻生效，不用重开背包
        Set<String> configuredGroups = new HashSet<>();
        for (ClassifyGroup group : groups) {
            configuredGroups.add(group.id());
        }
        configuredGroups.add(SlotMemory.FALLBACK);
        SlotMemory.retainActive(configuredGroups);

        SlotMemory.syncMenu(source.identity(), storageSlots);

        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (ClassifyGroup group : groups) {
            buckets.put(group.id(), new ArrayList<>());
        }
        List<Integer> fallback = new ArrayList<>();

        boolean[] occupied = new boolean[storageSlots];
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = source.stackAt(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (filter != null && !filter.test(stack)) {
                // 被搜索过滤掉的物品：占住槽位但不进任何组（保持隐藏），也不能当空槽用
                occupied[i] = true;
                continue;
            }
            occupied[i] = true;
            // 先声明的组优先：属性过滤器排在某个模组组前面时，命中的物品归过滤器
            String groupKey = SlotMemory.FALLBACK;
            for (ClassifyGroup group : groups) {
                if (group.predicate().test(stack)) {
                    groupKey = group.id();
                    break;
                }
            }
            List<Integer> bucket = buckets.get(groupKey);
            // 记住这个槽归属哪个组：物品取走后槽变空，靠它继续占住原来的位置
            SlotMemory.remember(i, groupKey);
            // 这个组本次会话里有过物品 —— 即使等下被取空，横幅也留着，直到重新打开背包
            SlotMemory.markActive(groupKey);
            if (bucket != null) {
                bucket.add(i);
            } else {
                fallback.add(i);
            }
        }

        // 空着但仍记着归属的槽 = 取物留下的空洞。它们会被塞回原组，挡住"后面的物品往前补位"
        Set<Integer> holes = new LinkedHashSet<>();
        for (int i = 0; i < storageSlots; i++) {
            if (!occupied[i] && SlotMemory.ownerOf(i) != null) {
                holes.add(i);
            }
        }
        Set<Integer> claimed = new HashSet<>();

        // 1) 先把分组汇成有序的分类块（兜底组排最后），取物空洞并回各自的块。
        //    折叠的块照样列出，只是不占格子 —— 它的物品仍然占着存储槽，不能给别人用。
        List<Block> blocks = new ArrayList<>();
        for (ClassifyGroup group : groups) {
            List<Integer> items = buckets.get(group.id());
            if (items == null) {
                continue;
            }
            if (items.isEmpty() && !SlotMemory.wasActive(group.id())) {
                // 本次会话里从没装过这个分类的东西：不画横幅。重新打开背包后也会走这里，
                // 所以"空标签只在重开时隐藏"自然成立
                continue;
            }
            blocks.add(new Block(group, mergeHoles(items, group.id(), holes, claimed)));
        }
        if (!fallback.isEmpty() || SlotMemory.wasActive(SlotMemory.FALLBACK)) {
            blocks.add(new Block(
                    new ClassifyGroup(SlotMemory.FALLBACK, iconFor(samples, null),
                            Component.translatable("gui.sbaddon.classify.fallback"), stack -> false),
                    mergeHoles(fallback, SlotMemory.FALLBACK, holes, claimed)));
        }

        // 没被任何现存组认领的空洞（例如白名单里删掉了那个分类）→ 释放，回到普通空槽池
        Iterator<Integer> remainingHoles = holes.iterator();
        while (remainingHoles.hasNext()) {
            int slot = remainingHoles.next();
            if (!claimed.contains(slot)) {
                SlotMemory.forget(slot);
                remainingHoles.remove();
            }
        }

        List<Integer> emptySlots = new ArrayList<>();
        for (int i = 0; i < storageSlots; i++) {
            if (!occupied[i] && !holes.contains(i)) {
                emptySlots.add(i);
            }
        }

        // 2) 剩余空槽按分类平均分配；除不尽的部分按分类列表从上到下依次补一格。
        //    折叠的块不参与均分（它一个格子都不显示），但它占用的存储槽本来就不在空槽池里。
        int visibleBlocks = 0;
        for (Block block : blocks) {
            if (!collapsedGroups.test(block.group().id())) {
                visibleBlocks++;
            }
        }
        int share = visibleBlocks <= 0 ? 0 : emptySlots.size() / visibleBlocks;
        int remainder = visibleBlocks <= 0 ? 0 : emptySlots.size() % visibleBlocks;
        int[] quota = new int[blocks.size()];
        int visibleIndex = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (collapsedGroups.test(block.group().id())) {
                continue;
            }
            int bonus = share + (visibleIndex < remainder ? 1 : 0);
            visibleIndex++;
            quota[i] = block.items().size() + bonus;
        }

        int scrollPx = scrollRows * CELL;
        int viewHeight = rows * CELL;
        List<Banner> banners = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();

        if (blocks.isEmpty()) {
            // 一个分类都没有（背包空 / 白名单全空）：退回最朴素的整格网格，
            // 否则所有槽位都会被当成"没有归属"隐藏，背包看起来是空的、东西也放不进去
            for (int i = 0; i < emptySlots.size(); i++) {
                int viewY = (i / cols) * CELL - scrollPx;
                if (viewY >= 0 && viewY < viewHeight) {
                    placements.add(new Placement(emptySlots.get(i),
                            originX + (i % cols) * CELL, originY + viewY));
                }
            }
            return new Result(List.of(), placements, (emptySlots.size() + cols - 1) / cols);
        }

        // 3) 逐块摆放：横幅占一行，下面是「物品 + 分到的空槽」，按列流式排。
        //    每个分类从新的一行开始，块与块之间不会互相穿插。
        int emptyCursor = 0;
        int y = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            int bannerY = y - scrollPx;
            if (bannerY >= 0 && bannerY < viewHeight) {
                banners.add(new Banner(originX, originY + bannerY, cols * CELL,
                        block.group().icon(), block.group().label(), block.group().id()));
            }
            int itemTop = y + CELL + BANNER_GAP;
            int count = quota[i];
            for (int k = 0; k < count; k++) {
                int slotIndex = k < block.items().size()
                        ? block.items().get(k)
                        : (emptyCursor < emptySlots.size() ? emptySlots.get(emptyCursor++) : -1);
                if (slotIndex < 0) {
                    continue;
                }
                int cellY = itemTop + (k / cols) * CELL;
                int viewY = cellY - scrollPx;
                if (viewY >= 0 && viewY < viewHeight) {
                    placements.add(new Placement(slotIndex, originX + (k % cols) * CELL, originY + viewY));
                }
            }
            int blockRows = (count + cols - 1) / cols;
            y = itemTop + blockRows * CELL;
        }
        int totalRows = (y + CELL - 1) / CELL;

        return new Result(banners, placements, totalRows);
    }

    /**
     * 把该组的空洞并进真实物品序列。两者都按存储槽索引排，所以空洞正好落回原位 ——
     * 后面的物品就不会往前补位。
     * <p>
     * 物品被全部取走时，这里仍然返回"只剩空洞（或空列表）"的序列：调用方会照常画横幅，
     * 标签不会消失。空标签的隐藏交给「重新打开背包」——那时 {@code wasActive} 已重置。
     */
    private static List<Integer> mergeHoles(List<Integer> items, String groupKey,
                                            Set<Integer> holes, Set<Integer> claimed) {
        List<Integer> groupHoles = new ArrayList<>();
        for (int slot : holes) {
            if (groupKey.equals(SlotMemory.ownerOf(slot))) {
                groupHoles.add(slot);
            }
        }
        if (groupHoles.isEmpty()) {
            return items;
        }
        List<Integer> merged = new ArrayList<>(items.size() + groupHoles.size());
        merged.addAll(items);
        merged.addAll(groupHoles);
        merged.sort(Integer::compareTo);
        claimed.addAll(groupHoles);
        return merged;
    }

    private static ItemStack iconFor(List<ItemStack> samples, String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // 优先用该模组创造标签页的图标，和玩家在创造模式里看到的保持一致
        ItemStack tabIcon = ModNames.icon(namespace);
        if (!tabIcon.isEmpty()) {
            return tabIcon;
        }
        for (ItemStack sample : samples) {
            if (sample.isEmpty()) {
                continue;
            }
            if (BuiltInRegistries.ITEM.getKey(sample.getItem()).getNamespace().equals(namespace)) {
                return sample;
            }
        }
        return ItemStack.EMPTY;
    }
}
