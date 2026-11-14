package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.layout.SlotMemory;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 精妙背包自带的「整理背包」。
 * <p>
 * 客户端点排序按钮走的是这个方法的客户端分支：{@code sendToServer(...)} 后直接返回，
 * 真正的重排在服务端做，结果再同步回来。所以在这里清空分类视图的空位记忆，
 * 服务端把物品紧凑排好同步回来时，布局就是紧凑的 —— 这正是用户要的
 * 「只有整理才补位」。
 */
@Mixin(StorageContainerMenuBase.class)
public abstract class StorageContainerMenuBaseMixin {

    @Inject(method = "sort", at = @At("HEAD"))
    private void sbaddon$forgetKeptHoles(CallbackInfo ci) {
        SlotMemory.forgetAll();
    }
}
