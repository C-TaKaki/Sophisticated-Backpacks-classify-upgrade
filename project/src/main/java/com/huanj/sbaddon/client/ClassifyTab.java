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
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.GuiHelper;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类配置页：列表形式。
 * <p>
 * 每一行 = 一个已配置的模组条目：左侧图标（该行的样本槽物品）+ 中间模组名 + 右侧拖拽手柄。
 * 行序即背包网格里的分类顺序；拖手柄可调序。条目数不固定，超出可视区时右侧出滚动条。
 * 末尾始终保留一个空行用于拖入新增（样本槽是幽灵槽，放入不消耗物品）。
 */
public class ClassifyTab extends UpgradeSettingsTab<ClassifyUpgradeContainer> {

    public static final int TAB_WIDTH = 150;
    public static final int TAB_HEIGHT = 170;

    /** 标题标签画在 y+8、高 9，所以内容区从 y+17 以下开始；再加 4px 呼吸空间。 */
    private static final int TITLE_H = 17;
    private static final int PAD = 4;
    private static final int ROW_H = 20;
    private static final int ROW_FILL_H = 18;
    private static final int HANDLE_W = 9;
    private static final int DELETE_W = 9;
    private static final int CELL = 18;
    /** 底部只留一行提示文字。 */
    private static final int FOOTER_H = 18;
    private static final int SCROLLBAR_W = 3;

    private static final int COLOR_ROW_BG = 0xFF8E8E8E;
    private static final int COLOR_ROW_HL = 0xFFB8B8B8;
    private static final int COLOR_ROW_SH = 0xFF5E5E5E;
    private static final int COLOR_LABEL = 0xFF1E1E1E;
    private static final int COLOR_LABEL_DIM = 0xFF5A5A5A;
    private static final int COLOR_HANDLE = 0xFF3C3C3C;

    /** 渲染期缓存的命中区：rowHits[i] = {行号, 是否条目, x, y, w, h, handleX, deleteX}。 */
    private final List<int[]> rowHits = new ArrayList<>();

    /** 当前打开的页签实例：SB 的屏幕不把滚轮事件派发给子控件，只能由 mixin 转交过来。 */
    private static ClassifyTab activeTab;

    public static ClassifyTab getActiveTab() {
        return activeTab;
    }

    private int scrollRow;
    private int draggingRow = -1;
    private int dragTargetRow = -1;

    public ClassifyTab(ClassifyUpgradeContainer container, Position position, StorageScreenBase<?> screen) {
        super(container, position, screen,
                Component.translatable("gui.sbaddon.classify.title"),
                Component.translatable("gui.sbaddon.classify.tooltip"));
        this.openTabDimension = new Dimension(TAB_WIDTH, TAB_HEIGHT);
    }

    // ---------------------------------------------------------------- 位置

    /**
     * 展开后整体向左偏移的像素数。
     * <p>
     * SB 把页签的 x 贴着背包面板内缘放，左侧两个圆角正好被面板边框压掉，看起来是直角；
     * 原版页签同样带圆角，只是位置更靠左、圆角露在面板外。这里跟着左移，把左侧圆角让出来。
     */
    private static final int X_SHIFT = 6;

    /** SB 给的原始 X（未偏移）。每次打开都从它重新算，避免反复开关导致偏移累积。 */
    private int baseX = Integer.MIN_VALUE;

    @Override
    protected void onTabOpen() {
        if (baseX == Integer.MIN_VALUE) {
            baseX = getX();
        }
        setPosition(new Position(baseX - X_SHIFT, getY()));
        super.onTabOpen();
    }

    @Override
    protected void onTabClose() {
        // 收起时要回到原位，否则右侧那条竖着的小页签会整体左移，和背包上的图标对不上
        if (baseX != Integer.MIN_VALUE) {
            setPosition(new Position(baseX, getY()));
        }
        super.onTabClose();
    }

    // ---------------------------------------------------------------- 布局

