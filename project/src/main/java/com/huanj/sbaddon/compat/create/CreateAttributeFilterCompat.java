package com.huanj.sbaddon.compat.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Predicate;

/**
 * 机械动力「属性过滤器」（{@code create:attribute_filter}）适配层 —— **纯反射，零编译期依赖**。
 * <p>
 * 这样写的原因：本模组不能强依赖 Create。没有 Create 时本类连类都加载不到 Create 的类，
 * 所有入口返回 {@code null} / {@code false}，调用方退化成「没有属性分类」即可，不抛异常。
 * <p>
 * 只借用 Create 自己的三个入口，语义完全交给 Create：
 * <ul>
 *   <li>{@code FilterItemStack.of(ItemStack)} → 包装器（必须是 {@code AttributeFilterItemStack}）；</li>
 *   <li>公开字段 {@code attributeTests} → 判空 / 条目数；</li>
 *   <li>{@code test(Level, ItemStack, boolean)} → 真正的匹配逻辑。</li>
 * </ul>
 * 属性类型有十几种（物品标签、创造页、名字包含、附魔、颜色……），自己解组件等于重写 Create，不做。
 */
public final class CreateAttributeFilterCompat {

    public static final ResourceLocation ATTRIBUTE_FILTER_ID =
            ResourceLocation.fromNamespaceAndPath("create", "attribute_filter");

    private static final String FILTER_STACK_CLASS =
            "com.simibubi.create.content.logistics.filter.FilterItemStack";
    private static final String ATTRIBUTE_STACK_CLASS =
            "com.simibubi.create.content.logistics.filter.FilterItemStack$AttributeFilterItemStack";

    /** null = 还没探测过。 */
    private static Boolean available;
    private static Method ofMethod;
    private static Method testMethod;
    private static Field attributeTestsField;
    private static Field whitelistModeField;

    private CreateAttributeFilterCompat() {
    }

    // ---------------------------------------------------------------- 探测

