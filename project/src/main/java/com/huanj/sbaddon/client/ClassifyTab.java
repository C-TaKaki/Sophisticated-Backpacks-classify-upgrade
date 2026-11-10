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
    /** 正在编辑的文本（就地编辑，不依赖任何外部输入框控件）。 */
    private String editingText = "";
    /** 上一次提交被拒（重名 / 超长）：输入框描边与文字转红。 */
    private boolean editingError;
    /** 编辑态下「勾」「叉」的命中区，每帧渲染时写入。 */
    private int[] renameSaveHit;
    private int[] renameCancelHit;

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
    }

    // ---------------------------------------------------------------- 改名（就地编辑）

    /**
     * 就地改名：不创建任何输入框控件，输入框由页签自己画在名称位置上，键盘事件由
     * {@code StorageScreenBaseMixin} 转发进来。
     * <p>
     * 之前用 SB 的 TextBox + Screen#addRenderableWidget 那套，实际点「编辑」没反应 ——
     * 原因是 SB 屏幕的渲染/焦点流程跟原版 renderables 不是一条路径，输入框画不出来也收不到键。
     * 现在全部自己接管：点「编辑」→ 按钮消失、名称变输入框、右侧出现勾/叉。
     */
    private void startRename(int row) {
        List<Integer> entries = usedSlots();
        if (row < 0 || row >= entries.size()) {
            return;
        }
        ItemStack sample = getContainer().getSamples().getStackInSlot(entries.get(row));
        if (ClassifyGroups.groupId(sample) == null) {
            return;
        }
        editingRow = row;
        // 预填当前显示名（含已改过的自定义名）
        editingText = ClassifyGroups.describe(sample, getContainer().getCustomNames()).label().getString();
        editingError = false;
    }

    /**
     * 提交改名：
     * <ul>
     *   <li>空名 / 与默认名一致 → 视为恢复默认，删掉 NBT 记录；</li>
     *   <li>与其它分类重名（忽略大小写）→ 拒绝，标红并留在编辑态；</li>
     *   <li>超长 → 截到 {@link ClassifyUpgradeContainer#MAX_NAME_LENGTH} 后照常保存；</li>
     *   <li>没改动 → 直接退出编辑，不发包。</li>
     * </ul>
     */
    private void commitRename() {
        if (editingRow < 0) {
            return;
        }
        List<Integer> entries = usedSlots();
        if (editingRow >= entries.size()) {
            cancelRename();
            return;
        }
        ItemStack sample = getContainer().getSamples().getStackInSlot(entries.get(editingRow));
        String groupId = ClassifyGroups.groupId(sample);
        if (groupId == null) {
            cancelRename();
            return;
        }
        String typed = editingText.strip();
        if (typed.length() > ClassifyUpgradeContainer.MAX_NAME_LENGTH) {
            typed = typed.substring(0, ClassifyUpgradeContainer.MAX_NAME_LENGTH);
        }
        String defaultLabel = ClassifyGroups.describe(sample, java.util.Map.of()).label().getString();
        if (typed.equals(defaultLabel)) {
            typed = "";
        }
        if (!typed.isEmpty() && renameCollides(groupId, typed)) {
            editingText = typed;
            editingError = true;
            return;
        }
        String current = getContainer().getCustomNames().getOrDefault(groupId, "");
        String name = typed;
        if (!current.equals(name)) {
            getContainer().applyRename(groupId, name);
            getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.renameTag(groupId, name));
        }
        cancelRename();
    }

    /** 是否与其它分类当前的显示名撞车（忽略大小写、忽略首尾空白）。 */
    private boolean renameCollides(String selfGroupId, String name) {
        java.util.Map<String, String> names = getContainer().getCustomNames();
        for (ItemStack sample : getContainer().getSampleStacks()) {
            if (sample.isEmpty()) {
                continue;
            }
            String id = ClassifyGroups.groupId(sample);
            if (id == null || id.equals(selfGroupId)) {
                continue;
            }
            String label = ClassifyGroups.describe(sample, names).label().getString();
            if (label.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void cancelRename() {
        editingRow = -1;
        editingText = "";
        editingError = false;
        renameSaveHit = null;
        renameCancelHit = null;
    }

    /**
     * 键盘入口：由 {@code StorageScreenBaseMixin} 在屏幕的 keyPressed 里转交过来
     * （页签不是 Screen 的焦点元素，收不到原版的分发）。
     *
     * @return true = 事件已消费，屏幕不要再处理（避免一边打字一边触发背包热键）
     */
    public boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingRow < 0) {
            return false;
        }
        switch (keyCode) {
            case 256 -> cancelRename();                 // Esc = 放弃
            case 257, 335 -> commitRename();            // Enter / 小键盘 Enter
            case 259 -> {                               // Backspace
                if (!editingText.isEmpty()) {
                    editingText = editingText.substring(0, editingText.length() - 1);
                    editingError = false;
                }
            }
            case 86 -> {                                // Ctrl+V
                if ((modifiers & 2) != 0) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        appendTyped(clip.replace('\n', ' ').replace('\r', ' '));
                    }
                }
            }
            default -> {
                // 编辑态吞掉其它按键（E / Esc 之类不要顺手把背包关掉）
            }
        }
        return true;
    }

    /** 字符输入入口，同样由 mixin 转发。 */
    public boolean handleCharTyped(char codePoint, int modifiers) {
        if (editingRow < 0) {
            return false;
        }
        appendTyped(String.valueOf(codePoint));
        return true;
    }

    /** 追加文本并截断到上限；控制字符与「§」直接丢弃（避免染色代码破坏界面）。 */
    private void appendTyped(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c == 167) {
                continue;
            }
            if (editingText.length() >= ClassifyUpgradeContainer.MAX_NAME_LENGTH) {
                break;
            }
            editingText += c;
        }
        editingError = false;
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
        boolean editing = entry && r == editingRow;
        String name;
        if (entry) {
            ItemStack sample = getContainer().getSamples().getStackInSlot(slotIndex);
            // 图标与文字交给分组层统一决定：模组样本显示创造页图标，属性过滤器显示它自己
            ClassifyGroups.Row row = ClassifyGroups.describe(sample, getContainer().getCustomNames());
            ItemStack icon = row.icon().isEmpty() ? sample : row.icon();
            graphics.renderItem(icon, iconX, iconY);
            if (editing) {
                // 编辑态：名称位置换成输入框，右侧只留勾/叉，手柄和删除按钮让位
                drawRenameEditor(graphics, mouseX, mouseY, x, y, w, h);
                return;
            }
            name = row.label().getString();
        } else {
            name = Component.translatable("gui.sbaddon.classify.row.empty").getString();
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
     * 就地编辑的一行：深色输入框（描边在非法名时转红）+ 光标 + 右侧勾/叉。
     * 位置全部照着普通行的名称区域算，视觉上就是"名称变成输入框"。
     */
    private void drawRenameEditor(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        int right = x + w - 3;
        int cancelX = right - EDIT_W;
        int saveX = cancelX - EDIT_W - 1;
        int boxX = rowIconX() + CELL + 1;
        int boxW = Math.max(12, saveX - 3 - boxX);
        int boxY = y + 3;
        int boxH = h - 6;

        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF1B1B1B);
        int border = editingError ? 0xFFC06060 : 0xFF7AA0D8;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 1, border);
        graphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, border);
        graphics.fill(boxX, boxY, boxX + 1, boxY + boxH, border);
        graphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, border);
        if (editingError) {
            graphics.fill(boxX + 1, boxY + 1, boxX + boxW - 1, boxY + boxH - 1, 0xFF3A1E1E);
        }

        int textMax = boxW - 5;
        String shown = editingText;
        while (!shown.isEmpty() && font.width(shown) > textMax) {
            // 超宽时从头部截断，保证正在输入的尾部可见
            shown = shown.substring(1);
        }
        int textColor = editingError ? 0xFFFFA0A0 : 0xFFE8E8E8;
        graphics.drawString(font, shown, boxX + 2, boxY + 2, textColor, false);
        if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = boxX + 2 + font.width(shown);
            graphics.fill(cursorX, boxY + 2, cursorX + 1, boxY + boxH - 2, textColor);
        }

        drawCheck(graphics, saveX, y, h, inside(mouseX, mouseY, saveX, y, EDIT_W, h));
        drawCross(graphics, cancelX, y, h, inside(mouseX, mouseY, cancelX, y, EDIT_W, h));
        renameSaveHit = new int[]{saveX, y, EDIT_W, h};
        renameCancelHit = new int[]{cancelX, y, EDIT_W, h};
    }

    /** 勾：两段对角线拼出来的对号。 */
    private void drawCheck(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + EDIT_W, y + h - 2, 0xFF4F8A4F);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF9BE89B;
        int cx = x + (EDIT_W - 7) / 2;
        int cy = y + (h - 7) / 2;
        for (int i = 0; i < 3; i++) {
            graphics.fill(cx + 1 + i, cy + 3 + i, cx + 2 + i, cy + 4 + i, color);
        }
        for (int i = 0; i < 4; i++) {
            graphics.fill(cx + 3 + i, cy + 5 - i, cx + 4 + i, cy + 6 - i, color);
        }
    }

    /** 叉：和删除按钮同一套画法，只是颜色偏取消。 */
    private void drawCross(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + EDIT_W, y + h - 2, 0xFFA34A4A);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFFE89B9B;
        int size = 7;
        int cx = x + (EDIT_W - size) / 2;
        int cy = y + (h - size) / 2;
        for (int i = 0; i < size; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            graphics.fill(cx + size - 1 - i, cy + i, cx + size - i, cy + i + 1, color);
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
        // 编辑态优先吃点击：勾=保存，叉=放弃，点别处按放弃处理
        if (editingRow >= 0) {
            if (renameSaveHit != null
                    && inside(mouseX, mouseY, renameSaveHit[0], renameSaveHit[1], renameSaveHit[2], renameSaveHit[3])) {
                commitRename();
                return true;
            }
            if (renameCancelHit != null
                    && inside(mouseX, mouseY, renameCancelHit[0], renameCancelHit[1], renameCancelHit[2], renameCancelHit[3])) {
                cancelRename();
                return true;
            }
            cancelRename();
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
