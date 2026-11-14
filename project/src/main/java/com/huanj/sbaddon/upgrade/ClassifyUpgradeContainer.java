package com.huanj.sbaddon.upgrade;

import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
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
import java.util.Map;
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
    private static final String TAG_RENAME = "Rename";
    /** 删除分类时把样本物品还给玩家（属性过滤器是消耗品，不能凭空没收）。
     *  只传行号：物品本体由服务端自己从样本槽读，避免 ItemStack 过 NBT 时装不进去。 */
    private static final String TAG_REFUND_ROW = "RefundRow";
    private static final String TAG_RENAME_ID = "Id";
    private static final String TAG_RENAME_NAME = "Name";

    /** 分类名长度上限 —— 列表行只有 ~100px，再长也显示不出来。 */
    public static final int MAX_NAME_LENGTH = 32;

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
            slots.add(new SampleSlot(this, samples, i, -10000, -10000));
        }
    }

    /**
     * 从光标上扣掉一个物品（样本槽的幽灵放入不扣数量，属性过滤器要单独收一次）。
     * 只在服务端执行，扣完由菜单同步回客户端。
     */
    boolean chargeFilterFromCursor() {
        if (player.level().isClientSide()) {
            // 客户端只做预测，真正的扣费在服务端；别在客户端改光标，否则两边各扣一次
            return true;
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            // 手上没有可扣的东西 = 这次放入没有来源，不能放行（否则就是白拿一只过滤器）
            return false;
        }
        carried.shrink(1);
        player.containerMenu.setCarried(carried);
        return true;
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

    /** 玩家改过的分类名（分组 key → 名字）。 */
    public Map<String, String> getCustomNames() {
        return upgradeWrapper.getCustomNames();
    }

    /** 本地立即改名（客户端乐观更新 / 服务端落盘），两边都走这里。 */
    public void applyRename(String groupId, String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
        }
        upgradeWrapper.setCustomName(groupId, cleaned);
        // 同时写进样本物品自己身上：属性过滤器的分组 id 由组件字符串算 hash 得到，
        // 重启 / NBT 往返后可能对不上，只有「名字跟着样本走」才不会丢
        syncNameIntoSample(groupId, cleaned);
    }

    /**
     * 把名字写进分组 id 匹配的那条样本。
     * <p>
     * 用 {@code setStackInSlot} 回写而不是原地改：走 handler 的 onContentsChanged 才会触发
     * {@code persistSamples()}（服务端落盘 / 客户端忽略），和样本本身的保存路径保持一条。
     */
    private void syncNameIntoSample(String groupId, String name) {
        for (int i = 0; i < samples.getSlots(); i++) {
            ItemStack stack = samples.getStackInSlot(i);
            if (stack.isEmpty() || !groupId.equals(ClassifyGroups.groupId(stack))) {
                continue;
            }
            if (ClassifyGroups.storeName(stack, name)) {
                samples.setStackInSlot(i, stack);
            }
            return;
        }
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
        // 先归还再重排：归还读的是"第几行"，必须在样本槽被重排之前取
        if (tag.contains(TAG_REFUND_ROW) && !player.level().isClientSide()) {
            refundRow(tag.getInt(TAG_REFUND_ROW));
        }
        if (tag.contains(TAG_ORDER)) {
            applyOrder(tag.getIntArray(TAG_ORDER));
        }
        if (tag.contains(TAG_AUTO)) {
            autoClassify();
        }
        if (tag.contains(TAG_RENAME)) {
            CompoundTag rename = tag.getCompound(TAG_RENAME);
            applyRename(rename.getString(TAG_RENAME_ID), rename.getString(TAG_RENAME_NAME));
            markUpgradeSlotChanged();
        }

    }

    /**
     * 把升级槽标脏，让改动真正落盘。
     * <p>
     * {@code wrapper.save()} 只是把 ItemStack 写回升级物品，背包本体（storage 的 NBT）并不会因此被标记为
     * 已修改 —— 采样槽有真正的 {@link Slot}，SB 的幽灵放入流程会替我们调 {@code setChanged()}，
     * 所以样本能存住；改名没有经过任何槽位交互，光改组件的话只在内存里生效，
     * 关掉游戏（存档写盘）后就丢了。这里补上这一步。
     */
    private void markUpgradeSlotChanged() {
        if (player.level().isClientSide()) {
            return;
        }
        if (!(player.containerMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        for (Slot slot : menu.slots) {
            if (slot.getItem().is(com.huanj.sbaddon.registry.SBItems.CLASSIFY_UPGRADE.get())) {
                slot.setChanged();
                menu.broadcastChanges();
                return;
            }
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

    /** 重排 + 顺带归还第 {@code refundRow} 行的样本物品（删除属性过滤器分类时用）。 */
    public static CompoundTag reorderTag(int[] slotOrder, int refundRow) {
        CompoundTag tag = reorderTag(slotOrder);
        if (refundRow >= 0) {
            tag.putInt(TAG_REFUND_ROW, refundRow);
        }
        return tag;
    }

    /**
     * 把第 row 条分类对应的样本物品还给玩家。
     * <p>
     * 只还属性过滤器：它加进来时被真正扣过费；普通模组样本只是图标，从来没有扣过，不能凭空发。
     */
    private void refundRow(int row) {
        List<Integer> used = new java.util.ArrayList<>();
        for (int i = 0; i < samples.getSlots(); i++) {
            if (!samples.getStackInSlot(i).isEmpty()) {
                used.add(i);
            }
        }
        if (row < 0 || row >= used.size()) {
            return;
        }
        ItemStack removed = samples.getStackInSlot(used.get(row));
        if (!CreateAttributeFilterCompat.isAttributeFilter(removed)) {
            return;
        }
        // 只有真付过费的才退：老存档 / 自动分类塞进来、以及漏洞期间白拿的样本都不带标记，
        // 否则删一次分类就等于凭空印一只过滤器
        if (!ClassifyGroups.isPaid(removed)) {
            return;
        }
        ItemStack refund = removed.copyWithCount(1);
        if (!player.getInventory().add(refund)) {
            player.drop(refund, false);
        }
    }

    /** 「一键分类」：请求服务端扫描背包并补全分类。 */
    public static CompoundTag autoClassifyTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_AUTO, true);
        return tag;
    }

    /**
     * 重命名分类标签。
     * <p>
     * 名字存在升级物品的 CUSTOM_DATA 里，只能由服务端写；客户端先本地应用一遍保证界面即时反馈，
     * 服务端落盘后随升级物品同步回来。
     */
    public static CompoundTag renameTag(String groupId, String name) {
        CompoundTag payload = new CompoundTag();
        payload.putString(TAG_RENAME_ID, groupId == null ? "" : groupId);
        payload.putString(TAG_RENAME_NAME, name == null ? "" : name);
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_RENAME, payload);
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
        private final ClassifyUpgradeContainer container;
        /** 上一次已经收过费的过滤器配置；同一只重复点不再重复扣。 */
        private String chargedKey;

        public SampleSlot(ClassifyUpgradeContainer container, ItemStackHandler handler,
                          int index, int x, int y) {
            super(handler, index, x, y);
            this.handler = handler;
            this.container = container;
        }

        /**
         * 幽灵放入完成后的回调（{@code StorageContainerMenuBase.clicked} 里 set 完就调它）。
         * <p>
         * 幽灵槽不扣玩家手上的数量，凭这一点属性过滤器就会被白拿 —— 它是带配置的物品，
         * 不能白拿。这里按「槽里换成了另一只过滤器」补扣一次；重复点同一只不会重复扣。
         */
        @Override
        public void onItemSet(int button) {
            ItemStack now = getItem();
            if (now.isEmpty()) {
                // 槽被清空（删分类 / 换成普通样本）：下一次放过滤器必须重新付费。
                // 不清的话，删掉分类拿到退款后，同一份配置再放回来会被当成"已付过"而白拿。
                chargedKey = null;
                return;
            }
            String key = CreateAttributeFilterCompat.isAttributeFilter(now)
                    ? CreateAttributeFilterCompat.key(now)
                    : null;
            if (key == null) {
                chargedKey = null;
                return;
            }
            if (key.equals(chargedKey)) {
                return;
            }
            if (!container.chargeFilterFromCursor()) {
                // 没扣到东西就把槽还原：宁可拒绝这次放入，也不能凭空多出一只过滤器
                set(ItemStack.EMPTY);
                chargedKey = null;
                return;
            }
            chargedKey = key;
            // 在样本上留一个「已付费」标记，删分类时只有带标记的才允许退还
            ClassifyGroups.markPaid(now);
            container.getSamples().setStackInSlot(getSlotIndex(), now);
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
                // 只接受「光标上真的拿着这只过滤器」的放入：QuickMove / setGhostSlot 这类
                // 从别处复制一份过来的路径没有可扣的来源，一律拒绝，堵掉刷物品
                ItemStack carried = container.player.containerMenu.getCarried();
                if (carried.isEmpty() || !ItemStack.isSameItemSameComponents(carried, stack)) {
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
