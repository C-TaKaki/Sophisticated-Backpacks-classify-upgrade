package com.huanj.sbaddon.client.layout;

import com.huanj.sbaddon.client.ModNames;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
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
            return "attr:" + CreateAttributeFilterCompat.key(sample);
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
                            labelFor(id, filterLabel(sample), customNames), predicate));
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
                    labelFor(id, ModNames.displayName(namespace), customNames),
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
            return new Row(sample.copyWithCount(1), labelFor(id, filterLabel(sample), customNames));
        }
        String namespace = ModNames.namespaceOf(sample);
        ItemStack tabIcon = ModNames.icon(namespace);
        return new Row(tabIcon.isEmpty() ? sample.copyWithCount(1) : tabIcon,
                labelFor(id, ModNames.displayName(namespace), customNames));
    }

    /** 玩家改过名就用玩家的，否则用默认名。 */
    private static Component labelFor(String groupId, Component fallback, Map<String, String> customNames) {
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
