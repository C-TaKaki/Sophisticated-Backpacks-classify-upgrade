package com.huanj.sbaddon.client.widget;

import com.huanj.sbaddon.SBAddon;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 分类横幅右侧的展开 / 收起按钮（纯绘制，不是控件 —— 横幅每帧重排，不适合挂进控件树）。
 * <p>
 * 外观取自 Create 风格资源包的 slot 按钮贴图：深蓝描边 + 浅蓝高光 + 主体实色 + 底部颗粒带，
 * 中间叠一个会旋转的实心三角箭头：展开 = ▼，收起 = ►。
 * <p>
 * 动画不在这个类里：进度由 {@link com.huanj.sbaddon.client.layout.ClassifyCollapse#expandAmount}
 * 提供，传 0~1 进来就是箭头当前的旋转量，所以状态切换天然是平滑过渡。
 */
public final class ExpandCollapseButton {

    // ---- 贴图引用：改这里就等于换皮 ----------------------------------------
    /** 底图。路径 = assets/sbaddon/textures/gui/widget/toggle_button.png。 */
    private static final ResourceLocation TEX_NORMAL = tex("toggle_button");
    /** 悬停态。想改回原资源包，换成 "toms_storage:..." 对应路径即可。 */
    private static final ResourceLocation TEX_HOVERED = tex("toggle_button_hovered");
    /** 禁用态。 */
    private static final ResourceLocation TEX_DISABLED = tex("toggle_button_disabled");

    /** 贴图原始尺寸，1:1 贴，不缩放，保住 1px 描边。 */
    public static final int SIZE = 16;

    private static final int ARROW_SIZE = 7;
    private static final int ARROW_COLOR = 0xFFEAFDFF;
    private static final int ARROW_COLOR_HOVER = 0xFFFFFFFF;

    private ExpandCollapseButton() {
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "textures/gui/widget/" + name + ".png");
    }

    /**
     * @param expandAmount 1 = 展开（箭头 ▼），0 = 收起（箭头 ►），中间值 = 过渡中
     */
    public static void render(GuiGraphics graphics, int x, int y, float expandAmount,
                              boolean hovered, boolean enabled) {
        ResourceLocation texture = !enabled ? TEX_DISABLED : (hovered ? TEX_HOVERED : TEX_NORMAL);
        graphics.blit(texture, x, y, 0, 0, SIZE, SIZE, SIZE, SIZE);
        drawArrow(graphics, x + SIZE / 2, y + SIZE / 2, Mth.clamp(expandAmount, 0F, 1F), hovered);
    }

    public static boolean isOver(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE;
    }

    /** 箭头：一个朝右的实心三角，随进度顺时针旋转 90°（► → ▼）。 */
    private static void drawArrow(GuiGraphics graphics, int cx, int cy, float expandAmount, boolean hovered) {
        int color = hovered ? ARROW_COLOR_HOVER : ARROW_COLOR;
        int s = ARROW_SIZE;
        double half = (s - 1) / 2.0;
        int x0 = cx - s / 2;
        int y0 = cy - s / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(90F * expandAmount));
        graphics.pose().translate(-cx, -cy, 0);

        for (int row = 0; row < s; row++) {
            // 中间行最宽、上下两端收成 1px，得到一个朝右的实心三角
            int len = (int) Math.round(s * (1.0 - Math.abs(row - half) / (half + 0.5)));
            int w = Mth.clamp(len, 1, s);
            graphics.fill(x0, y0 + row, x0 + w, y0 + row + 1, color);
        }

        graphics.pose().popPose();
    }
}
