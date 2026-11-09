package com.huanj.sbaddon.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.Optional;

/** 命名空间 -> 显示名。任何异常都退回命名空间本身，绝不让 UI 崩。 */
public final class ModNames {

    private ModNames() {
    }

    public static String namespaceOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return "minecraft";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key.getNamespace();
    }

    public static Component displayName(String namespace) {
        if ("minecraft".equals(namespace)) {
            return Component.translatable("gui.sbaddon.classify.vanilla");
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
