package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.client.ModNames;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把配置页的样本槽翻译成「分组列表」。
 * <p>
 * 样本槽是唯一数据源：普通物品 → 按模组分（谓词 = 命名空间相等）；
 * Create 属性过滤器 → 按过滤器分（谓词 = 过滤器自带的匹配逻辑）。
 * 列表顺序 = 样本槽顺序 = 界面行顺序 = 匹配优先级。
 */
public final class ClassifyGroups {

    /** 属性过滤器分组 id 的前缀。匹配优先级靠它判定（过滤器恒为最高）。 */
    public static final String ATTR_PREFIX = "attr:";

    /**
     * 直接记在样本物品 CUSTOM_DATA 里的自定义名。
     * <p>
     * 为什么不只靠「分组 id → 名字」的表：属性过滤器的 id 由 {@code filter.getComponents()} 的字符串
     * 算 hash 得来，配置本身经历过 NBT 往返 / 重启后字符串可能变，id 跟着变，表里那条就查不到了
     * （表现就是只有属性过滤器分类的改名会丢）。写进样本自己身上就没有这个依赖：
     * 样本存在升级 NBT 里，名字跟着样本走，顺带免疫重排。
     */
    public static final String NAME_TAG = "SbaddonName";

    /**
     * 样本物品上「这只过滤器已经付过费」的标记。
     * <p>
     * 删除分类时要把属性过滤器还给玩家，但只有真付过费的才该还 —— 没有这个标记，
     * 只要有办法把一只过滤器弄进样本槽（老存档、自动分类、以及以前的幽灵放入漏洞），
     * 删分类就等于凭空印一只出来。
     */
    public static final String PAID_TAG = "SbaddonPaid";

