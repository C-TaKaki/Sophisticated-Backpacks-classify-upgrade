package com.huanj.sbaddon.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类横幅的底：纯代码绘制的像素画，不引用任何贴图。
 * <p>
 * 原图 25×25，结构（自上而下）：
 * <ol>
 *   <li>外描边 {@code #333C58} 一圈，顶部内侧一条 {@code #EAFDFF} 高光、底部内侧 {@code #C1D8FF} 次高光；</li>
 *   <li>主体 {@code #9EB8FF}；</li>
 *   <li>中部是**有序抖动的颗粒带**：{@code #7C9DFB} 的 1px 方点按棋盘格排布，
 *       越往下越密，最后连成 {@code #7C9DFB} 的实色块 —— 观感上就是由亮到暗的过渡，不是一条硬边。</li>
 * </ol>
 * 绘制策略（保住颗粒，不把它拉成条）：
 * <ul>
 *   <li><b>横向</b>：左 3 列、右 10 列 1:1（右上折角装饰在右边 10 列内），中间 12 列原样**平铺**；
 *       抖动本身是 1px 棋盘格，平铺后颗粒仍是 1px 方点，不会被拉伸成横杠。</li>
 *   <li><b>纵向</b>：优先丢掉纯色空行（第 3~9 行那片空白主体、第 11 行的空行），
 *       把高度让出来，颗粒带和上下描边保持 1:1 —— 渐变过渡一行不丢。</li>
 * </ul>
 */
public final class BannerBackground {

    /** 原图边长。 */
    private static final int SIZE = 25;

    /** 1:1 不参与平铺的边距。 */
    private static final int LEFT_CAP = 3;
    private static final int RIGHT_CAP = 10;
    private static final int MIDDLE_WIDTH = SIZE - LEFT_CAP - RIGHT_CAP;

    /**
     * 可牺牲的行，按优先级从高到低：纯色空行（3~9 是同一片空白主体，11 行夹在颗粒带中间也是空行）。
     * 高度不够时先丢这些，颗粒带的 13 行留到最后。
     */
    private static final int[] REMOVABLE_ROWS = {6, 4, 8, 11, 3, 5, 7, 9};

    /** 0 透明 / 1 深蓝描边 / 2 顶部高光 / 3 底部次高光 / 4 主体 / 5 抖动颗粒。 */
    private static final int[] PALETTE = {
            0x00000000,
            0xFF333C58,
            0xFFEAFDFF,
            0xFFC1D8FF,
            0xFF9EB8FF,
            0xFF7C9DFB
    };

    private static final String[] PIXELS = {
            "0000000000000001111111110",
            "1111111111111111222222210",
            "1322222222222222444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244444444444444444444210",
            "1244445454545454545454210",
            "1244444444444444444444210",
            "1254545454545454545454210",
            "1244444444454545454545210",
            "1254545454545454545455210",
            "1245454545454545454545210",
            "1254545455555555555555521",
            "0125454545454545455555521",
            "0125555555555555555555521",
            "0125455555555555555555521",
            "0125555555555555555555521",
            "0125555555555555555555521",
            "0125555555555555552222231",
            "0132222222222222221111111",
            "0111111111111111110000000"
    };

    private BannerBackground() {
    }

    public static void render(GuiGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int[] rows = buildRowPlan(height);
        for (int dy = 0; dy < height; dy++) {
            int sourceRow = rows[dy];
            int runStart = 0;
            int current = -1;
            for (int dx = 0; dx <= width; dx++) {
                int color = dx == width ? -1 : colorAt(sourceRow, mapColumn(dx, width));
                if (color == current) {
                    continue;
                }
                if (current > 0 && dx > runStart) {
                    graphics.fill(x + runStart, y + dy, x + dx, y + dy + 1, PALETTE[current]);
                }
                current = color;
                runStart = dx;
            }
        }
    }

    /** 目标列 → 原图列：两端 1:1，中间 12 列循环平铺。 */
    private static int mapColumn(int dest, int width) {
        if (dest < LEFT_CAP) {
            return Math.min(dest, SIZE - 1);
        }
        int trailingStart = width - RIGHT_CAP;
        if (dest >= trailingStart) {
            return Math.min(SIZE - 1, SIZE - RIGHT_CAP + (dest - trailingStart));
        }
        return LEFT_CAP + (dest - LEFT_CAP) % MIDDLE_WIDTH;
    }

    /** 目标行 → 原图行：先把可牺牲的空行去掉，再按目标高度补回或继续裁剪。 */
    private static int[] buildRowPlan(int height) {
        boolean[] sacrificial = new boolean[SIZE];
        for (int row : REMOVABLE_ROWS) {
            sacrificial[row] = true;
        }
        List<Integer> plan = new ArrayList<>(SIZE);
        for (int row = 0; row < SIZE; row++) {
            if (!sacrificial[row]) {
                plan.add(row);
            }
        }

        int extra = height - plan.size();
        if (extra > 0) {
            // 高度富余：按优先级把空行放回去
            List<Integer> restored = new ArrayList<>(extra);
            for (int i = 0; i < REMOVABLE_ROWS.length && i < extra; i++) {
                restored.add(REMOVABLE_ROWS[i]);
            }
            plan.addAll(restored);
            plan.sort(Integer::compareTo);
        }
        if (plan.size() != height) {
            // 极少走的分支：高度和「去掉空行后的 17 行」差太多时，按比例均匀取样
            List<Integer> sampled = new ArrayList<>(height);
            for (int i = 0; i < height; i++) {
                int index = (int) ((long) i * plan.size() / height);
                sampled.add(plan.get(Math.min(plan.size() - 1, index)));
            }
            plan = sampled;
        }
        return plan.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int colorAt(int y, int x) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
            return 0;
        }
        return PIXELS[y].charAt(x) - '0';
    }
}
