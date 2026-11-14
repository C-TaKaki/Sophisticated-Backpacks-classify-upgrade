package com.huanj.sbaddon.compat.fxntstorage;

import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeWrapper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FXNT Storage（Create: Storage）版分类升级的数据载体。
 * <p>
 * 和精妙背包那边一样，所有配置都写在**升级物品自身的 CUSTOM_DATA** 里 —— 物品跟着背包的
 * CONTAINER 组件走，所以不需要额外的存档格式、也不依赖任何 FXNT 的内部数据类。
 * <p>
 * 结构差异：这里只存「紧凑排列的非空样本」列表（SB 那边是 64 个固定槽位），
 * 因为 FXNT 没有样本槽，增删都由服务端改 NBT，槽位池没有意义。
 */
public final class FxntClassifyData {

    private static final String TAG_SAMPLES = "Samples";
    private static final String TAG_NAMES = "Names";

    /** 分类条目上限。界面最多显示 6 行，理论上够用，超出只影响性能不影响正确性。 */
    public static final int SAMPLE_LIMIT = 48;

    /** 一次读取的结果，客户端每帧都要用，所以缓存住避免反复反序列化。 */
    public record Snapshot(List<ItemStack> samples, Map<String, String> names) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), Map.of());
        }
    }

    /**
     * 缓存键。用**值**比较而不是引用比较：同一次数据变化，客户端拿到的 ItemStack 实例可能复用，
     * 也可能换成内容相同的新实例；按引用判会漏掉"数据变了但实例没换"的情况，
     * 表现就是要重开背包界面才刷新。
     */
    private static CustomData cacheKey;
    private static boolean cacheValid;
    private static Snapshot cache = Snapshot.empty();

    private FxntClassifyData() {
    }

    // ---------------------------------------------------------------- 原始读写

    public static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void setData(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ---------------------------------------------------------------- 读取

    /** 读一次快照（样本 + 自定义名），按组件引用缓存。 */
    public static Snapshot snapshot(ItemStack stack, HolderLookup.Provider registries) {
        CustomData key = stack.get(DataComponents.CUSTOM_DATA);
        if (cacheValid && java.util.Objects.equals(key, cacheKey)) {
            return cache;
        }
        List<ItemStack> samples = readSamples(stack, registries);
        Map<String, String> names = readNames(data(stack));
        cache = new Snapshot(List.copyOf(samples), Map.copyOf(names));
        cacheKey = key;
        cacheValid = true;
        return cache;
    }

    private static List<ItemStack> readSamples(ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag data = data(stack);
        List<ItemStack> samples = new ArrayList<>();
        if (!data.contains(TAG_SAMPLES)) {
            return samples;
        }
        ItemStackHandler handler = new ItemStackHandler(SAMPLE_LIMIT);
        // 空 tag 不反序列化：ItemStackHandler 会按 tag 里的 Size 重设容量
        CompoundTag samplesTag = data.getCompound(TAG_SAMPLES);
        if (samplesTag.isEmpty()) {
            return samples;
        }
        handler.deserializeNBT(registries, samplesTag);
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack sample = handler.getStackInSlot(i);
            if (!sample.isEmpty()) {
                samples.add(sample.copyWithCount(1));
            }
        }
        return samples;
    }

    private static Map<String, String> readNames(CompoundTag data) {
        Map<String, String> names = new LinkedHashMap<>();
        if (!data.contains(TAG_NAMES)) {
            return names;
        }
        CompoundTag tag = data.getCompound(TAG_NAMES);
        for (String key : tag.getAllKeys()) {
            String name = tag.getString(key);
            if (!name.isBlank()) {
                names.put(key, name);
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- 写入

    /** 把样本列表写回物品（紧凑存放，顺序即分类顺序）。 */
    public static void writeSamples(ItemStack stack, List<ItemStack> samples, HolderLookup.Provider registries) {
        ItemStackHandler handler = new ItemStackHandler(SAMPLE_LIMIT);
        int slot = 0;
        for (ItemStack sample : samples) {
            if (sample.isEmpty() || slot >= SAMPLE_LIMIT) {
                continue;
            }
            handler.setStackInSlot(slot++, sample.copyWithCount(1));
        }
        CompoundTag data = data(stack);
        data.put(TAG_SAMPLES, handler.serializeNBT(registries));
        setData(stack, data);
    }

    public static void setName(ItemStack stack, String groupId, String name) {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        CompoundTag data = data(stack);
        CompoundTag names = data.contains(TAG_NAMES) ? data.getCompound(TAG_NAMES) : new CompoundTag();
        if (name == null || name.isBlank()) {
            names.remove(groupId);
        } else {
            names.putString(groupId, name.strip());
        }
        if (names.isEmpty()) {
            data.remove(TAG_NAMES);
        } else {
            data.put(TAG_NAMES, names);
        }
        setData(stack, data);
    }

    // ---------------------------------------------------------------- 增删改

    /**
     * 拖入物品新增一条分类：按「命名空间」去重（和 {@link #autoClassify} 同一口径，
     * 属性过滤器也算在它自己的命名空间下），满了或已存在则原样返回。
     */
    public static List<ItemStack> addSample(List<ItemStack> existing, ItemStack stack) {
        List<ItemStack> result = new ArrayList<>(existing);
        if (stack == null || stack.isEmpty() || result.size() >= SAMPLE_LIMIT) {
            return result;
        }
        String key = groupKey(stack);
        for (ItemStack sample : result) {
            if (sample.isEmpty()) {
                continue;
            }
            if (key.equals(groupKey(sample))) {
                return result;
            }
        }
        // 整只存下来：属性过滤器的属性条目全在组件里，只留注册名会变成空过滤器
        result.add(stack.copyWithCount(1));
        return result;
    }

    /**
     * 样本的分组标识，和客户端 {@code ClassifyGroups.groupId} 是同一套规则：
     * 属性过滤器按「过滤器配置」区分，普通物品按命名空间区分。
     * <p>
     * 之前这里按命名空间去重 —— 于是 create 的模组样本一加，属性过滤器就被当成重复项拒掉了。
     */
    public static String groupKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        if (CreateAttributeFilterCompat.isAttributeFilter(stack)) {
            return "attr:" + CreateAttributeFilterCompat.key(stack);
        }
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "mod:" + (id == null ? "" : id.getNamespace());
    }

    /** 一键分类：扫描背包物品槽，把每个模组的第一个物品加为样本。 */
    public static List<ItemStack> autoClassify(List<ItemStack> existing, List<ItemStack> storageStacks) {
        List<ItemStack> result = new ArrayList<>(existing);
        // 已有分类跳过，避免把过滤器物品的命名空间当成已存在的模组
        java.util.Set<String> known = new java.util.LinkedHashSet<>(ClassifyUpgradeWrapper.modWhitelist(result));
        for (ItemStack stack : storageStacks) {
            if (stack.isEmpty() || result.size() >= SAMPLE_LIMIT) {
                continue;
            }
            String namespace = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getNamespace();
            if (known.contains(namespace) || !known.add(namespace)) {
                continue;
            }
            result.add(stack.copyWithCount(1));
        }
        return result;
    }
}
