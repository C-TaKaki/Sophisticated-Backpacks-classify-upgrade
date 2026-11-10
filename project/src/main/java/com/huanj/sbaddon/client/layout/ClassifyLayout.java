package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.client.ModNames;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

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

    private ClassifyLayout() {
    }

    public record Banner(int x, int y, int width, ItemStack icon, Component label) {
    }

    public record Placement(int slotIndex, int x, int y) {
    }

    public record Result(List<Banner> banners, List<Placement> placements, int totalRows) {
        public static Result empty() {
            return new Result(List.of(), List.of(), 0);
        }
    }

    /** 内容行内未被物品占用的单元格，用来摆放空槽。 */
    private record Cell(int row, int col) {
    }

    public static Result compute(StorageContainerMenuBase<?> menu, ClassifyUpgradeContainer container,
                                 int cols, int rows, int scrollRows, Predicate<ItemStack> filter,
                                 int originX, int originY) {
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        if (storageSlots <= 0 || cols <= 0 || rows <= 0) {
            return Result.empty();
        }

        List<String> whitelist = container.getModWhitelist();
        List<ItemStack> samples = container.getSampleStacks();

        SlotMemory.syncMenu(menu, storageSlots);

        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (String namespace : whitelist) {
            buckets.put(namespace, new ArrayList<>());
        }
        List<Integer> fallback = new ArrayList<>();

        boolean[] occupied = new boolean[storageSlots];
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (filter != null && !filter.test(stack)) {
                // 被搜索过滤掉的物品：占住槽位但不进任何组（保持隐藏），也不能当空槽用
                occupied[i] = true;
                continue;
            }
            occupied[i] = true;
            String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
            List<Integer> bucket = buckets.get(namespace);
            // 记住这个槽归属哪个组：物品取走后槽变空，靠它继续占住原来的位置
            SlotMemory.remember(i, bucket != null ? namespace : SlotMemory.FALLBACK);
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

        // 兜底组不再自己排序：直接沿用存储槽的原始顺序，而 SB 的排序按钮会重排实际物品，
        // 所以"跟随背包自带排序"是自然成立的。
        List<Banner> banners = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        List<Cell> gaps = new ArrayList<>();

        int row = 0;
        for (Map.Entry<String, List<Integer>> entry : buckets.entrySet()) {
            List<Integer> merged = mergeHoles(entry.getValue(), entry.getKey(), holes, claimed);
            if (merged == null) {
                continue;
            }
            row = placeGroup(row, cols, rows, scrollRows, entry.getKey(), iconFor(samples, entry.getKey()),
                    merged, originX, originY, banners, placements, gaps);
        }
        if (!fallback.isEmpty()) {
            List<Integer> merged = mergeHoles(fallback, SlotMemory.FALLBACK, holes, claimed);
            if (merged != null) {
                String fallbackNamespace = null;
                row = placeGroup(row, cols, rows, scrollRows, "", iconFor(samples, fallbackNamespace),
                        merged, originX, originY, banners, placements, gaps);
            }
        }
        int contentRows = row;

        // 没被任何现存组认领的空洞（例如白名单里删掉了那个分类）→ 释放，回到普通空槽池
        Iterator<Integer> remaining = holes.iterator();
        while (remaining.hasNext()) {
            int slot = remaining.next();
            if (!claimed.contains(slot)) {
                SlotMemory.forget(slot);
                remaining.remove();
            }
        }

        List<Integer> emptySlots = new ArrayList<>();
        for (int i = 0; i < storageSlots; i++) {
            if (!occupied[i] && !holes.contains(i)) {
                emptySlots.add(i);
            }
        }

        // 1) 先用空槽填满内容行内的空格，让每个可见格子都能放东西
        int cursor = 0;
        for (Cell gap : gaps) {
            if (cursor >= emptySlots.size()) {
                break;
            }
            int slotIndex = emptySlots.get(cursor++);
            int r = gap.row() - scrollRows;
            if (r >= 0 && r < rows) {
                placements.add(new Placement(slotIndex, originX + gap.col() * CELL, originY + r * CELL));
            }
        }

        // 2) 剩下的空槽顺着内容之后继续铺
        int tailRow = contentRows;
        int tailCol = 0;
        while (cursor < emptySlots.size()) {
            int slotIndex = emptySlots.get(cursor++);
            int r = tailRow - scrollRows;
            if (r >= 0 && r < rows) {
                placements.add(new Placement(slotIndex, originX + tailCol * CELL, originY + r * CELL));
            }
            tailCol++;
            if (tailCol >= cols) {
                tailCol = 0;
                tailRow++;
            }
        }
        int totalRows = tailCol > 0 ? tailRow + 1 : tailRow;

        return new Result(banners, placements, totalRows);
    }

    /**
     * 把该组的空洞并进真实物品序列。两者都按存储槽索引排，所以空洞正好落回原位 ——
     * 后面的物品就不会往前补位。
     * <p>
     * 组里已经没有任何真实物品时，空洞无处可挂，顺手释放，让这些槽回到普通空槽池；
     * 否则它们会被永远记住、永远占着那一格。
     */
    private static List<Integer> mergeHoles(List<Integer> items, String groupKey,
                                            Set<Integer> holes, Set<Integer> claimed) {
        List<Integer> groupHoles = new ArrayList<>();
        for (int slot : holes) {
            if (groupKey.equals(SlotMemory.ownerOf(slot))) {
                groupHoles.add(slot);
            }
        }
        if (items.isEmpty()) {
            for (int slot : groupHoles) {
                holes.remove(slot);
                SlotMemory.forget(slot);
            }
            return null;
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

    private static int placeGroup(int row, int cols, int rows, int scrollRows, String namespace, ItemStack icon,
                                  List<Integer> items, int originX, int originY,
                                  List<Banner> banners, List<Placement> placements, List<Cell> gaps) {
        Component label = namespace.isEmpty()
                ? Component.translatable("gui.sbaddon.classify.fallback")
                : ModNames.displayName(namespace);

        int bannerRow = row - scrollRows;
        if (bannerRow >= 0 && bannerRow < rows) {
            banners.add(new Banner(originX, originY + bannerRow * CELL, cols * CELL, icon, label));
        }
        row++;

        int itemRows = (items.size() + cols - 1) / cols;
        for (int k = 0; k < itemRows * cols; k++) {
            int r = row + k / cols;
            int c = k % cols;
            if (k >= items.size()) {
                // 该行剩余空格：先记下来，稍后用空槽填
                gaps.add(new Cell(r, c));
                continue;
            }
            int viewRow = r - scrollRows;
            if (viewRow >= 0 && viewRow < rows) {
                placements.add(new Placement(items.get(k), originX + c * CELL, originY + viewRow * CELL));
            }
        }
        return row + itemRows;
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
