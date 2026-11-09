package com.huanj.sbaddon.client;

import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeSettingsTab;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Dimension;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.common.gui.SortBy;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类配置页：列表形式。
 * <p>
 * 每一行 = 一个已配置的模组条目：左侧图标（该行的样本槽物品）+ 中间模组名 + 右侧拖拽手柄。
 * 行序即背包网格里的分类顺序；拖手柄可调序。末尾保留一个空行用于拖入新增。
 * 顶部「+ / -」：加一条（把手上物品作为样本）、删最后一条（物品退回玩家背包）。
 */
public class ClassifyTab extends UpgradeSettingsTab<ClassifyUpgradeContainer> {

    public static final int TAB_WIDTH = 150;
    public static final int TAB_HEIGHT = 170;

    private static final int TITLE_H = 13;
    private static final int PAD = 4;
    private static final int ROW_H = 20;
    private static final int BTN = 11;
    private static final int HANDLE_W = 9;
    private static final int CELL = 18;
    private static final int BAR_H = 11;
    private static final int FOOTER_H = 34;

    private static final int COLOR_ROW_BG = 0xFF8E8E8E;
    private static final int COLOR_ROW_HL = 0xFFB8B8B8;
    private static final int COLOR_ROW_SH = 0xFF5E5E5E;
    private static final int COLOR_ICON_BOX = 0xFF4A4A4A;
    private static final int COLOR_LABEL = 0xFF1E1E1E;
    private static final int COLOR_LABEL_DIM = 0xFF5A5A5A;
    private static final int COLOR_BAR_BTN = 0xFF8A8A8A;
    private static final int COLOR_HANDLE = 0xFF3C3C3C;

    /** 渲染期缓存的命中区：rowHits[i] = {行内绝对行号, 是否条目, x, y, w, h, handleX}。 */
    private final List<int[]> rowHits = new ArrayList<>();

    private int plusX;
    private int plusY;
    private int minusX;
    private int minusY;
    private int sortButtonX;
    private int sortButtonY;
    private int sortButtonW;

    private int scrollRow;
    private int draggingRow = -1;
    private int dragTargetRow = -1;

    public ClassifyTab(ClassifyUpgradeContainer container, Position position, StorageScreenBase<?> screen) {
        super(container, position, screen,
                Component.translatable("gui.sbaddon.classify.title"),
                Component.translatable("gui.sbaddon.classify.tooltip"));
        this.openTabDimension = new Dimension(TAB_WIDTH, TAB_HEIGHT);
    }

    // ---------------------------------------------------------------- 布局

    private int listTop() {
        return getY() + TITLE_H + 16;
    }

    private int rowsVisible() {
        int available = TAB_HEIGHT - (listTop() - getY()) - FOOTER_H;
        return Math.max(1, available / ROW_H);
    }

    /** 已配置的样本槽索引（按槽位顺序），也就是条目的显示顺序。 */
    private List<Integer> usedSlots() {
        List<Integer> used = new ArrayList<>();
        var samples = getContainer().getSamples();
        for (int i = 0; i < samples.getSlots(); i++) {
            if (!samples.getStackInSlot(i).isEmpty()) {
                used.add(i);
            }
        }
        return used;
    }

