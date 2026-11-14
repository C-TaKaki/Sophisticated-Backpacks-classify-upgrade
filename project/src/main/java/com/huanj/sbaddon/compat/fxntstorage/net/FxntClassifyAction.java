package com.huanj.sbaddon.compat.fxntstorage.net;

import com.huanj.sbaddon.SBAddon;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
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
public record FxntClassifyAction(int action, int index, int dir, String groupId, String name,
                                 ItemStack stack)
        implements CustomPacketPayload {

    public static final int ACTION_AUTO = 0;
    public static final int ACTION_REMOVE = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_RENAME = 3;
    /** 拖拽排序：index = 源行，dir = 目标行。 */
    public static final int ACTION_REORDER = 4;
    /**
     * 拖入物品新增分类。
     * <p>
     * 必须连整只 {@link ItemStack} 一起发：只发注册名的话 Create 属性过滤器会变成一只**空过滤器**
     * （属性条目全在组件里），加进去也解析不出谓词，分类等于没加。
     */
    public static final int ACTION_ADD = 5;

    public static final Type<FxntClassifyAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "fxnt_classify_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FxntClassifyAction> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::action,
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::index,
                    ByteBufCodecs.VAR_INT, FxntClassifyAction::dir,
                    ByteBufCodecs.STRING_UTF8, FxntClassifyAction::groupId,
                    ByteBufCodecs.STRING_UTF8, FxntClassifyAction::name,
                    ItemStack.OPTIONAL_STREAM_CODEC, FxntClassifyAction::stack,
                    FxntClassifyAction::new);

    public static FxntClassifyAction auto() {
        return new FxntClassifyAction(ACTION_AUTO, 0, 0, "", "", ItemStack.EMPTY);
    }

    public static FxntClassifyAction remove(int index) {
        return new FxntClassifyAction(ACTION_REMOVE, index, 0, "", "", ItemStack.EMPTY);
    }

    public static FxntClassifyAction move(int index, int dir) {
        return new FxntClassifyAction(ACTION_MOVE, index, dir, "", "", ItemStack.EMPTY);
    }

    public static FxntClassifyAction reorder(int from, int to) {
        return new FxntClassifyAction(ACTION_REORDER, from, to, "", "", ItemStack.EMPTY);
    }

    /** 新增分类：带整只样本（属性过滤器的属性条目在组件里，只发名字会丢）。 */
    public static FxntClassifyAction add(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return new FxntClassifyAction(ACTION_ADD, 0, 0, "", "", ItemStack.EMPTY);
        }
        return new FxntClassifyAction(ACTION_ADD, 0, 0, "", "", sample.copyWithCount(1));
    }

    public static FxntClassifyAction rename(String groupId, String name) {
        return new FxntClassifyAction(ACTION_RENAME, 0, 0, groupId == null ? "" : groupId,
                name == null ? "" : name, ItemStack.EMPTY);
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
            // 注意：这里是升级物品，不是包里的样本。record 里也有个 stack() 组件，
            // 之前把局部变量也叫 stack，ACTION_ADD 里拿到的是升级物品本身，
            // 于是"拖入过滤器"加进去的是本模组的升级物品 —— 列表里冒出一行
            // 「Sophisticated Backpacks Addon」，过滤器永远加不上。
            ItemStack upgradeStack = slot.getItem();
            HolderLookup.Provider registries = player.level().registryAccess();
            List<ItemStack> samples = new ArrayList<>(
                    FxntClassifyData.snapshot(upgradeStack, registries).samples());

            switch (action) {
                case ACTION_AUTO -> samples = FxntClassifyData.autoClassify(samples,
                        FxntClassifyMenu.storageStacks(menu));
                case ACTION_REMOVE -> {
                    if (index < 0 || index >= samples.size()) {
                        return;
                    }
                    ItemStack removed = samples.remove(index);
                    // 属性过滤器是消耗品：加进来时从玩家手上扣了一个，删掉这条分类要还回去
                    if (CreateAttributeFilterCompat.isAttributeFilter(removed)) {
                        refund(player, removed);
                    }
                }
                case ACTION_MOVE -> {
                    int target = index + dir;
                    if (index < 0 || index >= samples.size() || target < 0 || target >= samples.size()) {
                        return;
                    }
                    ItemStack moved = samples.remove(index);
                    samples.add(target, moved);
                }
                case ACTION_ADD -> {
                    ItemStack sample = this.stack();
                    if (sample.isEmpty()) {
                        return;
                    }
                    // 手上必须真有这只物品，否则就是伪造包，直接拒绝（否则等于凭空造过滤器）
                    ItemStack carried = menu.getCarried();
                    if (carried.isEmpty()
                            || !FxntClassifyData.groupKey(carried).equals(FxntClassifyData.groupKey(sample))) {
                        return;
                    }
                    int before = samples.size();
                    samples = FxntClassifyData.addSample(samples, sample);
                    if (samples.size() > before) {
                        // 真加了一条新分类：把这一个从光标上扣掉（属性过滤器有配置，不能白拿）
                        carried.shrink(1);
                        menu.setCarried(carried);
                    }
                }
                case ACTION_REORDER -> {
                    if (index < 0 || index >= samples.size() || dir < 0 || dir >= samples.size()
                            || index == dir) {
                        return;
                    }
                    ItemStack moved = samples.remove(index);
                    samples.add(dir, moved);
                }
                case ACTION_RENAME -> {
                    FxntClassifyData.setName(upgradeStack, groupId, name);
                    slot.setChanged();
                    return;
                }
                default -> {
                    return;
                }
            }

            FxntClassifyData.writeSamples(upgradeStack, samples, registries);
            // 升级槽的 setChanged 会落到 BackpackContainer#setDataChanged()，
            // 背包内容随 CONTAINER 组件落盘，槽位差异再自动同步给客户端
            slot.setChanged();
        });
    }

    /** 把物品还给玩家：先进背包，塞不下就掉在脚下。 */
    private static void refund(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copyWithCount(1);
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
    }
}
