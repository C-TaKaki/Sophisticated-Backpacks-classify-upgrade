package com.huanj.sbaddon.upgrade;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;
import net.p3pp3rf1y.sophisticatedcore.common.gui.SortBy;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类升级的容器。
 * <p>
 * 样本槽是真 Slot（挂在菜单上），所以客户端能直接看到内容，不需要额外发包。
 * 槽位坐标在构造时随便给，页签打开时由 {@code ClassifyTab#moveSlotsToTab()} 摆到面板里。
 */
public class ClassifyUpgradeContainer extends UpgradeContainerBase<ClassifyUpgradeWrapper, ClassifyUpgradeContainer> {

    public static final int SAMPLE_COLUMNS = 5;
    public static final int SAMPLE_ROWS = 3;
    public static final int SAMPLE_COUNT = SAMPLE_COLUMNS * SAMPLE_ROWS;

    private static final String TAG_SORT = "Sort";
    private static final String TAG_VIEW = "View";
    private static final String TAG_ADD_HELD = "AddHeld";
    private static final String TAG_REMOVE_LAST = "RemoveLast";
    private static final String TAG_ORDER = "Order";

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

    /** 「+」：把玩家光标上的物品取 1 个作为新条目。仅服务端执行。 */
    private void addHeldSample() {
        if (player.level().isClientSide()) {
            return;
        }
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        int empty = -1;
        for (int i = 0; i < samples.getSlots(); i++) {
            if (samples.getStackInSlot(i).isEmpty()) {
                empty = i;
                break;
            }
        }
        if (empty < 0) {
            return;
        }
        samples.setStackInSlot(empty, carried.copyWithCount(1));
        carried.shrink(1);
        menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
    }

    /** 「-」：删掉最后一条，物品退回玩家背包（背包满则掉落）。仅服务端执行。 */
    private void removeLastSample() {
        if (player.level().isClientSide()) {
            return;
        }
        for (int i = samples.getSlots() - 1; i >= 0; i--) {
            ItemStack stack = samples.getStackInSlot(i);
            if (!stack.isEmpty()) {
                samples.setStackInSlot(i, ItemStack.EMPTY);
                ItemStack back = stack.copy();
                if (!player.getInventory().add(back)) {
                    player.drop(back, false);
                }
                return;
            }
        }
    }

    public List<String> getModWhitelist() {
        return ClassifyUpgradeWrapper.modWhitelist(getSampleStacks());
    }

    public SortBy getFallbackSort() {
        return upgradeWrapper.getFallbackSort();
    }

    public void setFallbackSort(SortBy sortBy) {
        upgradeWrapper.setFallbackSort(sortBy);
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
        if (tag.contains(TAG_SORT)) {
            upgradeWrapper.setFallbackSort(SortBy.fromName(tag.getString(TAG_SORT)));
        }
        if (tag.contains(TAG_VIEW)) {
            upgradeWrapper.setView(tag.getInt(TAG_VIEW));
        }
        if (tag.getBoolean(TAG_ADD_HELD)) {
            addHeldSample();
        }
        if (tag.getBoolean(TAG_REMOVE_LAST)) {
            removeLastSample();
        }
        if (tag.contains(TAG_ORDER)) {
            applyOrder(tag.getIntArray(TAG_ORDER));
        }
    }

    /** 标记需要发给服务端的状态变更包内容。 */
    public static CompoundTag sortTag(SortBy sortBy) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_SORT, sortBy.getSerializedName());
        return tag;
    }

    public static CompoundTag viewTag(int view) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_VIEW, view);
        return tag;
    }

    public static CompoundTag addHeldTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_ADD_HELD, true);
        return tag;
    }

    public static CompoundTag removeLastTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_REMOVE_LAST, true);
        return tag;
    }

    public static CompoundTag reorderTag(int[] slotOrder) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray(TAG_ORDER, slotOrder);
        return tag;
    }

    /** 样本槽：可以拖入，也可以用空手点出来（防止配错了清不掉）。 */
    public static class SampleSlot extends SlotItemHandler {
        public SampleSlot(ItemStackHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();
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