    private int firstEmptySlot() {
        var samples = getContainer().getSamples();
        for (int i = 0; i < samples.getSlots(); i++) {
            if (samples.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 行 -> 样本槽索引；末尾多一行空行（用于拖入新增）。 */
    private List<Integer> rows() {
        List<Integer> rows = new ArrayList<>(usedSlots());
        int empty = firstEmptySlot();
        if (empty >= 0) {
            rows.add(empty);
        }
        return rows;
    }

    @Override
    protected void moveSlotsToTab() {
        layoutSlots();
    }

    /** 把每个样本槽摆到它所在行的图标位置上；不在可见范围内的丢到屏幕外。 */
    private void layoutSlots() {
        ClassifyUpgradeContainer container = getContainer();
        List<Slot> slots = container.getSlots();
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();
        List<Integer> rows = rows();
        int visible = rowsVisible();

        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).x = -10000;
            slots.get(i).y = -10000;
        }
        for (int r = scrollRow; r < rows.size() && r < scrollRow + visible; r++) {
            int slotIndex = rows.get(r);
            if (slotIndex < 0 || slotIndex >= slots.size()) {
                continue;
            }
            int vis = r - scrollRow;
            slots.get(slotIndex).x = rowIconX() - guiLeft;
            slots.get(slotIndex).y = rowIconY(vis) - guiTop;
        }
    }

    private int rowX() {
        return getX() + 3;
    }

    private int rowWidth() {
        return getWidth() - 6;
    }

    private int rowY(int visRow) {
        return listTop() + visRow * ROW_H;
    }

    private int rowIconX() {
        return rowX() + 3;
    }

    private int rowIconY(int visRow) {
        return rowY(visRow) + 2;
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        int maxScroll = Math.max(0, rows().size() - rowsVisible());
        scrollRow = Math.min(Math.max(scrollRow, 0), maxScroll);

        layoutSlots();
        rowHits.clear();

        drawButtons(graphics, mouseX, mouseY);
        drawList(graphics, mouseX, mouseY);
        drawFooter(graphics, mouseX, mouseY);
    }

    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        plusX = getX() + PAD + 1;
        plusY = getY() + TITLE_H + 2;
        minusX = plusX + BTN + 3;
        minusY = plusY;

        drawSmallButton(graphics, plusX, plusY, "+", mouseX, mouseY);
        drawSmallButton(graphics, minusX, minusY, "-", mouseX, mouseY);
    }

    private void drawSmallButton(GuiGraphics graphics, int x, int y, String glyph, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, BTN, BTN);
        graphics.fill(x, y, x + BTN, y + BTN, hovered ? 0xFFB4C8A4 : 0xFF9A9A9A);
        graphics.fill(x, y, x + BTN, y + 1, 0xFFE0E0E0);
        graphics.fill(x, y + BTN - 1, x + BTN, y + BTN, 0xFF5E5E5E);
        graphics.drawString(font, glyph, x + (BTN - font.width(glyph)) / 2 + 1, y + 2, 0xFF202020, false);
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Integer> rows = rows();
        int visible = rowsVisible();
        int x = rowX();
        int w = rowWidth();
        int h = ROW_H - 2;

