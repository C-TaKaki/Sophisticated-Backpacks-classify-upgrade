package com.huanj.sbaddon.client;

import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeSettingsTab;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.TextBox;
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
    private static final int EDIT_W = 9;
    private static final int CELL = 18;
    private static final int SCROLLBAR_W = 3;
    /** 底部固定行右侧的「一键分类」按钮。 */
    private static final int AUTO_W = 44;
    private static final int AUTO_H = 12;

    private static final int COLOR_ROW_BG = 0xFF8E8E8E;
    private static final int COLOR_ROW_HL = 0xFFB8B8B8;
    private static final int COLOR_ROW_SH = 0xFF5E5E5E;
    private static final int COLOR_LABEL = 0xFF1E1E1E;
    private static final int COLOR_LABEL_DIM = 0xFF5A5A5A;
    private static final int COLOR_HANDLE = 0xFF3C3C3C;

    /** 渲染期缓存的命中区：rowHits[i] = {行号, 是否条目, x, y, w, h, handleX, deleteX}。 */
    private final List<int[]> rowHits = new ArrayList<>();

    /** 「一键分类」按钮的命中区，渲染时写入；不在固定行上时为 null。 */
    private int[] autoButton;

    /** 当前打开的页签实例：SB 的屏幕不把滚轮事件派发给子控件，只能由 mixin 转交过来。 */
    private static ClassifyTab activeTab;

    public static ClassifyTab getActiveTab() {
        return activeTab;
    }

    private int scrollRow;
    private int draggingRow = -1;
    private int dragTargetRow = -1;
    /** 拖动中鼠标的 y，被拖的那一行直接跟手。 */
    private double dragMouseY;

    /** 行索引 → 当前渲染 y（绝对坐标）。拖动让位时朝目标值插值，形成平滑移动。 */
    private final java.util.Map<Integer, Float> rowDisplayY = new java.util.HashMap<>();

    /** 正在改名的那一行（行索引）；-1 = 没在改名。 */
    private int editingRow = -1;
    /** 改名输入框：懒创建一次，之后复用（隐藏而不销毁，避免和 Screen 的 children 列表较劲）。 */
    private RenameBox renameBox;

    /** 上一帧的滚动行，用来识别"滚动发生了"——滚动不播让位动画，内容直接跳到新位置。 */
    private int lastScrollRow;

    public ClassifyTab(ClassifyUpgradeContainer container, Position position, StorageScreenBase<?> screen) {
        super(container, position, screen,
                Component.translatable("gui.sbaddon.classify.title"),
                Component.translatable("gui.sbaddon.classify.tooltip"));
        this.openTabDimension = new Dimension(TAB_WIDTH, TAB_HEIGHT);
    }

    // ---------------------------------------------------------------- 位置

    /**
     * 背景向左多扩出的像素数。
     * <p>
     * SB 把页签的 x 贴着背包面板内缘放，左侧两个圆角正好被面板边框压掉，看起来是直角；
     * 原版页签同样带圆角，只是位置更靠左、圆角露在面板外。
     * 这里只把**背景**向左扩，页面里的图标、文字、槽位仍然按原来的坐标排，不会跟着位移。
     */
    private static final int X_SHIFT = 3;

    /**
     * 只偏移背景的绘制范围：临时把 {@code x} 左移、宽度加大，画完立刻还原。
     * <p>
     * 不能改 {@link #onTabOpen()} 里的位置 —— 那样列表、槽位、文字全都以 {@code getX()} 为基准，
     * 会一起位移。
     */
    @Override
    protected void renderBg(GuiGraphics graphics, Minecraft minecraft, int mouseX, int mouseY) {
        int expandedWidth = getWidth();
        if (expandedWidth <= 21) {
            // 收起状态那条竖着的小页签保持原样
            super.renderBg(graphics, minecraft, mouseX, mouseY);
            return;
        }
        int oldX = x;
        x = oldX - X_SHIFT;
        setWidth(expandedWidth + X_SHIFT);
        try {
            super.renderBg(graphics, minecraft, mouseX, mouseY);
        } finally {
            x = oldX;
            setWidth(expandedWidth);
        }
    }

    // ---------------------------------------------------------------- 布局

    private int listTop() {
        return getY() + TITLE_H + 4;
    }

    /** 底部「拖入物品添加」那一行的 y：固定贴底，不随滚动。 */
    private int pinnedRowY() {
        return getY() + TAB_HEIGHT - ROW_H - PAD;
    }

    /** 列表区可显示的行数 —— 底部的添加行固定占一行，不参与滚动。 */
    private int rowsVisible() {
        return Math.max(1, (pinnedRowY() - listTop()) / ROW_H);
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

    @Override
    protected void moveSlotsToTab() {
        layoutSlots(usedSlots());
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
    private void layoutSlots(List<Integer> entries) {
        List<Slot> slots = getContainer().getSlots();
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();
        int visible = rowsVisible();

        for (Slot slot : slots) {
            slot.x = -10000;
            slot.y = -10000;
        }
        for (int r = scrollRow; r < entries.size() && r < scrollRow + visible; r++) {
            int slotIndex = entries.get(r);
            if (slotIndex < 0 || slotIndex >= slots.size()) {
                continue;
            }
            slots.get(slotIndex).x = rowIconX() - guiLeft;
            // 用动画后的 y：拖动让位时图标跟着行一起平滑移动
            slots.get(slotIndex).y = Math.round(displayY(r)) + 1 - guiTop;
        }
        // 底部固定的「拖入物品添加」行
        int emptySlot = firstEmptySlot();
        if (emptySlot >= 0 && emptySlot < slots.size()) {
            slots.get(emptySlot).x = rowIconX() - guiLeft;
            slots.get(emptySlot).y = pinnedRowY() + 1 - guiTop;
        }
        // 被拖的行滚出可见范围后，它的槽位在上面的循环里被丢到屏幕外了，
        // 单独摆一次，保证拖动时图标跟着行一起浮动
        if (draggingRow >= 0 && draggingRow < entries.size()
                && (draggingRow < scrollRow || draggingRow >= scrollRow + visible)) {
            int slotIndex = entries.get(draggingRow);
            if (slotIndex >= 0 && slotIndex < slots.size()) {
                slots.get(slotIndex).x = rowIconX() - guiLeft;
                slots.get(slotIndex).y = Math.round(displayY(draggingRow)) + 1 - guiTop;
            }
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

    // ---------------------------------------------------------------- 让位动画

    /** 该行的目标 y：拖动中的行跟手，其余行按「谁让位」错开一格。 */
    private float targetY(int r) {
        if (draggingRow == r) {
            double follow = dragMouseY - ROW_H / 2.0;
            double clamped = Math.max(listTop() - ROW_H / 2.0, Math.min(follow, pinnedRowY()));
            return (float) clamped;
        }
        int shifted = r;
        if (draggingRow >= 0 && dragTargetRow >= 0) {
            if (draggingRow < r && r <= dragTargetRow) {
                // 后面的行整体上移一格，把位置让出来
                shifted = r - 1;
            } else if (dragTargetRow <= r && r < draggingRow) {
                // 前面的行整体下移一格
                shifted = r + 1;
            }
        }
        return rowY(shifted - scrollRow);
    }

    /** 每帧刷新一次所有可见行的显示位置。 */
    private void updateRowPositions(List<Integer> entries) {
        if (scrollRow != lastScrollRow) {
            // 滚动时内容整列平移，插值会变成"从上往下滑"的补偿动画 —— 直接丢掉缓存，
            // 让每行瞬间落到新位置，滚动过程保持稳定
            rowDisplayY.clear();
            lastScrollRow = scrollRow;
        }
        int visible = rowsVisible();
        rowDisplayY.keySet().removeIf(r -> r >= entries.size());
        for (int r = scrollRow; r < entries.size() && r < scrollRow + visible; r++) {
            stepTo(r);
        }
        if (draggingRow >= 0) {
            stepTo(draggingRow);
        }
    }

    /**
     * 朝目标位置插值。拖动中的行不做插值（否则跟不上鼠标），其余行按 0.45 的系数收敛，
     * 60fps 下大约 4 帧到位，观感是"滑过去"而不是"跳过去"。
     */
    private void stepTo(int r) {
        float target = targetY(r);
        Float current = rowDisplayY.get(r);
        if (current == null || draggingRow == r) {
            rowDisplayY.put(r, target);
            return;
        }
        float diff = target - current;
        rowDisplayY.put(r, Math.abs(diff) < 0.4f ? target : current + diff * 0.45f);
    }

    /** 行的当前显示 y；没算过就退回静态位置。 */
    private float displayY(int r) {
        Float y = rowDisplayY.get(r);
        return y != null ? y : rowY(r - scrollRow);
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (!getContainer().isOpen()) {
            // 页签收起时不再排版，否则槽位会被重新摆回面板位置，物品就"漏"到界面外
            cancelRename();
            if (activeTab == this) {
                activeTab = null;
            }
            return;
        }

        List<Integer> entries = usedSlots();
        int visible = rowsVisible();
        scrollRow = Math.min(Math.max(scrollRow, 0), Math.max(0, entries.size() - visible));

        activeTab = this;
        updateRowPositions(entries);
        layoutSlots(entries);
        rowHits.clear();
        autoButton = null;

        drawList(graphics, mouseX, mouseY, entries);
        drawPinnedRow(graphics, mouseX, mouseY);
        drawScrollbar(graphics, entries.size(), visible);
        syncRenameBox();
    }

    // ---------------------------------------------------------------- 改名

    /**
     * 改名输入框是懒创建的：挂在 Screen 的 children 上（走它的键盘事件分发），
     * 关闭编辑时只隐藏不销毁 —— 自己从 Screen 里摘除要走 protected 方法，不值当。
     * <p>
     * 交互约定：回车保存；点别处 / 按 Esc（都表现为失焦）取消。
     */
    private void syncRenameBox() {
        if (editingRow < 0 || renameBox == null) {
            return;
        }
        if (screen.getFocused() != renameBox) {
            cancelRename();
            return;
        }
        renameBox.setPosition(new Position(rowX(), Math.round(displayY(editingRow))));
    }

    private void startRename(int row) {
        List<Integer> entries = usedSlots();
        if (row < 0 || row >= entries.size()) {
            return;
        }
        ItemStack sample = getContainer().getSamples().getStackInSlot(entries.get(row));
        String groupId = ClassifyGroups.groupId(sample);
        if (groupId == null) {
            return;
        }
        if (renameBox == null) {
            renameBox = new RenameBox(new Position(rowX(), 0), new Dimension(rowWidth(), ROW_FILL_H));
            renameBox.setMaxLength(ClassifyUpgradeContainer.MAX_NAME_LENGTH);
            ((com.huanj.sbaddon.mixin.ScreenAccess) screen).sbaddon$addRenderableWidget(renameBox);
        }
        editingRow = row;
        // 输入框预填当前显示的名字（含自定义名），玩家想恢复默认就把它清空
        renameBox.setValue(ClassifyGroups.describe(sample, getContainer().getCustomNames()).label().getString());
        renameBox.setPosition(new Position(rowX(), Math.round(displayY(row))));
        renameBox.setVisible(true);
        screen.setFocused(renameBox);
        renameBox.setFocused(true);
    }

    /** 回车提交：名字和默认名一致就当作"恢复默认"，把 NBT 里那条记录删掉。 */
    private void commitRename() {
        if (editingRow < 0 || renameBox == null) {
            return;
        }
        List<Integer> entries = usedSlots();
        if (editingRow < entries.size()) {
            ItemStack sample = getContainer().getSamples().getStackInSlot(entries.get(editingRow));
            String groupId = ClassifyGroups.groupId(sample);
            String typed = renameBox.getValue().strip();
            String defaultLabel = ClassifyGroups.describe(sample, java.util.Map.of()).label().getString();
            if (typed.equals(defaultLabel)) {
                typed = "";
            }
            if (groupId != null) {
                String name = typed;
                getContainer().applyRename(groupId, name);
                getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.renameTag(groupId, name));
            }
        }
        cancelRename();
    }

    private void cancelRename() {
        editingRow = -1;
        if (renameBox != null) {
            renameBox.setVisible(false);
            renameBox.setFocused(false);
            if (screen.getFocused() == renameBox) {
                screen.setFocused(null);
            }
        }
    }

    /** 回车 = 保存。TextBox 的 onEnterPressed 是 protected，只能靠子类接。 */
    private final class RenameBox extends TextBox {
        RenameBox(Position position, Dimension dimension) {
            super(position, dimension);
        }

        @Override
        protected void onEnterPressed() {
            commitRename();
        }
    }

    /** 条目行。拖动中的那一行最后画，压在其他行上面。 */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY, List<Integer> entries) {
        int visible = rowsVisible();
        int last = Math.min(entries.size(), scrollRow + visible);
        for (int r = scrollRow; r < last; r++) {
            if (r == draggingRow) {
                continue;
            }
            drawRow(graphics, mouseX, mouseY, r, entries.get(r), true, Math.round(displayY(r)));
        }
        // 被拖的行**无条件**画：拖动到边缘触发自动滚动后，它原来的索引已经滚出可见范围
        // （draggingRow < scrollRow），但视觉上必须一直黏在鼠标上，否则看起来像拖丢了
        if (draggingRow >= 0 && draggingRow < entries.size()) {
            drawRow(graphics, mouseX, mouseY, draggingRow, entries.get(draggingRow), true,
                    Math.round(displayY(draggingRow)));
        }
    }

    /** 底部固定行：拖入物品即新增一条分类。 */
    private void drawPinnedRow(GuiGraphics graphics, int mouseX, int mouseY) {
        int emptySlot = firstEmptySlot();
        if (emptySlot < 0) {
            return;
        }
        drawRow(graphics, mouseX, mouseY, -1, emptySlot, false, pinnedRowY());
    }

    private void drawRow(GuiGraphics graphics, int mouseX, int mouseY, int r, int slotIndex,
                         boolean entry, int y) {
        int x = rowX();
        int w = rowWidth();
        int h = ROW_FILL_H;
        boolean dragging = entry && draggingRow == r;
        boolean hovered = !dragging && inside(mouseX, mouseY, x, y, w, h);

        int bg = dragging ? 0xFF9CB088 : hovered ? 0xFF9E9E9E : COLOR_ROW_BG;
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.fill(x, y, x + w, y + 1, COLOR_ROW_HL);
        graphics.fill(x, y + h - 1, x + w, y + h, COLOR_ROW_SH);

        // 图标格：直接用精妙背包自己的槽位背景贴图（18×18，和原版设置页 / 背包网格里的槽位同款）。
        // 背景左上角比槽位坐标外扩 1px，物品正好落在凹槽内居中。
        int iconX = rowIconX();
        int iconY = y + 1;
        GuiHelper.renderSlotsBackground(graphics, iconX - 1, iconY - 1, 1, 1);

        int handleX = x + w - HANDLE_W - 3;
        int deleteX = handleX - DELETE_W - 1;
        int editX = deleteX - EDIT_W - 1;
        int nameX = iconX + CELL + 2;
        int nameW = Math.max(0, editX - nameX - 3);
        String name;
        if (entry) {
            ItemStack sample = getContainer().getSamples().getStackInSlot(slotIndex);
            // 图标与文字交给分组层统一决定：模组样本显示创造页图标，属性过滤器显示它自己
            ClassifyGroups.Row row = ClassifyGroups.describe(sample, getContainer().getCustomNames());
            ItemStack icon = row.icon().isEmpty() ? sample : row.icon();
            graphics.renderItem(icon, iconX, iconY);
            name = row.label().getString();
        } else {
            name = Component.translatable("gui.sbaddon.classify.row.empty").getString();
        }
        if (entry && r == editingRow) {
            // 正在改名：输入框盖在这一行上，行文字让位，避免和输入内容叠字
            name = "";
        }
        graphics.drawString(font, truncate(name, nameW), nameX, y + 5,
                entry ? COLOR_LABEL : COLOR_LABEL_DIM, false);
        if (!entry) {
            drawAutoButton(graphics, mouseX, mouseY, x, y, w, h);
        }
        if (entry) {
            // 拖拽手柄只属于已配置条目；底部「拖入物品添加」行不放手柄
            drawHandle(graphics, handleX, y, h, true);
            drawEdit(graphics, editX, y, h, inside(mouseX, mouseY, editX, y, EDIT_W, h));
            drawDelete(graphics, deleteX, y, h, inside(mouseX, mouseY, deleteX, y, DELETE_W, h));
        }

        if (entry) {
            rowHits.add(new int[]{r, 1, x, y, w, h, handleX, deleteX, editX});
        }
    }

    /**
     * 固定行右侧的「一键分类」按钮：点一下让服务端扫描背包，把每个模组的一个代表物品
     * 填进空的样本槽。样本槽满了就不画。
     */
    private void drawAutoButton(GuiGraphics graphics, int mouseX, int mouseY,
                                int rowX, int rowY, int rowW, int rowH) {
        if (firstEmptySlot() < 0) {
            return;
        }
        int bx = rowX + rowW - AUTO_W - 6;
        int by = rowY + (rowH - AUTO_H) / 2;
        boolean hovered = inside(mouseX, mouseY, bx, by, AUTO_W, AUTO_H);
        graphics.fill(bx, by, bx + AUTO_W, by + AUTO_H, hovered ? 0xFF6F8F5A : 0xFF5D7A49);
        graphics.fill(bx, by, bx + AUTO_W, by + 1, 0xFF9DBB85);
        graphics.fill(bx, by + AUTO_H - 1, bx + AUTO_W, by + AUTO_H, 0xFF3E5432);
        String label = Component.translatable("gui.sbaddon.classify.auto").getString();
        int tw = font.width(label);
        graphics.drawString(font, label, bx + (AUTO_W - tw) / 2, by + 2, 0xFFF2F2F2, false);
        autoButton = new int[]{bx, by, AUTO_W, AUTO_H};
    }

    /** 行尾的「改名」按钮：一支 45° 铅笔。 */
    private void drawEdit(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + EDIT_W, y + h - 2, 0xFF4A6FA3);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF20344D;
        int size = 7;
        int cx = x + (EDIT_W - size) / 2;
        int cy = y + (h - size) / 2;
        // 笔杆：从左下到右上的对角线
        for (int i = 0; i < 5; i++) {
            graphics.fill(cx + i, cy + 5 - i, cx + i + 1, cy + 6 - i, color);
        }
        // 笔尖
        graphics.fill(cx + 5, cy, cx + 7, cy + 2, color);
    }

    /** 行尾的删除按钮：标准「×」，点一下直接删掉这条分类（样本槽不可拿取）。 */
    private void drawDelete(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + DELETE_W, y + h - 2, 0xFFA34A4A);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF3C1A1A;
        // 7×7 的两条 45° 对角线，1px 粗
        int size = 7;
        int cx = x + (DELETE_W - size) / 2;
        int cy = y + (h - size) / 2;
        for (int i = 0; i < size; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            graphics.fill(cx + size - 1 - i, cy + i, cx + size - i, cy + i + 1, color);
        }
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

    /** 三条横线做排序手柄，线间距 3px，整体在行内垂直居中。 */
    private void drawHandle(GuiGraphics graphics, int x, int y, int h, boolean active) {
        int color = active ? COLOR_HANDLE : 0xFF7A7A7A;
        int lines = 3;
        int spacing = 3;
        int totalH = (lines - 1) * spacing + 1;
        int cx = x + 2;
        int cy = y + (h - totalH) / 2;
        for (int i = 0; i < lines; i++) {
            int lineY = cy + i * spacing;
            graphics.fill(cx, lineY, cx + 5, lineY + 1, color);
        }
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
        if (autoButton != null
                && inside(mouseX, mouseY, autoButton[0], autoButton[1], autoButton[2], autoButton[3])) {
            getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.autoClassifyTag());
            return true;
        }
        for (int[] hit : rowHits) {
            if (hit[1] != 1) {
                continue;
            }
            if (hit[8] >= 0 && inside(mouseX, mouseY, hit[8], hit[3], EDIT_W, hit[5])) {
                startRename(hit[0]);
                return true;
            }
            if (inside(mouseX, mouseY, hit[7], hit[3], DELETE_W, hit[5])) {
                deleteEntry(hit[0]);
                return true;
            }
            if (inside(mouseX, mouseY, hit[6], hit[3], HANDLE_W, hit[5])) {
                draggingRow = hit[0];
                dragTargetRow = hit[0];
                dragMouseY = mouseY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingRow >= 0) {
            dragMouseY = mouseY;
            int usedCount = usedSlots().size();
            if (usedCount <= 0) {
                return true;
            }
            int visible = rowsVisible();
            int maxScroll = Math.max(0, usedCount - visible);
            int top = listTop();
            int bottom = top + visible * ROW_H;

            // 拖到列表上下边缘（还在列表范围内、没越界）就自动滚动一行，
            // 这样才能把条目拖到当前看不见的位置
            if (mouseY < top + ROW_H && scrollRow > 0) {
                scrollRow--;
            } else if (mouseY > bottom - ROW_H && scrollRow < maxScroll) {
                scrollRow++;
            }

            int row = scrollRow + (int) Math.floor((mouseY - top) / (double) ROW_H);
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
            // 松手瞬间被拖行的视觉位置。重排后缓存必须清空：缓存的 key 是「行索引」，
            // 而重排把各索引对应的条目换了，留着旧缓存会让每一行都从旧位置再滑一次
            // （表现就是松手后动画重播）。清空后只有被拖那一行从当前位置收尾，其余行
            // 直接落在让位后的位置上 —— 它们在拖动过程中已经停在那里了。
            float releasedY = displayY(from);
            draggingRow = -1;
            dragTargetRow = -1;
            rowDisplayY.clear();
            if (button == 0 && to >= 0 && from != to) {
                applyReorder(from, to);
                rowDisplayY.put(to, releasedY);
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
        // 行顺序变了，缓存的 y 与索引不再对应，直接丢掉，避免删完又播一次滑动
        rowDisplayY.clear();
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
        int maxScroll = Math.max(0, usedSlots().size() - visible);
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