    private int listTop() {
        return getY() + TITLE_H + 4;
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
        List<Integer> used = usedSlots();
        List<Integer> rows = new ArrayList<>(used);
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

    /**
     * 页签关闭时把槽位彻底移出屏幕。
     * SB 默认只把 x 改成 -2000、保留 y，配合我们每帧的布局会留下残影，这里两个坐标一起清。
     */
    @Override
    protected void moveSlotsOutOfView() {
        for (Slot slot : getContainer().getSlots()) {
            slot.x = -10000;
            slot.y = -10000;
        }
    }

    /** 把每个样本槽摆到它所在行的图标位置上；不在可见范围内的丢到屏幕外。 */
    private void layoutSlots() {
        List<Slot> slots = getContainer().getSlots();
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();
        List<Integer> rows = rows();
        int visible = rowsVisible();

        for (Slot slot : slots) {
            slot.x = -10000;
            slot.y = -10000;
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
        return getWidth() - 6 - SCROLLBAR_W - 2;
    }

    private int rowY(int visRow) {
        return listTop() + visRow * ROW_H;
    }

    private int rowIconX() {
        // 图标槽位坐标：槽位背景从 rowX() 起画（见 drawList），所以这里比行左缘缩进 1px，
        // 让 18×18 的槽位背景正好和行背景左缘齐平，不留额外的空白带。
        return rowX() + 1;
    }

    private int rowIconY(int visRow) {
        // 行高 18、图标 16，上下各留 1px
        return rowY(visRow) + 1;
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (!getContainer().isOpen()) {
            // 页签收起时不再排版，否则槽位会被重新摆回面板位置，物品就"漏"到界面外
            if (activeTab == this) {
                activeTab = null;
            }
            return;
        }

        List<Integer> rows = rows();
        int visible = rowsVisible();
        int maxScroll = Math.max(0, rows.size() - visible);
        scrollRow = Math.min(Math.max(scrollRow, 0), maxScroll);

        activeTab = this;
        layoutSlots();
        rowHits.clear();

        drawList(graphics, mouseX, mouseY, rows);
        drawScrollbar(graphics, rows.size(), visible);
        drawFooter(graphics);
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY, List<Integer> rows) {
        int visible = rowsVisible();
        int x = rowX();
        int w = rowWidth();
        int h = ROW_FILL_H;
        int usedCount = usedSlots().size();

        for (int r = scrollRow; r < rows.size() && r < scrollRow + visible; r++) {
            int vis = r - scrollRow;
            int y = rowY(vis);
            boolean entry = r < usedCount;
            boolean hovered = inside(mouseX, mouseY, x, y, w, h);
            boolean dragging = entry && draggingRow == r;

            int bg = dragging ? 0xFF9CB088 : hovered ? 0xFF9E9E9E : COLOR_ROW_BG;
            graphics.fill(x, y, x + w, y + h, bg);
            graphics.fill(x, y, x + w, y + 1, COLOR_ROW_HL);
            graphics.fill(x, y + h - 1, x + w, y + h, COLOR_ROW_SH);

            // 图标格：直接用精妙背包自己的槽位背景贴图（18×18，和原版设置页 / 背包网格里的槽位同款）。
            // 背景左上角比槽位坐标外扩 1px，物品正好落在凹槽内居中 —— 原来手绘的深灰方块
            // 起点和宽度都没按这套几何算，所以和图标对不上。
            int iconX = rowIconX();
            int iconY = rowIconY(vis);
            GuiHelper.renderSlotsBackground(graphics, iconX - 1, iconY - 1, 1, 1);

            int handleX = x + w - HANDLE_W - 3;
            int deleteX = handleX - DELETE_W - 1;
            int nameX = iconX + CELL + 2;
            int nameW = Math.max(0, deleteX - nameX - 3);
            String name;
            if (entry) {
                ItemStack sample = getContainer().getSamples().getStackInSlot(rows.get(r));
                String namespace = ModNames.namespaceOf(sample);
                ItemStack icon = ModNames.icon(namespace);
                graphics.renderItem(icon.isEmpty() ? sample : icon, iconX, iconY);
                name = ModNames.displayName(namespace).getString();
            } else {
                name = Component.translatable("gui.sbaddon.classify.row.empty").getString();
            }
            graphics.drawString(font, truncate(name, nameW), nameX, y + 5,
                    entry ? COLOR_LABEL : COLOR_LABEL_DIM, false);
            drawHandle(graphics, handleX, y, h, entry);
            if (entry) {
                drawDelete(graphics, deleteX, y, h, inside(mouseX, mouseY, deleteX, y, DELETE_W, h));
            }

            rowHits.add(new int[]{r, entry ? 1 : 0, x, y, w, h, handleX, deleteX});
        }
    }

    /** 行尾的删除按钮：点一下直接删掉这条分类，不从槽里"取出"物品（取样槽不可拿取）。 */
    private void drawDelete(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + DELETE_W, y + h - 2, 0xFFA34A4A);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF3C1A1A;
        int cx = x + 2;
        int cy = y + h / 2 - 2;
        graphics.fill(cx, cy, cx + 5, cy + 1, color);
        graphics.fill(cx, cy + 4, cx + 5, cy + 5, color);
        graphics.fill(cx + 1, cy + 1, cx + 2, cy + 2, color);
        graphics.fill(cx + 3, cy + 1, cx + 4, cy + 2, color);
        graphics.fill(cx + 1, cy + 3, cx + 2, cy + 4, color);
        graphics.fill(cx + 3, cy + 3, cx + 4, cy + 4, color);
    }

    /** 内容超出可视行数时，右侧画一条滚动条（轨道 + 滑块）。 */
    private void drawScrollbar(GuiGraphics graphics, int totalRows, int visible) {
        if (totalRows <= visible) {
            return;
        }
        int x = getX() + getWidth() - SCROLLBAR_W - 2;
        int top = listTop();
        int trackH = visible * ROW_H - 2;
        graphics.fill(x, top, x + SCROLLBAR_W, top + trackH, 0xFF6A6A6A);

        int thumbH = Math.max(10, trackH * visible / totalRows);
        int maxScroll = totalRows - visible;
        int thumbY = top + (trackH - thumbH) * Math.min(scrollRow, maxScroll) / Math.max(1, maxScroll);
        graphics.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, 0xFFD8D8D8);
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

    private void drawFooter(GuiGraphics graphics) {
        int textLeft = getX() + PAD + 1;
        int maxTextWidth = getWidth() - PAD * 2 - 2;
        int hintY = getY() + TAB_HEIGHT - FOOTER_H + 2;

        Component hint = Component.translatable("gui.sbaddon.classify.hint");
        graphics.drawString(font, truncate(hint.getString(), maxTextWidth), textLeft, hintY, COLOR_LABEL_DIM, false);
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
        for (int[] hit : rowHits) {
            if (hit[1] != 1) {
                continue;
            }
            if (inside(mouseX, mouseY, hit[7], hit[3], DELETE_W, hit[5])) {
                deleteEntry(hit[0]);
                return true;
            }
            if (inside(mouseX, mouseY, hit[6], hit[3], HANDLE_W, hit[5])) {
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

    /** 删掉第 row 条分类：直接重排样本槽数据并同步服务端，槽里的物品本身不还给玩家。 */
    private void deleteEntry(int row) {
        List<Integer> order = new ArrayList<>(usedSlots());
        if (row < 0 || row >= order.size()) {
            return;
        }
        order.remove(row);
        int[] slotOrder = order.stream().mapToInt(Integer::intValue).toArray();
        getContainer().applyOrder(slotOrder);
        getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.reorderTag(slotOrder));
        if (scrollRow > 0) {
            scrollRow--;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollList(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 在列表区域滚轮时滚列表本身。
     * <p>
     * SB 的 {@code StorageScreenBase.mouseScrolled} 不把事件派发给页签，所以这个方法是
     * 由 {@code StorageScreenBaseMixin} 在滚轮入口主动调用的；鼠标在列表上时一律吃掉事件，
     * 避免背包网格跟着滚。
     */
    public boolean scrollList(double mouseX, double mouseY, double scrollY) {
        if (!getContainer().isOpen()) {
            return false;
        }
        int visible = rowsVisible();
        int listBottom = listTop() + visible * ROW_H;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < listTop() || mouseY >= listBottom) {
            return false;
        }
        int maxScroll = Math.max(0, rows().size() - visible);
        if (maxScroll > 0 && scrollY != 0) {
            scrollRow = Math.min(Math.max(scrollRow - (int) Math.signum(scrollY), 0), maxScroll);
        }
        return true;
    }

    @Override
    public void renderTooltip(Screen parent, GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(parent, graphics, mouseX, mouseY);
    }
}
