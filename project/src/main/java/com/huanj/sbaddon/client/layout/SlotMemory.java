package com.huanj.sbaddon.client.layout;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 记住「每个存储槽上一次装的是哪个分类的物品」。
 * <p>
 * 作用只有一个：物品被取走后，那个槽变空了，但布局仍然把它算进原来的分组，
 * 于是组内会**留一个空洞**，后面的物品不会往前补位 —— 玩家连续取物时位置是稳的，
 * 不会因为"东西挤上来"点错。
 * <p>
 * 只有两种情况会让空洞失效：
 * <ul>
 *   <li>玩家点了精妙背包自带的「整理背包」（{@code StorageContainerMenuBase.sort()}）——
 *       这时才允许紧凑排列，见 {@code StorageContainerMenuBaseMixin}；</li>
 *   <li>该槽被重新放进物品（正常覆盖记录），或整个组已经没有真实物品了（空洞无处可挂）。</li>
 * </ul>
 * 纯客户端内存态，不参与存档；换背包 / 槽位数量变化时自动清空。
 */
public final class SlotMemory {

    /** 空字符串代表「默认分类」（不在白名单里的物品）。 */
    public static final String FALLBACK = "";

    /** 存储槽索引 → 当初占用它的分组 key。 */
    private static final Map<Integer, String> OWNER = new HashMap<>();

    private static Object lastMenu;
    private static int lastSlotCount = -1;

    private SlotMemory() {
    }

    /** 每帧布局前调用：换背包或槽位数量变了，历史位置和活跃分组都没有意义了。 */
    public static void syncMenu(Object menu, int slotCount) {
        if (menu != lastMenu || slotCount != lastSlotCount) {
            OWNER.clear();
            lastMenu = menu;
            lastSlotCount = slotCount;
        }
    }

    /** 槽里现在装着这个分组的物品。 */
    public static void remember(int slot, String namespace) {
        OWNER.put(slot, namespace);
    }

    /** 槽里是空的，但仍记得它属于哪个分组；没记录过返回 null。 */
    public static String ownerOf(int slot) {
        return OWNER.get(slot);
    }

    /** 忘掉单个槽（例如它所在的组已经没有真实物品，空洞不该继续占位）。 */
    public static void forget(int slot) {
        OWNER.remove(slot);
    }

    /** 整理背包：所有历史空位一起失效，恢复紧凑排列。 */
    public static void forgetAll() {
        OWNER.clear();
    }
}
