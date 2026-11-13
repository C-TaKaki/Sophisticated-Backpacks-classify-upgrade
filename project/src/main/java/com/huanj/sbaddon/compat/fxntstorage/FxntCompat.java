package com.huanj.sbaddon.compat.fxntstorage;

import com.huanj.sbaddon.SBAddon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * FXNT Storage 兼容的总开关。
 * <p>
 * <b>这个类（以及它的全部依赖）不得引用任何 {@code net.fxnt.*} 类型</b> ——
 * 它是「未安装 FXNT 时也不能炸」的唯一保证：只有 {@link #isLoaded()} 为真时，
 * 才会去触碰 {@link FxntCompatContent}，进而加载真正依赖 FXNT 的类。
 */
public final class FxntCompat {

    public static final String MOD_ID = "fxntstorage";

    private static Boolean loaded;

    private FxntCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    /** 由主模组构造函数调用；未安装 FXNT 时只打一行日志，什么都不注册。 */
    public static void init(IEventBus modEventBus) {
        if (!isLoaded()) {
            SBAddon.LOGGER.info("[sbaddon] 未检测到 Create: Storage(fxntstorage)，跳过其兼容内容");
            return;
        }
        FxntCompatContent.register(modEventBus);
        SBAddon.LOGGER.info("[sbaddon] 已启用 Create: Storage(fxntstorage) 兼容：分类升级可在其背包中使用");
    }
}
