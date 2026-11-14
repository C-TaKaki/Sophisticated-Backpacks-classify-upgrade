package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.layout.SlotMemory;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

    // ---------------------------------------------------------------- 过滤器消耗

    /** 点击前的样本槽内容，用来判断这次点击是不是"新塞进来一只过滤器"。 */
    @Unique
    private ItemStack sbaddon$sampleBefore = ItemStack.EMPTY;
    @Unique
    private int sbaddon$sampleSlotId = -1;

    @Inject(method = "clicked", at = @At("HEAD"))
    private void sbaddon$captureSample(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        sbaddon$sampleSlotId = slotId;
        sbaddon$sampleBefore = ItemStack.EMPTY;
        StorageContainerMenuBase<?> menu = (StorageContainerMenuBase<?>) (Object) this;
        if (slotId >= 0 && slotId < menu.slots.size()) {
            sbaddon$sampleBefore = menu.getSlot(slotId).getItem().copy();
        }
    }

    /**
     * 样本槽是 SB 的「幽灵槽」（{@code IFilterSlot}）：往里放东西只复制 1 个，不扣玩家手上的数量。
     * <p>
     * 普通模组样本这样没问题（只是拿一个物品当图标），但属性过滤器是**带配置**的贵重物品，
     * 白拿等于凭空造过滤器。这里补一次真实消耗：槽里从「空 / 别的物品」变成「这只过滤器」时，
     * 从光标上扣掉一个。删掉该分类时由 {@link ClassifyUpgradeContainer} 负责归还。
     */
    @Inject(method = "clicked", at = @At("RETURN"))
    private void sbaddon$chargeFilter(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (player.level().isClientSide() || slotId != sbaddon$sampleSlotId) {
            return;
        }
        StorageContainerMenuBase<?> menu = (StorageContainerMenuBase<?>) (Object) this;
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return;
        }
        Slot slot = menu.getSlot(slotId);
        if (!(slot instanceof ClassifyUpgradeContainer.SampleSlot)) {
            return;
        }
        ItemStack now = slot.getItem();
        if (!CreateAttributeFilterCompat.isAttributeFilter(now)) {
            return;
        }
        // 槽里本来就是同一只过滤器（重复点同一个）：不再重复扣
        if (CreateAttributeFilterCompat.isAttributeFilter(sbaddon$sampleBefore)
                && CreateAttributeFilterCompat.key(sbaddon$sampleBefore)
                        .equals(CreateAttributeFilterCompat.key(now))) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        carried.shrink(1);
        menu.setCarried(carried);
    }
}
