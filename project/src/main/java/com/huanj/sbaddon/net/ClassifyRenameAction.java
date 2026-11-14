package com.huanj.sbaddon.net;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;

/**
 * 分类改名：精妙背包侧的 C2S 落地包。
 * <p>
 * 不走 SB 自己的容器数据包（那条路依赖容器 id 分发，改名之前只改到客户端那份物品副本，
 * 服务端没落盘；样本能存住是因为它们走的是真槽位的 setChanged 流程）。
 * 这里直接由服务端按「玩家当前打开的菜单」找到分类升级槽，写名字再把槽标脏，
 * 落盘路径和样本一致。
 */
public record ClassifyRenameAction(String groupId, String name) implements CustomPacketPayload {

    public static final Type<ClassifyRenameAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "classify_rename"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClassifyRenameAction> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ClassifyRenameAction::groupId,
                    ByteBufCodecs.STRING_UTF8, ClassifyRenameAction::name,
                    ClassifyRenameAction::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
                return;
            }
            for (UpgradeContainerBase<?, ?> container : menu.getUpgradeContainers().values()) {
                if (!(container instanceof ClassifyUpgradeContainer classify)) {
                    continue;
                }
                classify.applyRename(this.groupId, this.name);
                // 光改组件不会让背包本体标脏，存档写盘时会被丢掉；这一步才是真正的持久化
                markSlotChanged(menu);
                return;
            }
        });
    }

    /** 找到装着分类升级的那一格并标脏，让菜单把改动同步给客户端、存档把改动写进 NBT。 */
    private static void markSlotChanged(StorageContainerMenuBase<?> menu) {
        for (Slot slot : menu.slots) {
            if (!slot.hasItem()) {
                continue;
            }
            if (slot.getItem().getItem() instanceof com.huanj.sbaddon.upgrade.ClassifyUpgradeItem) {
                slot.setChanged();
                menu.broadcastChanges();
                return;
            }
        }
    }
}
