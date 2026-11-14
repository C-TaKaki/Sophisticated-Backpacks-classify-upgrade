package com.huanj.sbaddon.client.layout;

import net.minecraft.Util;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 分类分组的展开/收起状态 + 箭头的过渡进度。
 * <p>
 * 键用分组 id（模组命名空间 / 过滤器名 / 兜底组），客户端同一时刻只有一个背包界面，
 * 所以静态就够；关掉背包再打开会保持上次的收起状态，这点和大多数列表一致。
 * 渲染层每帧调 {@link #expandAmount(String)} 取箭头进度即可，不需要额外的 tick。
 */
public final class ClassifyCollapse {

    private static final long ANIM_MS = 160L;

    /** 已收起的分组 id。 */
    private static final Set<String> COLLAPSED = new HashSet<>();

    /** 正在做动画的分组：{起始进度, 起始时刻}。 */
    private static final Map<String, float[]> ANIM = new HashMap<>();

    private ClassifyCollapse() {
    }

    public static boolean isCollapsed(String groupId) {
        return groupId != null && COLLAPSED.contains(groupId);
    }

    /** 1 = 完全展开（箭头朝下），0 = 完全收起（箭头朝右）。 */
    public static float expandAmount(String groupId) {
        if (groupId == null) {
            return 1F;
        }
        float target = COLLAPSED.contains(groupId) ? 0F : 1F;
        float[] anim = ANIM.get(groupId);
        if (anim == null) {
            return target;
        }
        float t = Mth.clamp((Util.getMillis() - anim[1]) / (float) ANIM_MS, 0F, 1F);
        float inv = 1F - t;
        float eased = 1F - inv * inv * inv;
        float value = anim[0] + (target - anim[0]) * eased;
        if (t >= 1F) {
            ANIM.remove(groupId);
            value = target;
        }
        return value;
    }

    /** 切换某个分组的展开/收起，并从当前箭头位置继续做动画。 */
    public static boolean toggle(String groupId) {
        if (groupId == null) {
            return false;
        }
        float from = expandAmount(groupId);
        boolean nowCollapsed;
        if (COLLAPSED.remove(groupId)) {
            nowCollapsed = false;
        } else {
            COLLAPSED.add(groupId);
            nowCollapsed = true;
        }
        ANIM.put(groupId, new float[]{from, Util.getMillis()});
        return nowCollapsed;
    }

    /** 界面重开时清掉动画缓存（收起状态保留）。 */
    public static void clearAnimation() {
        ANIM.clear();
    }
}
