package com.huanj.sbaddon.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类横幅的底：纯代码绘制的像素画，不引用任何贴图。
 * <p>
 * 原图 25×25，结构（自上而下）：
 * <ol>
 *   <li>外描边 {@code #333C58} 一圈，顶部内侧 {@code #EAFDFF} 高光、底部内侧 {@code #C1D8FF} 次高光；</li>
 *   <li>主体 {@code #9EB8FF}；</li>
 *   <li>中部是**有序抖动的颗粒带**：{@code #7C9DFB} 的 1px 方点按棋盘格排布，越往下越密，
 *       最后连成实色块 —— 观感上是由亮到暗的过渡，不是一条硬边。</li>
 * </ol>
 * 绘制策略（保住颗粒，不把它拉成条）：
 * <ul>
 *   <li><b>横向</b>：左 3 列、右 10 列 1:1（右上折角装饰在右边 10 列内），中间 12 列原样平铺；</li>
 *   <li><b>纵向</b>：优先丢掉纯色空行（第 3~9 行那片空白主体、第 11 行的空行），
 *       颗粒带和上下描边保持 1:1，渐变过渡一行不丢。</li>
 * </ul>
 * 性能：图案按「横幅宽高」烘焙成一张 {@link DynamicTexture}，每帧只 blit 一次。
 * 早前逐像素 {@code fill} 的方案每条横幅要几百次填充，开着背包掉帧，已经废弃。
 */
public final class BannerBackground {

    private static final Logger LOGGER = LoggerFactory.getLogger("sbaddon");

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
            "1254545454545454545454210",
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

    private record Baked(ResourceLocation location, DynamicTexture texture) {
    }

    /** width<<32 | height → 烘焙好的贴图。宽度只在界面尺寸变化时改变，所以缓存基本不会膨胀。 */
    private static final Map<Long, Baked> CACHE = new HashMap<>();

    private static boolean disabled;

    private BannerBackground() {
    }

    public static void render(GuiGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ResourceLocation location = disabled ? null : bakedLocation(width, height);
        if (location == null) {
            // 兜底：贴图没建起来（极端情况）时至少给一块主色底，别让文字压在木纹上
            graphics.fill(x, y, x + width, y + height, PALETTE[4]);
            return;
        }
        // 每帧一次 blit：图案已烘焙进贴图（早前逐像素 fill 会掉帧，已废弃）
        graphics.blit(location, x, y, 0f, 0f, width, height, width, height);
    }

    private static ResourceLocation bakedLocation(int width, int height) {
        long key = ((long) width << 32) | height;
        Baked cached = CACHE.get(key);
        if (cached != null && Minecraft.getInstance().getTextureManager()
                .getTexture(cached.location(), null) != null) {
            return cached.location();
        }
        if (cached != null) {
            // 资源重载把注册过的贴图清掉了，先释放旧的再重建
            cached.texture().close();
            CACHE.remove(key);
        }
        try {
            NativeImage image = paint(width, height);
            dumpOnce(image, width, height);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("sbaddon_classify_banner", texture);
            CACHE.put(key, new Baked(location, texture));
            return location;
        } catch (Exception exception) {
            LOGGER.warn("Failed to bake classify banner texture ({}x{})", width, height, exception);
            disabled = true;
            return null;
        }
    }

    private static boolean dumped;

    private static void dumpOnce(NativeImage image, int width, int height) {
        if (dumped) {
            return;
        }
        dumped = true;
        try {
            java.nio.file.Path path = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("sbaddon_banner_debug.png");
            image.writeToFile(path);
            int opaque = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                        opaque++;
                    }
                }
            }
            LOGGER.info("[sbaddon] banner texture dumped to {} ({}x{}, opaque={}/{}, pixel(5,3)={})",
                    path, width, height, opaque, width * height, Integer.toHexString(image.getPixelRGBA(5, 3)));
        } catch (Exception exception) {
            LOGGER.warn("[sbaddon] failed to dump banner texture", exception);
        }
    }

    /** 按图案生成本尺寸的像素图。 */
    private static NativeImage paint(int width, int height) {
        NativeImage image = new NativeImage(width, height, false);
        int[] rows = buildRowPlan(height);
        for (int y = 0; y < height; y++) {
            int sourceRow = rows[y];
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, toNative(PALETTE[colorAt(sourceRow, mapColumn(x, width))]));
            }
        }
        return image;
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

    /** ARGB → NativeImage 的 ABGR 字节序。 */
    private static int toNative(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
