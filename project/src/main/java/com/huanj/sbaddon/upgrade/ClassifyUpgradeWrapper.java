package com.huanj.sbaddon.upgrade;

import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * 分类升级的数据载体。配置全部存在**升级物品自身**的 CUSTOM_DATA 里，跟着物品走。
 */
public class ClassifyUpgradeWrapper extends UpgradeWrapperBase<ClassifyUpgradeWrapper, ClassifyUpgradeItem> {

    /** 样本槽物品列表（ItemStackHandler 的序列化结果），由容器负责读写。 */
    public static final String TAG_SAMPLES = "Samples";
    /** 已折叠的组（模组命名空间）。 */
    public static final String TAG_COLLAPSED = "Collapsed";
    /** 当前子页：0 = 列表，1 = 配置。 */
    public static final String TAG_VIEW = "View";

    public static final int VIEW_LIST = 0;
    public static final int VIEW_CONFIG = 1;

    public ClassifyUpgradeWrapper(IStorageWrapper storageWrapper, ItemStack upgradeStack, Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgradeStack, upgradeSaveHandler);
    }

    // ---------------------------------------------------------------- 物品 NBT 读写

    private CompoundTag data() {
        return upgrade.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private void setData(CompoundTag tag) {
        upgrade.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        save();
    }

    public CompoundTag getSamplesTag() {
        CompoundTag data = data();
        return data.contains(TAG_SAMPLES) ? data.getCompound(TAG_SAMPLES) : new CompoundTag();
    }

    public void setSamplesTag(CompoundTag samplesTag) {
        CompoundTag data = data();
        data.put(TAG_SAMPLES, samplesTag);
        setData(data);
    }

    public int getView() {
        return data().getInt(TAG_VIEW);
    }

    public void setView(int view) {
        CompoundTag data = data();
        data.putInt(TAG_VIEW, view);
        setData(data);
    }

    public boolean isCollapsed(String namespace) {
        CompoundTag data = data();
        if (!data.contains(TAG_COLLAPSED)) {
            return false;
        }
        CompoundTag collapsed = data.getCompound(TAG_COLLAPSED);
        return collapsed.getBoolean(namespace);
    }

    public void setCollapsed(String namespace, boolean collapsed) {
        CompoundTag data = data();
        CompoundTag collapsedTag = data.contains(TAG_COLLAPSED) ? data.getCompound(TAG_COLLAPSED) : new CompoundTag();
        collapsedTag.putBoolean(namespace, collapsed);
        data.put(TAG_COLLAPSED, collapsedTag);
        setData(data);
    }

    // ---------------------------------------------------------------- 白名单派生

    /**
     * 从样本槽按槽位顺序推导白名单：每个槽位的物品取命名空间，去重保序。
     * 这个顺序就是标签组的排列顺序（决策 #5）。
     * <p>
     * 属性过滤器样本会被跳过 —— 它自带谓词，属于独立的分类来源，
     * 不是"create 模组的一个代表物品"（否则会凭空多出一个 create 分类）。
     */
    public static List<String> modWhitelist(List<ItemStack> sampleStacks) {
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        for (ItemStack stack : sampleStacks) {
            if (stack.isEmpty() || CreateAttributeFilterCompat.isAttributeFilter(stack)) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            namespaces.add(key.getNamespace());
        }
        return new ArrayList<>(namespaces);
    }
}
