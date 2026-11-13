package com.huanj.sbaddon.client.widget;

import com.huanj.sbaddon.SBAddon;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

/**
 * 展开 / 收起切换按钮。
 * <p>
 * 外观：Create 风格资源包的 slot 按钮贴图（深蓝描边 + 浅蓝高光 + 主体实色），
 * 中间叠一个由「▶」平滑转到「▼」的箭头。收起 = ▶，展开 = ▼。
 * <p>
 * 动画：状态切换时 {@link #getProgress()} 在 {@link #ANIM_MS} 毫秒内从 0 缓动到 1（反之为 1→0），
 * 缓动曲线 easeOutCubic。文字/面板等需要跟着动的元素读 {@code getProgress()} 自行插值即可，
 * 不依赖帧率（用 {@link Util#getMillis()} 计时），也不依赖 partialTick。
 */
public class ExpandCollapseButton extends AbstractWidget {

    // ---- 贴图引用：改这里就等于换皮 ----------------------------------------
    /** 底图。路径 = assets/sbaddon/textures/gui/widget/toggle_button.png。 */
    private static final ResourceLocation TEX_NORMAL = tex("toggle_button");
    /** 悬停态。想用别的资源包贴图，把命名空间换掉即可，例如 "toms_storage:.../slot_button_hovered"。 */
    private static final ResourceLocation TEX_HOVERED = tex("toggle_button_hovered");
    /** 禁用态。 */
    private static final ResourceLocation TEX_DISABLED = tex("toggle_button_disabled");

    /** 贴图原始尺寸，按 1:1 贴，不缩放，保住 1px 描边。 */
    private static final int TEX_W = 16;
    private static final int TEX_H = 16;

    /** 箭头三角边长（像素）。 */
    private static final int ARROW_SIZE = 7;
    private static final int ARROW_COLOR = 0xFFEAFDFF;
    private static final int ARROW_COLOR_HOVER = 0xFFFFFFFF;

    /** 一次展开/收起的时长（毫秒）。 */
    private static final long ANIM_MS = 180L;

    /** true = 展开，false = 收起。 */
    private boolean expanded;
    /** 0 = 完全收起，1 = 完全展开；动画期间是中间值。 */
    private float progress;
    /** 本次动画的起始值 / 起始时刻。 */
    private float animFrom;
    private long animStart;

    /** 状态变化回调，参数是新状态。 */
    private Consumer<Boolean> onToggle = value -> {
    };

    public ExpandCollapseButton(int x, int y) {
        this(x, y, 18, 18);
    }

    public ExpandCollapseButton(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.sbaddon.toggle.expand"));
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(SBAddon.MOD_ID, "textures/gui/widget/" + name + ".png");
    }

    // ---- 对外接口 -----------------------------------------------------------

    public boolean isExpanded() {
        return expanded;
    }

    /** 展开进度 0~1，供外层面板做同步动画。 */
    public float getProgress() {
        return progress;
    }

    public ExpandCollapseButton onToggle(Consumer<Boolean> listener) {
        this.onToggle = listener == null ? value -> {
        } : listener;
        return this;
    }

    public void toggle() {
        setExpanded(!expanded, true);
    }

    /**
     * @param value   目标状态
     * @param animate false 时立刻到位（例如读存档初始化）
     */
    public void setExpanded(boolean value, boolean animate) {
        if (this.expanded == value) {
            return;
        }
        this.expanded = value;
        this.animFrom = this.progress;
        this.animStart = Util.getMillis();
        if (!animate) {
            this.progress = target();
        }
        setMessage(Component.translatable(expanded ? "gui.sbaddon.toggle.collapse" : "gui.sbaddon.toggle.expand"));
        onToggle.accept(value);
    }

    private float target() {
        return expanded ? 1F : 0F;
    }

    // ---- 渲染 ---------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateAnimation();

        boolean hovered = active
                && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight();

        ResourceLocation texture = !active ? TEX_DISABLED : (hovered ? TEX_HOVERED : TEX_NORMAL);
        // 居中 1:1 贴图：控件可以比 16×16 大，多出来的空间留白，贴图不拉伸
        int x = getX() + (getWidth() - TEX_W) / 2;
        int y = getY() + (getHeight() - TEX_H) / 2;
        graphics.blit(texture, x, y, 0, 0, TEX_W, TEX_H, TEX_W, TEX_H);

        drawArrow(graphics, x + 8, y + 8, hovered);
    }

    /** 箭头：一个朝右的实心三角，随 progress 顺时针旋转 90°（▶ → ▼）。 */
    private void drawArrow(GuiGraphics graphics, int cx, int cy, boolean hovered) {
        int color = hovered ? ARROW_COLOR_HOVER : ARROW_COLOR;
        int s = ARROW_SIZE;
        double half = (s - 1) / 2.0;
        int x0 = cx - s / 2;
        int y0 = cy - s / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(90F * progress));
        graphics.pose().translate(-cx, -cy, 0);

        for (int row = 0; row < s; row++) {
            // 中间行最宽、上下两端收成 1px，得到一个朝右的实心三角
            int len = (int) Math.round(s * (1.0 - Math.abs(row - half) / (half + 0.5)));
            int w = Mth.clamp(len, 1, s);
            graphics.fill(x0, y0 + row, x0 + w, y0 + row + 1, color);
        }

        graphics.pose().popPose();
    }

    private void updateAnimation() {
        float target = target();
        if (progress == target) {
            return;
        }
        float t = Mth.clamp((Util.getMillis() - animStart) / (float) ANIM_MS, 0F, 1F);
        float inv = 1F - t;
        float eased = 1F - inv * inv * inv; // easeOutCubic：起步快、收尾稳
        progress = animFrom + (target - animFrom) * eased;
        if (t >= 1F) {
            progress = target;
        }
    }

    // ---- 交互 ---------------------------------------------------------------

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        if (button == 0) {
            toggle();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
