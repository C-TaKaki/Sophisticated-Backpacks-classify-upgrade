package com.huanj.sbaddon.client;

import com.huanj.sbaddon.client.widget.ExpandCollapseButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 展开/收起按钮的演示屏：按钮 + 一块跟着进度横向展开的面板。
 * <p>
 * 只看控件本身 → {@link ExpandCollapseButton}；这个屏幕只是把 {@code getProgress()}
 * 接到面板宽度和内容透明度上，证明动画平滑且可复用。
 * 不需要演示屏的话，整个类删掉，同时删掉 {@link ClientKeybinds} 里的按键即可。
 */
public class ExpandCollapseDemoScreen extends Screen {

    private static final int BTN_SIZE = 18;
    private static final int GAP = 4;
    private static final int PANEL_W = 116;
    private static final int PANEL_H = 46;

    /** 外框 / 内高光 / 主体 / 抖动 / 深底，取自按钮贴图的同一套色。 */
    private static final int C_FRAME = 0xFF333C58;
    private static final int C_HILITE = 0xFFC1D8FF;
    private static final int C_BODY = 0xFF697FC3;
    private static final int C_DITHER = 0xFF536AB2;
    private static final int C_TEXT = 0xFFEAFDFF;

    private ExpandCollapseButton toggleButton;

    public ExpandCollapseDemoScreen() {
        super(Component.translatable("screen.sbaddon.toggle_demo.title"));
    }

    @Override
    protected void init() {
        int totalW = BTN_SIZE + GAP + PANEL_W;
        int bx = this.width / 2 - totalW / 2;
        int by = this.height / 2 - PANEL_H / 2;
        this.toggleButton = new ExpandCollapseButton(bx, by + (PANEL_H - BTN_SIZE) / 2, BTN_SIZE, BTN_SIZE);
        this.toggleButton.setExpanded(false, false);
        this.addRenderableWidget(this.toggleButton);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不用原版菜单背景（会把像素画糊掉），直接压一层半透明深色
        graphics.fill(0, 0, this.width, this.height, 0xCC0B1220);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick); // 背景 + 按钮
        drawPanel(graphics);
        drawHud(graphics);
    }

    /** 面板：宽度 = 进度 × 全宽，内部内容用 scissor 裁掉溢出部分。 */
    private void drawPanel(GuiGraphics graphics) {
        float progress = this.toggleButton.getProgress();
        int px = this.toggleButton.getX() + BTN_SIZE + GAP;
        int py = this.height / 2 - PANEL_H / 2;
        int pw = Math.round((PANEL_W + 2) * progress);
        if (pw <= 1) {
            return;
        }

        // 外框：1px 深蓝描边 + 1px 内高光 + 主体（和贴图按钮同一套像素语言）
        graphics.fill(px, py, px + pw, py + PANEL_H, C_FRAME);
        if (pw > 2) {
            graphics.fill(px + 1, py + 1, px + pw - 1, py + PANEL_H - 1, C_HILITE);
        }
        if (pw > 4) {
            graphics.fill(px + 2, py + 2, px + pw - 2, py + PANEL_H - 2, C_BODY);
            // 底部三分之一做棋盘抖动，向贴图的颗粒带靠
            int ditherTop = py + PANEL_H - 14;
            for (int y = ditherTop; y < py + PANEL_H - 2; y++) {
                for (int x = px + 2 + ((y - ditherTop) % 2); x < px + pw - 2; x += 2) {
                    graphics.fill(x, y, x + 1, y + 1, C_DITHER);
                }
            }
        }

        // 内容：跟随进度淡入 + 右移，超出面板宽度就裁掉
        int alpha = (int) (Mth.clamp(progress, 0F, 1F) * 255F);
        if (alpha <= 4) {
            return;
        }
        int textColor = (alpha << 24) | (C_TEXT & 0xFFFFFF);
        int slide = Math.round((1F - progress) * 8F);

        graphics.enableScissor(px + 2, py + 2, px + Math.max(pw - 2, px + 3), py + PANEL_H - 2);
        graphics.drawString(this.font, Component.translatable("screen.sbaddon.toggle_demo.panel_title"),
                px + 6 + slide, py + 6, textColor, false);
        graphics.drawString(this.font, Component.translatable("screen.sbaddon.toggle_demo.line1"),
                px + 6 + slide, py + 18, textColor, false);
        graphics.drawString(this.font, Component.translatable("screen.sbaddon.toggle_demo.line2"),
                px + 6 + slide, py + 29, textColor, false);
        graphics.disableScissor();
    }

    private void drawHud(GuiGraphics graphics) {
        Component title = this.title;
        graphics.drawCenteredString(this.font, title, this.width / 2, this.height / 2 - PANEL_H / 2 - 26, 0xFFEAFDFF);
        Component state = Component.translatable(this.toggleButton.isExpanded()
                ? "screen.sbaddon.toggle_demo.state_expanded"
                : "screen.sbaddon.toggle_demo.state_collapsed");
        graphics.drawCenteredString(this.font, state, this.width / 2, this.height / 2 + PANEL_H / 2 + 10, 0xFF9EB8FF);
        graphics.drawCenteredString(this.font, Component.translatable("screen.sbaddon.toggle_demo.hint"),
                this.width / 2, this.height / 2 + PANEL_H / 2 + 22, 0xFF7C9DFB);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