        for (int r = scrollRow; r < rows.size() && r < scrollRow + visible; r++) {
            int vis = r - scrollRow;
            int y = rowY(vis);
            boolean entry = r < usedSlots().size();
            boolean hovered = inside(mouseX, mouseY, x, y, w, h);
            boolean dragging = entry && draggingRow == r;

            int bg = dragging ? 0xFF9CB088 : hovered ? 0xFF9E9E9E : COLOR_ROW_BG;
            graphics.fill(x, y, x + w, y + h, bg);
            graphics.fill(x, y, x + w, y + 1, COLOR_ROW_HL);
            graphics.fill(x, y + h - 1, x + w, y + h, COLOR_ROW_SH);

            // 图标框（槽位物品由界面绘制在框内）
            int iconX = rowIconX();
            int iconY = rowIconY(vis);
            graphics.fill(iconX - 2, iconY - 1, iconX + CELL - 2, iconY + CELL - 3, COLOR_ICON_BOX);

            int handleX = x + w - HANDLE_W - 3;
            int nameX = iconX + CELL + 2;
            int nameW = Math.max(0, handleX - nameX - 3);
            String name;
            if (entry) {
                ItemStack stack = getContainer().getSamples().getStackInSlot(rows.get(r));
                name = ModNames.displayName(ModNames.namespaceOf(stack)).getString();
            } else {
                name = Component.translatable("gui.sbaddon.classify.row.empty").getString();
            }
            graphics.drawString(font, truncate(name, nameW), nameX, y + 6,
                    entry ? COLOR_LABEL : COLOR_LABEL_DIM, false);
            drawHandle(graphics, handleX, y, h, entry);

            rowHits.add(new int[]{r, entry ? 1 : 0, x, y, w, h, handleX});
        }
    }

    private void drawHandle(GuiGraphics graphics, int x, int y, int h, boolean active) {
        int color = active ? COLOR_HANDLE : 0xFF7A7A7A;
        int cx = x + 2;
        int cy = y + 4;
        for (int i = 0; i < 3; i++) {
            int lineY = cy + i * 4;
            if (lineY + 1 > y + h - 2) {
                break;
            }
            graphics.fill(cx, lineY, cx + 5, lineY + 1, color);
        }
    }

    private void drawFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int textLeft = getX() + PAD + 1;
        int maxTextWidth = getWidth() - PAD * 2 - 2;
        int hintY = getY() + TAB_HEIGHT - FOOTER_H + 2;

        Component hint = Component.translatable("gui.sbaddon.classify.hint");
        graphics.drawString(font, truncate(hint.getString(), maxTextWidth), textLeft, hintY, COLOR_LABEL_DIM, false);

        SortBy sortBy = getContainer().getFallbackSort();
        Component sortLabel = Component.translatable("gui.sbaddon.classify.sort",
                Component.translatable("gui.sbaddon.classify.sort." + sortBy.getSerializedName()));
        int w = Math.min(maxTextWidth, font.width(sortLabel) + 6);
        int x = textLeft;
        int y = hintY + 11;
        sortButtonX = x;
        sortButtonY = y;
        sortButtonW = w;
        boolean hovered = inside(mouseX, mouseY, x, y, w, BAR_H - 1);
        graphics.fill(x, y, x + w, y + BAR_H - 1, hovered ? 0xFF6E8A5A : COLOR_BAR_BTN);
        graphics.drawString(font, truncate(sortLabel.getString(), w - 5), x + 3, y + 1, 0xFF202020, false);
    }

    private String truncate(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }

        if (inside(mouseX, mouseY, plusX, plusY, BTN, BTN)) {
            // 把手上的物品加为一条新条目（服务端执行，物品从光标移入样本槽）
            getContainer().sendDataToServer(ClassifyUpgradeContainer::addHeldTag);
            return true;
        }
        if (inside(mouseX, mouseY, minusX, minusY, BTN, BTN)) {
            // 删掉最后一条，物品退回玩家背包
            getContainer().sendDataToServer(ClassifyUpgradeContainer::removeLastTag);
            return true;
        }
        if (sortButtonW > 0 && inside(mouseX, mouseY, sortButtonX, sortButtonY, sortButtonW, BAR_H - 1)) {
            SortBy candidate = getContainer().getFallbackSort().next();
            if (candidate == SortBy.MOD) {
                candidate = candidate.next();
            }
            final SortBy next = candidate;
            getContainer().setFallbackSort(next);
            getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.sortTag(next));
            return true;
        }
        for (int[] hit : rowHits) {
            if (hit[1] == 1 && inside(mouseX, mouseY, hit[6], hit[3], HANDLE_W, hit[5])) {
                draggingRow = hit[0];
                dragTargetRow = hit[0];
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingRow >= 0) {
            int usedCount = usedSlots().size();
            if (usedCount <= 0) {
                return true;
            }
            int row = scrollRow + (int) Math.floor((mouseY - listTop()) / (double) ROW_H);
            dragTargetRow = Math.min(Math.max(row, 0), usedCount - 1);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingRow >= 0) {
            int from = draggingRow;
            int to = dragTargetRow;
            draggingRow = -1;
            dragTargetRow = -1;
            if (button == 0 && to >= 0 && from != to) {
                applyReorder(from, to);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 把「第 from 条」移到「第 to 条」的位置，得到新的槽位顺序并同步服务端。 */
    private void applyReorder(int from, int to) {
        List<Integer> order = new ArrayList<>(usedSlots());
        if (from < 0 || from >= order.size() || to < 0 || to >= order.size()) {
            return;
        }
        Integer moved = order.remove(from);
        order.add(to, moved);
        int[] slotOrder = order.stream().mapToInt(Integer::intValue).toArray();

        // 本地立即生效（服务端会收到同样的顺序，回包不会翻车）
        getContainer().applyOrder(slotOrder);
        getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.reorderTag(slotOrder));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int visible = rowsVisible();
        int maxScroll = Math.max(0, rows().size() - visible);
        int listBottom = listTop() + visible * ROW_H;
        if (maxScroll > 0 && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= listTop() && mouseY < listBottom) {
            scrollRow = Math.min(Math.max(scrollRow - (int) Math.signum(scrollY), 0), maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderTooltip(Screen parent, GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(parent, graphics, mouseX, mouseY);
    }
}
