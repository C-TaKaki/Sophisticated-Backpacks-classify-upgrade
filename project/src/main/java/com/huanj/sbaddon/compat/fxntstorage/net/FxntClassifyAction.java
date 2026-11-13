package com.huanj.sbaddon.compat.fxntstorage.net;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyData;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyMenu;
import net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类配置的服务端唯一入口。
 * <p>
 * 客户端没有任何写权限：所有增删改都发到这个包，由服务端改升级物品的 CUSTOM_DATA，
 * 再走背包菜单自带的槽位同步回客户端。这样多人游戏下不会出现两端各改各的。
 */
public record FxntClassifyAction(int action, int index, int dir, String groupId, String name)
        implements CustomPacketPayload {

    public static final int ACTION_AUTO = 0;
    public static final int ACTION_REMOVE = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_RENAME = 3;

    public static final Type<FxntClassifyAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "fxnt_classify_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FxntClassifyAction> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::action,
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::index,
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::dir,
                    ByteBufCodecs.STRING_UTF8, FxntClassifyAction::groupId,
                    ByteBufCodecs.STRING_UTF8, FxntClassifyAction::name,
                    FxntClassifyAction::new);

    public static FxntClassifyAction auto() {
        return new FxntClassifyAction(ACTION_AUTO, 0, 0, "", "");
    }

    public static FxntClassifyAction remove(int index) {
        return new FxntClassifyAction(ACTION_REMOVE, index, 0, "", "");
    }

    public static FxntClassifyAction move(int index, int dir) {
        return new FxntClassifyAction(ACTION_MOVE, index, dir, "", "");
    }

    public static FxntClassifyAction rename(String groupId, String name) {
        return new FxntClassifyAction(ACTION_RENAME, 0, 0, groupId == null ? "" : groupId,
                name == null ? "" : name);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof BackpackMenu menu)) {
                FxntClassifyMenu.logMissingMenu();
                return;
            }
            Slot slot = FxntClassifyMenu.findUpgradeSlot(menu);
            if (slot == null) {
                return;
            }
            ItemStack stack = slot.getItem();
            HolderLookup.Provider registries = player.level().registryAccess();
            List<ItemStack> samples = new ArrayList<>(FxntClassifyData.snapshot(stack, registries).samples());

            switch (action) {
                case ACTION_AUTO -> samples = FxntClassifyData.autoClassify(samples,
                        FxntClassifyMenu.storageStacks(menu));
                case ACTION_REMOVE -> {
                    if (index < 0 || index >= samples.size()) {
                        return;
                    }
                    samples.remove(index);
                }
                case ACTION_MOVE -> {
                    int target = index + dir;
                    if (index < 0 || index >= samples.size() || target < 0 || target >= samples.size()) {
                        return;
                    }
                    ItemStack moved = samples.remove(index);
                    samples.add(target, moved);
                }
                case ACTION_RENAME -> {
                    FxntClassifyData.setName(stack, groupId, name);
                    slot.setChanged();
                    return;
                }
                default -> {
                    return;
                }
            }

            FxntClassifyData.writeSamples(stack, samples, registries);
            // 升级槽的 setChanged 会落到 BackpackContainer#setDataChanged()，
            // 背包内容随 CONTAINER 组件落盘，槽位差异再自动同步给客户端
            slot.setChanged();
        });
    }
}
