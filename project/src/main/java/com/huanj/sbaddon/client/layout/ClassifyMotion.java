package com.huanj.sbaddon.client.layout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 分类布局的过渡动画：把「槽位/横幅的瞬时目标位置」变成逐帧逼近的显示位置。
 * <p>
 * 每个背包界面实例各持一份。用法固定三步：
 * <pre>
 * motion.beginFrame();
 * for (placement : result.placements()) {
 *     float[] pos = motion.slot(placement.slotIndex(), placement.x(), placement.y());
 *     slot.x = Math.round(pos[0]);
 *     slot.y = Math.round(pos[1]);
 * }
 * int bannerY = Math.round(motion.bannerY(banner.id(), banner.y()));
 * motion.endFrame();
 * </pre>
 * 本帧没出现的槽位/横幅在 {@link #endFrame()} 里被丢弃 —— 它们下次出现时直接落位，
 * 不会从旧位置"飞"过去（滚动、换组时就该是这样）。
 */
public final class ClassifyMotion {

    /** 每帧向目标靠拢的比例：60fps 下约 5 帧到位，观感是"滑过去"而不是"跳过去"。 */
    private static final float STEP = 0.35F;

    private final Map<Integer, float[]> slots = new HashMap<>();
    private final Map<String, float[]> banners = new HashMap<>();
    private final Set<Integer> seenSlots = new HashSet<>();
    private final Set<String> seenBanners = new HashSet<>();

    public void beginFrame() {
        seenSlots.clear();
        seenBanners.clear();
    }

    /** @return 该槽位当前的显示坐标 {x, y} */
    public float[] slot(int slotIndex, int targetX, int targetY) {
        seenSlots.add(slotIndex);
        float[] pos = slots.get(slotIndex);
        if (pos == null) {
            pos = new float[]{targetX, targetY};
            slots.put(slotIndex, pos);
            return pos;
        }
        pos[0] = approach(pos[0], targetX);
        pos[1] = approach(pos[1], targetY);
        return pos;
    }

    /** @return 该横幅当前的显示 y（x 由列数决定，不参与动画） */
    public float bannerY(String bannerId, int targetY) {
        seenBanners.add(bannerId);
        float[] pos = banners.get(bannerId);
        if (pos == null) {
            pos = new float[]{targetY};
            banners.put(bannerId, pos);
            return targetY;
        }
        pos[0] = approach(pos[0], targetY);
        return pos[0];
    }

    /**
     * 丢掉某个槽位的动画缓存，下次出现时直接落位。
     * <p>
     * 空槽位用它：空槽只是网格背景，跟着一起滑动只会让"一大片格子整体漂移"，
     * 反而看不出物品在移动。物品槽才需要过渡。
     */
    public void drop(int slotIndex) {
        slots.remove(slotIndex);
    }

    public void endFrame() {
        slots.keySet().removeIf(index -> !seenSlots.contains(index));
        banners.keySet().removeIf(id -> !seenBanners.contains(id));
    }

    public void reset() {
        slots.clear();
        banners.clear();
        seenSlots.clear();
        seenBanners.clear();
    }

    private static float approach(float current, float target) {
        float diff = target - current;
        return Math.abs(diff) < 0.5F ? target : current + diff * STEP;
    }
}
