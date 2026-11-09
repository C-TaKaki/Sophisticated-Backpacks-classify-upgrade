package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.client.ModNames;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.SortBy;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 分类视图的纯计算层：给定背包内容 + 白名单，算出「每个存储槽应该画在哪」以及「横幅画在哪」。
 * <p>
 * 布局规则（决策：整行横幅）：
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │ [图标] 模组名                      │  ← 横幅占满一整行
 *   ├──────────────────────────────────┤
 *   │ [物品][物品][物品]...              │  ← 该组物品按行流式排
 *   ├──────────────────────────────────┤
 *   │ [图标] 下一个模组                  │
 *   └──────────────────────────────────┘
 * </pre>
 * 空槽统一推到内容之后，保持背包底部的空位观感。
 */
public final class ClassifyLayout {

    private static final int CELL = 18;

    private ClassifyLayout() {
    }

    public record Banner(int x, int y, int width, ItemStack icon, Component label) {
    }

    public record Placement(int slotIndex, int x, int y) {
    }

    public record Result(List<Banner> banners, List<Placement> placements, List<Integer> hiddenSlots) {
        public static Result empty() {
            return new Result(List.of(), List.of(), List.of());
        }
    }

    public static Result compute(StorageContainerMenuBase<?> menu, ClassifyUpgradeContainer container,
                                 int cols, int rows, Predicate<ItemStack> filter,
                                 int originX, int originY) {
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        if (storageSlots <= 0 || cols <= 0 || rows <= 0) {
            return Result.empty();
        }

        List<String> whitelist = container.getModWhitelist();
        List<ItemStack> samples = container.getSampleStacks();

        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (String namespace : whitelist) {
            buckets.put(namespace, new ArrayList<>());
        }
        List<Integer> fallback = new ArrayList<>();

        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty() || (filter != null && !filter.test(stack))) {
                continue;
            }
            String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
            List<Integer> bucket = buckets.get(namespace);
            if (bucket != null) {
                bucket.add(i);
            } else {
                fallback.add(i);
            }
        }

        fallback.sort(comparator(menu, container.getFallbackSort()));

        List<Banner> banners = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        List<Integer> used = new ArrayList<>();

        int row = 0;
        for (Map.Entry<String, List<Integer>> entry : buckets.entrySet()) {
            List<Integer> items = entry.getValue();
            if (items.isEmpty()) {
                continue;
            }
            row = placeGroup(row, cols, rows, entry.getKey(), iconFor(samples, entry.getKey()),
                    items, originX, originY, banners, placements, used);
        }
        if (!fallback.isEmpty()) {
            row = placeGroup(row, cols, rows, "", iconFor(samples, null),
                    fallback, originX, originY, banners, placements, used);
        }

        // 剩余空槽按顺序填在后面（保持背包底部空位观感），超出可视区就隐藏
        boolean[] taken = new boolean[storageSlots];
        for (int index : used) {
            taken[index] = true;
        }
        List<Integer> hidden = new ArrayList<>();
        int cell = row * cols;
        for (int i = 0; i < storageSlots; i++) {
            if (taken[i]) {
                continue;
            }
            int r = cell / cols;
            int c = cell % cols;
            if (r >= rows) {
                hidden.add(i);
            } else {
                placements.add(new Placement(i, originX + c * CELL, originY + r * CELL));
            }
            cell++;
        }
        return new Result(banners, placements, hidden);
    }

    private static int placeGroup(int row, int cols, int rows, String namespace, ItemStack icon,
                                  List<Integer> items, int originX, int originY,
                                  List<Banner> banners, List<Placement> placements, List<Integer> used) {
        Component label = namespace.isEmpty()
                ? Component.translatable("gui.sbaddon.classify.fallback")
                : ModNames.displayName(namespace);

        if (row < rows) {
            banners.add(new Banner(originX, originY + row * CELL, cols * CELL - 1, icon, label));
        }
        row++;

        for (int k = 0; k < items.size(); k++) {
            int r = row + k / cols;
            int c = k % cols;
            int slotIndex = items.get(k);
            used.add(slotIndex);
            if (r < rows) {
                placements.add(new Placement(slotIndex, originX + c * CELL, originY + r * CELL));
            }
        }
        return row + (items.size() + cols - 1) / cols;
    }

    private static ItemStack iconFor(List<ItemStack> samples, String namespace) {
        if (namespace == null) {
            return ItemStack.EMPTY;
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

    private static Comparator<Integer> comparator(StorageContainerMenuBase<?> menu, SortBy sortBy) {
        Comparator<Integer> byName = Comparator.comparing(
                i -> menu.getSlot(i).getItem().getHoverName().getString());
        return switch (sortBy) {
            case COUNT -> Comparator.<Integer>comparingInt(i -> -menu.getSlot(i).getItem().getCount())
                    .thenComparing(byName);
            case TAGS -> Comparator.<Integer, String>comparing(i -> menu.getSlot(i).getItem().getTags()
                            .map(Object::toString).sorted().findFirst().orElse(""))
                    .thenComparing(byName);
            default -> byName;
        };
    }
}