    /** 样本上直接记的名字；没有返回 {@code null}。 */
    public static String storedName(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return null;
        }
        CompoundTag data = sample.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String name = data.getString(NAME_TAG);
        return name.isBlank() ? null : name;
    }

    /** 这只样本是否已经为它付过费（属性过滤器用）。 */
    public static boolean isPaid(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return false;
        }
        return sample.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBoolean(PAID_TAG);
    }

    /** 记下「已付费」标记。 */
    public static void markPaid(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return;
        }
        CompoundTag data = sample.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (data.getBoolean(PAID_TAG)) {
            return;
        }
        data.putBoolean(PAID_TAG, true);
        sample.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    /**
     * 退还给玩家前，把我们自己加在样本上的标记全部剥掉。
     * <p>
     * 退回去的应该是玩家原来那只过滤器，而不是带一堆内部 NBT 的样本；否则
     * 它和新拿的过滤器组件不同，去重 / 光标比对都会被这些标记干扰。
     */
    public static void stripTags(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(NAME_TAG) && !data.contains(PAID_TAG)) {
            return;
        }
        data.remove(NAME_TAG);
        data.remove(PAID_TAG);
        if (data.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
    }

    /**
     * 把名字写进样本物品（{@code null} / 空串 = 删掉，恢复默认名）。
     *
     * @return 是否真的改动了（调用方据此决定要不要回写样本槽）
     */
    public static boolean storeName(ItemStack sample, String name) {
        if (sample == null || sample.isEmpty()) {
            return false;
        }
        CompoundTag data = sample.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String old = data.getString(NAME_TAG);
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) {
            if (old.isEmpty()) {
                return false;
            }
            data.remove(NAME_TAG);
        } else {
            if (cleaned.equals(old)) {
                return false;
            }
            data.putString(NAME_TAG, cleaned);
        }
        if (data.isEmpty()) {
            sample.remove(DataComponents.CUSTOM_DATA);
        } else {
            sample.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        return true;
    }

    /** 界面行上要显示的内容（图标 + 文字）。属性过滤器直接用它自己的物品做图标。 */
    public record Row(ItemStack icon, Component label) {
    }

    private ClassifyGroups() {
    }

    /** 样本槽对应的分组 key；空栈返回 {@code null}。 */
    public static String groupId(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return null;
        }
        if (CreateAttributeFilterCompat.isAttributeFilter(sample)) {
            return ATTR_PREFIX + CreateAttributeFilterCompat.key(sample);
        }
        return "mod:" + ModNames.namespaceOf(sample);
    }

    /**
     * 构建分组。
     * <p>
     * 属性过滤器在以下情况不会产出分组（但样本槽照旧占位）：Create 缺席、解析失败、
     * 过滤器为空、黑名单模式、界面还没进世界。所以"适配失效"不会连带毁掉模组分类。
     *
     * @param customNames 玩家改过的名字（分组 key → 名字），可为空
     */
    public static List<ClassifyGroup> build(List<ItemStack> samples, Level level, Map<String, String> customNames) {
        List<ClassifyGroup> groups = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (ItemStack sample : samples) {
            if (sample == null || sample.isEmpty()) {
                continue;
            }
            String id = groupId(sample);
            if (CreateAttributeFilterCompat.isAttributeFilter(sample)) {
                var predicate = CreateAttributeFilterCompat.predicate(sample, level);
                if (predicate == null) {
                    continue;
                }
                if (seenIds.add(id)) {
                    groups.add(new ClassifyGroup(id, sample.copyWithCount(1),
                            labelFor(id, sample, filterLabel(sample), customNames), predicate));
                }
                continue;
            }
            String namespace = ModNames.namespaceOf(sample);
            if (!seenIds.add(id)) {
                continue;
            }
            ItemStack tabIcon = ModNames.icon(namespace);
            groups.add(new ClassifyGroup(
                    id,
                    tabIcon.isEmpty() ? sample.copyWithCount(1) : tabIcon,
                    labelFor(id, sample, ModNames.displayName(namespace), customNames),
                    stack -> !stack.isEmpty() && namespace.equals(ModNames.namespaceOf(stack))));
        }
        return groups;
    }

    /** 单个样本槽在界面行上的图标与文字。 */
    public static Row describe(ItemStack sample, Map<String, String> customNames) {
        if (sample == null || sample.isEmpty()) {
            return new Row(ItemStack.EMPTY, Component.empty());
        }
        String id = groupId(sample);
        if (CreateAttributeFilterCompat.isAttributeFilter(sample)) {
            return new Row(sample.copyWithCount(1), labelFor(id, sample, filterLabel(sample), customNames));
        }
        String namespace = ModNames.namespaceOf(sample);
        ItemStack tabIcon = ModNames.icon(namespace);
        return new Row(tabIcon.isEmpty() ? sample.copyWithCount(1) : tabIcon,
                labelFor(id, sample, ModNames.displayName(namespace), customNames));
    }

    /**
     * 玩家改过名就用玩家的，否则用默认名。
     * <p>
     * 优先读样本物品上直接记的名字（稳定，跟着样本走），读不到再退回「分组 id → 名字」的表。
     * 表那条是为兼容已经存下来的旧数据保留的。
     */
    private static Component labelFor(String groupId, ItemStack sample, Component fallback,
                                      Map<String, String> customNames) {
        String stored = storedName(sample);
        if (stored != null) {
            return Component.literal(stored);
        }
        if (customNames == null || groupId == null) {
            return fallback;
        }
        String custom = customNames.get(groupId);
        return custom == null || custom.isBlank() ? fallback : Component.literal(custom);
    }

    /**
     * 属性过滤器行的文字：物品名 + 属性条目数。
     * <p>
     * 空过滤器（或 Create 不可用解析不出来）额外标注，否则玩家只会看到一行永远不出物品的空分类，不知道哪里错了。
     */
    private static Component filterLabel(ItemStack filter) {
        int count = CreateAttributeFilterCompat.attributeCount(filter);
        String suffix = count > 0 ? " (" + count + ")" : " (空)";
        return filter.getHoverName().copy().append(Component.literal(suffix));
    }
}
