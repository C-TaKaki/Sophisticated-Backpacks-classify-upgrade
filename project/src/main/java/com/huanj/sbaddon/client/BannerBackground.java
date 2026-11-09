package com.huanj.sbaddon.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 分类横幅的底：纯代码绘制的像素画，不引用任何贴图。
 * <p>
 * 图案按原图 25×25 逐像素登记在 {@link #PIXELS} 里，绘制时：
 * <ul>
 *   <li>左右两侧的「角」原样 1:1 保留（左 3 列、右 10 列 —— 右上角那块折角装饰在右边 10 列内）；</li>
 *   <li>中间区域沿水平方向拉伸填满横幅宽度；</li>
 *   <li>纵向同理：上下各 3 行 1:1，中间压缩到横幅高度。</li>
 * </ul>
 * 每行把同色的连续像素合并成一段 {@code fill}，一屏十几条横幅也就几百次填充。
 */
public final class BannerBackground {

    /** 原图边长。 */
    private static final int SIZE = 25;

    /** 固定不拉伸的边距：左侧 3 列（描边+高光）、右侧 10 列（含右上折角）、上下各 3 行。 */
    private static final int LEFT_CAP = 3;
    private static final int RIGHT_CAP = 10;
    private static final int TOP_CAP = 3;
    private static final int BOTTOM_CAP = 3;

    /** 0 透明 / 1 深蓝描边 / 2 顶部高光 / 3 底部次高光 / 4 主体 / 5 斜纹点。 */
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
        for (int dy = 0; dy < height; dy++) {
            int sourceRow = mapIndex(dy, height, TOP_CAP, BOTTOM_CAP);
            int runStart = 0;
            int current = -1;
            for (int dx = 0; dx <= width; dx++) {
                int color = dx == width ? -1 : colorAt(mapIndex(dx, width, LEFT_CAP, RIGHT_CAP), sourceRow);
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

    /**
     * 目标坐标 → 原图坐标：两端各 {@code leadingCap} / {@code trailingCap} 格 1:1，
     * 中间按比例取样（最近邻，不做插值）。
     */
    private static int mapIndex(int dest, int destSize, int leadingCap, int trailingCap) {
        int leading = Math.min(leadingCap, destSize);
        int trailing = Math.min(trailingCap, destSize - leading);
        if (dest < leading) {
            return Math.min(dest, SIZE - 1);
        }
        int trailingStart = destSize - trailing;
        if (dest >= trailingStart) {
            return Math.min(SIZE - 1, SIZE - trailingCap + (dest - trailingStart));
        }
        int destMiddle = Math.max(1, trailingStart - leading);
        int sourceMiddle = Math.max(1, SIZE - leadingCap - trailingCap);
        int offset = (dest - leading) * sourceMiddle / destMiddle;
        return Math.min(SIZE - trailingCap - 1, leadingCap + offset);
    }

    private static int colorAt(int x, int y) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
            return 0;
        }
        char c = PIXELS[y].charAt(x);
        return c - '0';
    }
}
