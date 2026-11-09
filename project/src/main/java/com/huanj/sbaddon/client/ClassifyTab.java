package com.huanj.sbaddon.client;

import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeSettingsTab;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Dimension;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.common.gui.SortBy;

import java.util.List;

/**
 * 分类配置页：只负责「标记模组」。
 * <p>
 * 把某模组的任意物品拖进样本槽，该模组就加入分类白名单；样本物品同时作为背包网格里
 * 该分类横幅的图标。分类视图本身由 {@code StorageScreenBaseMixin} 渲染，这里不放列表。
 */
public class ClassifyTab extends UpgradeSettingsTab<ClassifyUpgradeContainer> {

    public static final int TAB_WIDTH = 118;
    public static final int TAB_HEIGHT = 132;

    private static final int TITLE_H = 13;
    private static final int PAD = 5;
    private static final int CELL = 18;
    private static final int BAR_H = 11;

    private static final int COLOR_LABEL_DIM = 0xFF6E6E6E;
    private static final int COLOR_BOX_BORDER = 0xFF5A5A5A;
    private static final int COLOR_BOX_FILL = 0xFF8B8B8B;
    private static final int COLOR_BAR_BTN = 0xFF8A8A8A;

    private int sortButtonX;
    private int sortButtonY;
    private int sortButtonW;

    public ClassifyTab(ClassifyUpgradeContainer container, Position position, StorageScreenBase<?> screen) {
        super(container, position, screen,
                Component.translatable("gui.sbaddon.classify.title"),
                Component.translatable("gui.sbaddon.classify.tooltip"));
        this.openTabDimension = new Dimension(TAB_WIDTH, TAB_HEIGHT);
    }

    @Override
    protected void moveSlotsToTab() {
        ClassifyUpgradeContainer container = getContainer();
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();
        List<Slot> slots = container.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            int col = i % ClassifyUpgradeContainer.SAMPLE_COLUMNS;
            int row = i / ClassifyUpgradeContainer.SAMPLE_COLUMNS;
            slot.x = gridX() + col * CELL - guiLeft;
            slot.y = gridY() + row * CELL - guiTop;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        int gx = gridX();
        int gy = gridY();
        int cols = ClassifyUpgradeContainer.SAMPLE_COLUMNS;
        int rows = ClassifyUpgradeContainer.SAMPLE_ROWS;

        // 样本槽底框（槽位本身由界面绘制，这里只画背景）
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = gx + c * CELL;
                int y = gy + r * CELL;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, COLOR_BOX_BORDER);
                graphics.fill(x, y, x + 16, y + 16, COLOR_BOX_FILL);
            }
        }

        boolean empty = getContainer().getModWhitelist().isEmpty();
        int hintY = gy + rows * CELL + 3;
        graphics.drawString(font,
                Component.translatable(empty ? "gui.sbaddon.classify.hint.empty" : "gui.sbaddon.classify.hint"),
                getX() + PAD, hintY, COLOR_LABEL_DIM, false);

        // 兜底组排序切换
        SortBy sortBy = getContainer().getFallbackSort();
        Component sortLabel = Component.translatable("gui.sbaddon.classify.sort",
                Component.translatable("gui.sbaddon.classify.sort." + sortBy.getSerializedName()));
        int w = Math.min(getWidth() - PAD * 2, font.width(sortLabel) + 6);
        int x = getX() + PAD;
        int y = hintY + 11;
        sortButtonX = x;
        sortButtonY = y;
        sortButtonW = w;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BAR_H - 1;
        graphics.fill(x, y, x + w, y + BAR_H - 1, hovered ? 0xFF6E8A5A : COLOR_BAR_BTN);
        graphics.drawString(font, sortLabel, x + 3, y + 1, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (sortButtonW > 0
                && mouseX >= sortButtonX && mouseX < sortButtonX + sortButtonW
                && mouseY >= sortButtonY && mouseY < sortButtonY + BAR_H - 1) {
            SortBy candidate = getContainer().getFallbackSort().next();
            if (candidate == SortBy.MOD) {
                candidate = candidate.next();
            }
            final SortBy next = candidate;
            getContainer().setFallbackSort(next);
            getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.sortTag(next));
            return true;
        }
        return false;
    }

    @Override
    public void renderTooltip(Screen parent, GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(parent, graphics, mouseX, mouseY);
    }

    private int gridX() {
        return getX() + PAD + 1;
    }

    private int gridY() {
        return getY() + TITLE_H + 1;
    }
}
