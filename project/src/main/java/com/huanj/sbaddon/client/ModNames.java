package com.huanj.sbaddon.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 命名空间 -> 显示用的「模组创造模式标签页」信息（图标 + 名称）。
 * <p>
 * 一个模组可能有多个创造标签页，优先取主标签页（{@code modid:base} / {@code modid:main} 之类），
 * 找不到就用字典序最小的那个，保证每次结果一致。任何异常都退回命名空间 / 样本物品，绝不让 UI 崩。
 */
public final class ModNames {

    /** 视为主标签页的路径名（大多数模组的第一个标签页叫这些）。 */
    private static final String[] PRIMARY_PATHS = {"base", "main", "general", "creative", "creative_tab", "tab", "items", "all", "default"};

    private static final Map<String, CreativeModeTab> TAB_CACHE = new HashMap<>();
    private static final Map<String, ItemStack> ICON_CACHE = new HashMap<>();

    private ModNames() {
    }

    public static String namespaceOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return "minecraft";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key.getNamespace();
    }

    /**
     * 该命名空间的创造标签页。原版没有单一标签页，统一返回 null（由 {@link #displayName} 兜底）。
     */
    private static CreativeModeTab tabOf(String namespace) {
        if (namespace == null || namespace.isEmpty() || "minecraft".equals(namespace)) {
            return null;
        }
        CreativeModeTab cached = TAB_CACHE.get(namespace);
        if (cached != null) {
            return cached;
        }
        CreativeModeTab primary = null;
        List<ResourceLocation> candidates = new ArrayList<>();
        try {
            for (ResourceKey<CreativeModeTab> key : BuiltInRegistries.CREATIVE_MODE_TAB.registryKeySet()) {
                if (key.location().getNamespace().equals(namespace)) {
                    candidates.add(key.location());
                }
            }
            candidates.sort((a, b) -> a.getPath().compareTo(b.getPath()));
            for (ResourceLocation id : candidates) {
                if (isPrimary(id)) {
                    primary = BuiltInRegistries.CREATIVE_MODE_TAB.get(id);
                    break;
                }
            }
            if (primary == null && !candidates.isEmpty()) {
                primary = BuiltInRegistries.CREATIVE_MODE_TAB.get(candidates.get(0));
            }
        } catch (Throwable ignored) {
            // 注册表还没准备好，忽略
        }
        if (primary != null) {
            TAB_CACHE.put(namespace, primary);
        }
        return primary;
    }

    private static boolean isPrimary(ResourceLocation id) {
        String path = id.getPath();
        if (path.equals(id.getNamespace())) {
            return true;
        }
        for (String candidate : PRIMARY_PATHS) {
            if (candidate.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** 该命名空间创造标签页的图标；没有就返回空栈，由调用方退回样本物品。 */
    public static ItemStack icon(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack cached = ICON_CACHE.get(namespace);
        if (cached != null) {
            return cached;
        }
        ItemStack icon = ItemStack.EMPTY;
        try {
            CreativeModeTab tab = tabOf(namespace);
            if (tab != null) {
                ItemStack stack = tab.getIconItem();
                if (stack != null && !stack.isEmpty()) {
                    icon = stack.copyWithCount(1);
                }
            }
        } catch (Throwable ignored) {
            // 个别模组的标签页还没构建完，退回空栈
        }
        if (!icon.isEmpty()) {
            ICON_CACHE.put(namespace, icon);
        }
        return icon;
    }

    public static Component displayName(String namespace) {
        if ("minecraft".equals(namespace)) {
            return Component.translatable("gui.sbaddon.classify.vanilla");
        }
        CreativeModeTab tab = tabOf(namespace);
        if (tab != null) {
            // 创造标签页标题就是模组作者起的"分类名"，比模组 displayName 更贴合游戏内观感
            return tab.getDisplayName();
        }
        try {
            Optional<? extends net.neoforged.fml.ModContainer> container = ModList.get().getModContainerById(namespace);
            if (container.isPresent()) {
                IModInfo info = container.get().getModInfo();
                String name = info.getDisplayName();
                if (name != null && !name.isBlank()) {
                    // displayName 可能是语言键（汉化资源包靠翻译它实现中文名），
                    // 用 translatable 生成：有译文就用译文，没有就原样显示。
                    return Component.translatable(name);
                }
            }
        } catch (Throwable ignored) {
            // 某些环境 ModList 尚未就绪，忽略
        }
        return Component.literal(namespace);
    }
}
