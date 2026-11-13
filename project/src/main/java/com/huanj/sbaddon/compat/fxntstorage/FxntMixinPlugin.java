package com.huanj.sbaddon.compat.fxntstorage;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 让 FXNT 兼容的 mixin 只在装了 Create: Storage 时生效。
 * <p>
 * 这是「未安装时绝不影响原有功能」的关键：mixin 配置本身是 {@code required: false}，
 * 这里再按模组存在与否决定是否应用 —— 目标类不存在时整份配置被跳过，不会抛
 * {@code ClassNotFoundException}，也不会在日志里刷一屏失败。
 * <p>
 * 注意：本类在 mixin 加载阶段执行，那时 {@code ModList} 还没建好，只能用 {@link LoadingModList}。
 */
public class FxntMixinPlugin implements IMixinConfigPlugin {

    private static final String FXNT_MOD_ID = "fxntstorage";

    private static final boolean FXNT_PRESENT = detect();

    private static boolean detect() {
        try {
            LoadingModList modList = LoadingModList.get();
            return modList != null && modList.getModFileById(FXNT_MOD_ID) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FXNT_PRESENT;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
