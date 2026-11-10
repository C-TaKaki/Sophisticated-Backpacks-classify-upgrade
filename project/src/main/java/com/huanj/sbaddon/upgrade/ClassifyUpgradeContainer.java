package com.huanj.sbaddon.upgrade;

import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分类升级的容器。
 * <p>
 * 样本槽是真 Slot（挂在菜单上），所以客户端能直接看到内容，不需要额外发包。
 * 槽位坐标在构造时随便给，页签打开时由 {@code ClassifyTab#moveSlotsToTab()} 摆到面板里。
 */
public class ClassifyUpgradeContainer extends UpgradeContainerBase<ClassifyUpgradeWrapper, ClassifyUpgradeContainer> {

    /**
     * 样本槽容量。列表本身按内容增长，这里只是上限：
     * 槽位必须在菜单构造时一次性建好（客户端/服务端索引要一致），所以给一个足够大的池子。
     */
    public static final int SAMPLE_COUNT = 64;

    private static final String TAG_VIEW = "View";
    private static final String TAG_ORDER = "Order";
    private static final String TAG_AUTO = "AutoClassify";

    private final SampleHandler samples;

    public ClassifyUpgradeContainer(Player player, int containerId, ClassifyUpgradeWrapper wrapper,
                                    UpgradeContainerType<ClassifyUpgradeWrapper, ClassifyUpgradeContainer> type) {
        super(player, containerId, wrapper, type);

        HolderLookup.Provider registries = player.level().registryAccess();
        this.samples = new SampleHandler(SAMPLE_COUNT, this::persistSamples);
        CompoundTag savedSamples = wrapper.getSamplesTag();
        if (!savedSamples.isEmpty()) {
            // 空 tag 不反序列化：ItemStackHandler 会按 tag 里的 Size 重设容量
            this.samples.deserializeNBT(registries, savedSamples);
        }

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            // 初始位置放在屏幕外，页签真正打开时才摆进来
            slots.add(new SampleSlot(samples, i, -10000, -10000));
        }
    }

    private void persistSamples() {
        if (player.level().isClientSide()) {
            return;
        }
        upgradeWrapper.setSamplesTag(samples.serializeNBT(player.level().registryAccess()));
    }

    public ItemStackHandler getSamples() {
        return samples;
    }

    public List<ItemStack> getSampleStacks() {
        List<ItemStack> stacks = new ArrayList<>(samples.getSlots());
        for (int i = 0; i < samples.getSlots(); i++) {
            stacks.add(samples.getStackInSlot(i));
        }
        return stacks;
    }

    /**
     * 按给定顺序重排样本槽：{@code slotOrder[i]} 表示新第 i 行取原第 slotOrder[i] 个槽位的物品。
     * 重排后顺序紧凑（前面填满，后面清空），顺带消掉删除留下的空档。
     */
    public void applyOrder(int[] slotOrder) {
        List<ItemStack> old = new ArrayList<>();
        for (int i = 0; i < samples.getSlots(); i++) {
            old.add(samples.getStackInSlot(i));
        }
        List<ItemStack> reordered = new ArrayList<>();
        for (int oldIndex : slotOrder) {
            reordered.add(oldIndex >= 0 && oldIndex < old.size() ? old.get(oldIndex) : ItemStack.EMPTY);
        }
        for (int i = 0; i < samples.getSlots(); i++) {
            samples.setStackInSlot(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < reordered.size() && i < samples.getSlots(); i++) {
            samples.setStackInSlot(i, reordered.get(i));
        }
    }

    public List<String> getModWhitelist() {
        return ClassifyUpgradeWrapper.modWhitelist(getSampleStacks());
    }

    /** 升级是否启用（升级槽左侧的那个开关）。关掉时背包网格恢复原版排版，不做分类。 */
    public boolean isUpgradeEnabled() {
        return upgradeWrapper.isEnabled();
    }

    public int getView() {
        return upgradeWrapper.getView();
    }

    public void setView(int view) {
        upgradeWrapper.setView(view);
    }

    public boolean isCollapsed(String namespace) {
        return upgradeWrapper.isCollapsed(namespace);
    }

    public void toggleCollapsed(String namespace) {
        upgradeWrapper.setCollapsed(namespace, !upgradeWrapper.isCollapsed(namespace));
    }

    @Override
    public void handlePacket(CompoundTag tag) {
        if (tag.contains(TAG_VIEW)) {
            upgradeWrapper.setView(tag.getInt(TAG_VIEW));
        }
        if (tag.contains(TAG_ORDER)) {
            applyOrder(tag.getIntArray(TAG_ORDER));
        }
        if (tag.contains(TAG_AUTO)) {
            autoClassify();
        }
    }

    /**
     * 一键分类：扫描当前打开的背包，把每个模组的一个代表物品填进空的样本槽。
     * <p>
     * 只在服务端执行 —— 样本槽是真 Slot，服务端改完内容会随菜单自动同步回客户端。
     * 已经存在的模组跳过，所以可以反复点，不会产生重复分类。
     */
    private void autoClassify() {
        if (player.level().isClientSide()) {
            return;
        }
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }

        // 已有分类（含属性过滤器）跳过，避免把过滤器物品的命名空间当成已存在的模组
        Set<String> known = new LinkedHashSet<>(
                ClassifyUpgradeWrapper.modWhitelist(getSampleStacks()));

        List<ItemStack> picks = new ArrayList<>();
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
            // 已有分类跳过；同一模组的第二个物品也跳过（只取第一个当代表）
            if (known.contains(namespace) || !known.add(namespace)) {
                continue;
            }
            picks.add(stack.copyWithCount(1));
        }

        int slot = 0;
        for (ItemStack pick : picks) {
            while (slot < samples.getSlots() && !samples.getStackInSlot(slot).isEmpty()) {
                slot++;
            }
            if (slot >= samples.getSlots()) {
                break;
            }
            samples.setStackInSlot(slot, pick);
            slot++;
        }
    }

    public static CompoundTag viewTag(int view) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_VIEW, view);
        return tag;
    }

    public static CompoundTag reorderTag(int[] slotOrder) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(TAG_ORDER, slotOrder);
        return tag;
    }

    /** 「一键分类」：请求服务端扫描背包并补全分类。 */
    public static CompoundTag autoClassifyTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_AUTO, true);
        return tag;
    }

    /**
     * 样本槽。
     * <p>
     * 实现 {@link IFilterSlot} 是关键：SB 的 {@code StorageContainerMenuBase.clicked} 对
     * 「升级设置区 + IFilterSlot」的槽位走幽灵语义 —— 复制光标物品 1 个放进去，
     * <b>不扣玩家手上的数量</b>。没有这个接口，点一下就会真消耗一个物品。
     * <p>
     * {@code mayPickup} 恒为 false：槽里的东西只是"图标样本"，一旦允许拿取就等于凭空刷物品。
     * 删条目走页签行尾的删除按钮（直接改数据并同步），不从槽里取。
     */
    public static class SampleSlot extends SlotItemHandler implements IFilterSlot {
        private final ItemStackHandler handler;

        public SampleSlot(ItemStackHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
            this.handler = handler;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        /**
         * 放入规则（SB 的幽灵放入会被这里挡下，物品仍留在玩家手上）：
         * <ul>
         *   <li>同一个模组只允许一条分类；</li>
         *   <li>属性过滤器：只按「同一份配置」去重（不同属性组合是不同分类），
         *       且不参与命名空间去重 —— 它是独立分类来源，不是 create 模组的代表物品；</li>
         *   <li>空的 / 黑名单模式的属性过滤器直接拒绝：前者永远匹配不到东西，
         *       后者会吞掉后面所有分类。</li>
         * </ul>
         */
        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            boolean attributeFilter = CreateAttributeFilterCompat.isAttributeFilter(stack);
            if (attributeFilter) {
                int count = CreateAttributeFilterCompat.attributeCount(stack);
                if (count <= 0) {
                    return false;
                }
                if ("blacklist".equals(CreateAttributeFilterCompat.mode(stack))) {
                    return false;
                }
            }
            String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
            for (int i = 0; i < handler.getSlots(); i++) {
                if (i == getSlotIndex()) {
                    continue;
                }
                ItemStack existing = handler.getStackInSlot(i);
                if (existing.isEmpty()) {
                    continue;
                }
                boolean existingFilter = CreateAttributeFilterCompat.isAttributeFilter(existing);
                if (attributeFilter) {
                    // 同一份过滤器配置只允许一条
                    if (existingFilter && ItemStack.isSameItemSameComponents(existing, stack)) {
                        return false;
                    }
                    continue;
                }
                // 过滤器不占模组分类位
                if (existingFilter) {
                    continue;
                }
                if (BuiltInRegistries.ITEM.getKey(existing.getItem()).getNamespace().equals(namespace)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** 任何一次槽位变更都写回升级物品 NBT。堆叠上限 1，避免样本被合并。 */
    public static class SampleHandler extends ItemStackHandler {
        private final Runnable onChange;

        public SampleHandler(int size, Runnable onChange) {
            super(size);
            this.onChange = onChange;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            onChange.run();
        }
    }
}