    /**
     * Create 是否可用（模组存在 + 反射自检通过）。
     * <p>
     * 结果缓存；任何异常都视为不可用 —— 版本升级把字段/方法改名了也只是"适配失效"，
     * 不会让整个分类功能连带崩掉。
     */
    public static boolean isAvailable() {
        if (available != null) {
            return available;
        }
        synchronized (CreateAttributeFilterCompat.class) {
            if (available != null) {
                return available;
            }
            available = Boolean.FALSE;
            try {
                if (net.neoforged.fml.ModList.get() == null
                        || !net.neoforged.fml.ModList.get().isLoaded("create")) {
                    return available;
                }
                Class<?> filterStack = Class.forName(FILTER_STACK_CLASS);
                Class<?> attributeStack = Class.forName(ATTRIBUTE_STACK_CLASS);
                ofMethod = filterStack.getMethod("of", ItemStack.class);
                testMethod = filterStack.getMethod("test", Level.class, ItemStack.class, boolean.class);
                attributeTestsField = attributeStack.getField("attributeTests");
                whitelistModeField = attributeStack.getField("whitelistMode");
                available = Boolean.TRUE;
            } catch (Throwable ignored) {
                // Create 缺席或 API 变了：保持 false，功能静默降级
            }
            return available;
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 这只物品是不是 Create 的属性过滤器。不依赖反射，注册表比对即可。 */
    public static boolean isAttributeFilter(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && ATTRIBUTE_FILTER_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * 过滤器里配置的属性条目数。
     *
     * @return 条目数；不可用 / 解析失败返回 {@code -1}
     */
    public static int attributeCount(ItemStack filter) {
        Object wrapper = wrapperOf(filter);
        if (wrapper == null) {
            return -1;
        }
        try {
            List<?> tests = (List<?>) attributeTestsField.get(wrapper);
            return tests == null ? -1 : tests.size();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 过滤器的白名单模式：{@code whitelist_disj} / {@code whitelist_conj} / {@code blacklist}。
     *
     * @return 小写枚举名；不可用返回 {@code null}
     */
    public static String mode(ItemStack filter) {
        Object wrapper = wrapperOf(filter);
        if (wrapper == null) {
            return null;
        }
        try {
            Object mode = whitelistModeField.get(wrapper);
            return mode == null ? null : mode.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 生成「这只物品是否属于该过滤器」的谓词。
     * <p>
     * 以下情况返回 {@code null}（调用方据此不创建分类）：
     * <ul>
     *   <li>Create 不可用 / 反射失败；</li>
     *   <li>过滤器为空（没有属性条目）—— 空过滤器的 {@code test} 恒为 false，留着只会多一个永远空的分组；</li>
     *   <li>黑名单模式 —— 它匹配"几乎全世界"，放进分桶视图会吞掉后面所有分类；</li>
     *   <li>{@code level} 为空（界面还没进世界）。</li>
     * </ul>
     */
    public static Predicate<ItemStack> predicate(ItemStack filter, Level level) {
        if (level == null) {
            return null;
        }
        Object wrapper = wrapperOf(filter);
        if (wrapper == null) {
            return null;
        }
        try {
            List<?> tests = (List<?>) attributeTestsField.get(wrapper);
            if (tests == null || tests.isEmpty()) {
                return null;
            }
            Object mode = whitelistModeField.get(wrapper);
            if (mode != null && "blacklist".equalsIgnoreCase(mode.toString())) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return stack -> {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            try {
                return Boolean.TRUE.equals(testMethod.invoke(wrapper, level, stack, false));
            } catch (Throwable ignored) {
                // 单次匹配出错（某个属性类型解析失败等）：当作不匹配，不影响其它分类
                return false;
            }
        };
    }

    /**
     * 过滤器自身的摘要行（Create 的 tooltip 内容：第一行是匹配模式，之后每行一条属性）。
     *
     * @return 摘要；不可用或取不到返回 {@code null}
     */
    public static List<net.minecraft.network.chat.Component> summary(ItemStack filter) {
        if (!isAttributeFilter(filter) || !isAvailable()) {
            return null;
        }
        try {
            Method makeSummary = filter.getItem().getClass().getMethod("makeSummary", ItemStack.class);
            Object result = makeSummary.invoke(filter.getItem(), filter);
            if (result instanceof List<?> lines && !lines.isEmpty()) {
                List<net.minecraft.network.chat.Component> out = new java.util.ArrayList<>(lines.size());
                for (Object line : lines) {
                    if (line instanceof net.minecraft.network.chat.Component component) {
                        out.add(component);
                    }
                }
                return out.isEmpty() ? null : out;
            }
        } catch (Throwable ignored) {
            // 版本差异 / 实例类型不对：调用方退回只显示名字
        }
        return null;
    }

    /**
     * 过滤器的稳定标识片段：同一份配置得到同一个串，不同配置得到不同串。
     * <p>
     * 只用于分组 key（折叠记忆 / 取物空洞归属），不需要密码学级唯一。
     */
    public static String key(ItemStack filter) {
        // 先剥掉我们自己的「自定义名」标签再算：样本上写了名字之后组件字符串会变，
        // 不剥的话同一个过滤器改名前后的分组 id 不同（折叠状态、旧名字表都会跟着失联）
        ItemStack cleaned = filter.copy();
        CompoundTag custom = cleaned.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if (custom.contains(com.huanj.sbaddon.client.layout.ClassifyGroups.NAME_TAG)) {
            custom.remove(com.huanj.sbaddon.client.layout.ClassifyGroups.NAME_TAG);
            if (custom.isEmpty()) {
                cleaned.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            } else {
                cleaned.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(custom));
            }
        }
        int hash = 31 * BuiltInRegistries.ITEM.getKey(cleaned.getItem()).hashCode()
                + String.valueOf(cleaned.getComponents()).hashCode();
        return Integer.toHexString(hash);
    }

    // ---------------------------------------------------------------- 内部

    private static Object wrapperOf(ItemStack filter) {
        if (!isAttributeFilter(filter) || !isAvailable()) {
            return null;
        }
        try {
            Object wrapper = ofMethod.invoke(null, filter);
            if (wrapper == null || !ATTRIBUTE_STACK_CLASS.equals(wrapper.getClass().getName())) {
                // 非属性过滤器（普通过滤器等）走不到这里，防御性判断
                return null;
            }
            return wrapper;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
