package com.huanj.sbaddon.client;

import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeSettingsTab;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Dimension;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.common.gui.SortBy;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 分类页签。两个子页共用一块区域：
 * <ul>
 *   <li>列表页：按模组分组，每组一个标签框；未入名单的物品归「默认分类」放最后。</li>
 *   <li>配置页：3×5 样本槽 + 兜底组排序切换。</li>
 * </ul>
 */
public class ClassifyTab extends UpgradeSettingsTab<ClassifyUpgradeContainer> {

    public static final int TAB_WIDTH = 118;
    public static final int TAB_HEIGHT = 132;

    /** 标题 label 占据的高度，由 SettingsTabBase#addLabel 决定。 */
    private static final int TITLE_H = 13;
    private static final int PAD = 5;
    /** 顶部「列表 / 配置」切换条高度。 */
    private static final int BAR_H = 11;
    private static final int HEADER_H = 10;
    private static final int CELL = 18;
    private static final int GROUP_GAP = 4;

    private static final int COLOR_LABEL = 0xFF3A3A3A;
    private static final int COLOR_LABEL_DIM = 0xFF6E6E6E;
    private static final int COLOR_BOX_BORDER = 0xFF5A5A5A;
    private static final int COLOR_BOX_FILL = 0xFFEDEDED;
    private static final int COLOR_BAR_BTN = 0xFF8A8A8A;
    private static final int COLOR_BAR_BTN_ACTIVE = 0xFF4E7A3A;

    private int scrollOffset;
    private int scrollMax;
    private int lastView = -1;

    private final List<Hit> itemHits = new ArrayList<>();
    private final List<Hit> headerHits = new ArrayList<>();
    private final List<Rect> viewHits = new ArrayList<>();
    private Rect sortHit;

    public ClassifyTab(ClassifyUpgradeContainer container, Position position, StorageScreenBase<?> screen) {
        super(container, position, screen,
                Component.translatable("gui.sbaddon.classify.title"),
                Component.translatable("gui.sbaddon.classify.tooltip"));
        this.openTabDimension = new Dimension(TAB_WIDTH, TAB_HEIGHT);
    }

    // ------------------------------------------------------------------ 槽位摆放

    @Override
    protected void moveSlotsToTab() {
        positionSampleSlots();
    }

    private void positionSampleSlots() {
        ClassifyUpgradeContainer container = getContainer();
        boolean config = container.getView() == ClassifyUpgradeWrapper.VIEW_CONFIG;
        int guiLeft = screen.getGuiLeft();
        int guiTop = screen.getGuiTop();

        List<Slot> slots = container.getSlots();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!config) {
                // 列表页不显示样本槽，挪出屏幕
                slot.x = -10000;
                slot.y = -10000;
                continue;
            }
            int col = i % ClassifyUpgradeContainer.SAMPLE_COLUMNS;
            int row = i / ClassifyUpgradeContainer.SAMPLE_COLUMNS;
            slot.x = gridX() + col * CELL - guiLeft;
            slot.y = gridY() + row * CELL - guiTop;
        }
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        itemHits.clear();
        headerHits.clear();
        viewHits.clear();
        sortHit = null;

        int view = getContainer().getView();
        if (view != lastView) {
            lastView = view;
            scrollOffset = 0;
            positionSampleSlots();
        }

        renderBar(graphics, mouseX, mouseY);
        if (view == ClassifyUpgradeWrapper.VIEW_CONFIG) {
            renderConfig(graphics, mouseX, mouseY);
        } else {
            renderList(graphics, mouseX, mouseY);
        }
    }

    private void renderBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = getY() + TITLE_H;
        int x = getX() + PAD;
        x = drawBarButton(graphics, x, y, "gui.sbaddon.classify.view.list",
                getContainer().getView() == ClassifyUpgradeWrapper.VIEW_LIST, mouseX, mouseY);
        drawBarButton(graphics, x + 2, y, "gui.sbaddon.classify.view.config",
                getContainer().getView() == ClassifyUpgradeWrapper.VIEW_CONFIG, mouseX, mouseY);
    }

    private int drawBarButton(GuiGraphics graphics, int x, int y, String key, boolean active, int mouseX, int mouseY) {
        Component text = Component.translatable(key);
        int w = font.width(text) + 6;
        int color = active ? COLOR_BAR_BTN_ACTIVE : COLOR_BAR_BTN;
        graphics.fill(x, y, x + w, y + BAR_H - 1, color);
        graphics.drawString(font, text, x + 3, y + 1, 0xFFFFFFFF, false);
        viewHits.add(new Rect(x, y, w, BAR_H - 1));
        return x + w;
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        List<Group> groups = buildGroups();

        int x0 = getX() + PAD;
        int y0 = listTop();
        int w = getWidth() - PAD * 2;
        int h = getHeight() - (y0 - getY()) - PAD;

        int total = 0;
        for (Group group : groups) {
            total += HEADER_H;
            if (!getContainer().isCollapsed(group.namespace)) {
                total += boxHeight(group, w) + GROUP_GAP;
            } else {
                total += 2;
            }
        }
        scrollMax = Math.max(0, total - h);
        scrollOffset = clamp(scrollOffset, 0, scrollMax);

        if (groups.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.sbaddon.classify.empty"), x0 + 2, y0 + 2, COLOR_LABEL_DIM, false);
            return;
        }

        graphics.enableScissor(x0, y0, x0 + w, y0 + h);
        int cursor = y0 - scrollOffset;
        int cols = columns(w);
        for (Group group : groups) {
            boolean collapsed = getContainer().isCollapsed(group.namespace);

            graphics.drawString(font, group.label, x0 + 2, cursor + 1, group.fallback ? COLOR_LABEL_DIM : COLOR_LABEL, false);
            if (collapsed) {
                graphics.drawString(font, "+" + group.entries.size(), x0 + w - 16, cursor + 1, COLOR_LABEL_DIM, false);
            }
            headerHits.add(new Hit(x0, cursor, w, HEADER_H, -1, ItemStack.EMPTY, group.namespace));
            cursor += HEADER_H;

            if (collapsed) {
                cursor += 2;
                continue;
            }

            int boxH = boxHeight(group, w);
            graphics.fill(x0, cursor, x0 + w, cursor + boxH, COLOR_BOX_BORDER);
            graphics.fill(x0 + 1, cursor + 1, x0 + w - 1, cursor + boxH - 1, COLOR_BOX_FILL);

            for (int i = 0; i < group.entries.size(); i++) {
                Entry entry = group.entries.get(i);
                int cx = x0 + 2 + (i % cols) * CELL;
                int cy = cursor + 2 + (i / cols) * CELL;
                graphics.renderItem(entry.stack, cx, cy);
                graphics.renderItemDecorations(font, entry.stack, cx, cy);
                if (inside(mouseX, mouseY, cx, cy, 16, 16)) {
                    graphics.fill(cx, cy, cx + 16, cy + 16, 0x55FFFFFF);
                }
                itemHits.add(new Hit(cx, cy, 16, 16, entry.slot, entry.stack, group.namespace));
            }
            cursor += boxH + GROUP_GAP;
        }
        graphics.disableScissor();
    }

    private void renderConfig(GuiGraphics graphics, int mouseX, int mouseY) {
        int gx = gridX();
        int gy = gridY();
        int cols = ClassifyUpgradeContainer.SAMPLE_COLUMNS;
        int rows = ClassifyUpgradeContainer.SAMPLE_ROWS;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = gx + c * CELL;
                int y = gy + r * CELL;
                graphics.fill(x, y, x + 16, y + 16, COLOR_BOX_BORDER);
                graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF8B8B8B);
            }
        }

        int hintY = gy + rows * CELL + 3;
        boolean empty = getContainer().getModWhitelist().isEmpty();
        graphics.drawString(font,
                Component.translatable(empty ? "gui.sbaddon.classify.hint.empty" : "gui.sbaddon.classify.hint"),
                getX() + PAD, hintY, COLOR_LABEL_DIM, false);

        // 兜底组排序切换
        SortBy sortBy = getContainer().getFallbackSort();
        Component sortLabel = Component.translatable("gui.sbaddon.classify.sort",
                Component.translatable("gui.sbaddon.classify.sort." + sortBy.getSerializedName()));
        int y = hintY + 11;
        int w = Math.min(getWidth() - PAD * 2, font.width(sortLabel) + 6);
        int x = getX() + PAD;
        graphics.fill(x, y, x + w, y + BAR_H - 1, inside(mouseX, mouseY, x, y, w, BAR_H - 1) ? 0xFF6E8A5A : COLOR_BAR_BTN);
        graphics.drawString(font, sortLabel, x + 3, y + 1, 0xFFFFFFFF, false);
        sortHit = new Rect(x, y, w, BAR_H - 1);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 顶部「列表 / 配置」切换
        for (int i = 0; i < viewHits.size(); i++) {
            if (viewHits.get(i).contains(mouseX, mouseY)) {
                switchView(i == 0 ? ClassifyUpgradeWrapper.VIEW_LIST : ClassifyUpgradeWrapper.VIEW_CONFIG);
                return true;
            }
        }

        // 配置页的兜底排序按钮
        if (getContainer().getView() == ClassifyUpgradeWrapper.VIEW_CONFIG) {
            if (sortHit != null && sortHit.contains(mouseX, mouseY)) {
                SortBy next = getContainer().getFallbackSort().next();
                if (next == SortBy.MOD) {
                    next = next.next();
                }
                getContainer().setFallbackSort(next);
                getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.sortTag(next));
                return true;
            }
            return false;
        }

        // 组标题：折叠 / 展开
        for (Hit hit : headerHits) {
            if (hit.contains(mouseX, mouseY)) {
                getContainer().toggleCollapsed(hit.namespace);
                return true;
            }
        }

        // 物品格：完全交给菜单处理，保证服务端同步
        for (Hit hit : itemHits) {
            if (hit.contains(mouseX, mouseY)) {
                clickSlot(hit.slot, button);
                return true;
            }
        }
        return false;
    }

    private void switchView(int view) {
        getContainer().setView(view);
        getContainer().sendDataToServer(() -> ClassifyUpgradeContainer.viewTag(view));
        lastView = view;
        scrollOffset = 0;
        positionSampleSlots();
    }

    private void clickSlot(int slotIndex, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        StorageContainerMenuBase<?> menu = menu();
        if (player == null || menu == null || slotIndex < 0) {
            return;
        }
        ClickType type = (button == 0 && Screen.hasShiftDown()) ? ClickType.QUICK_MOVE : ClickType.PICKUP;
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, button, type, player);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (getContainer().getView() != ClassifyUpgradeWrapper.VIEW_LIST) {
            return false;
        }
        int x0 = getX() + PAD;
        int y0 = listTop();
        int w = getWidth() - PAD * 2;
        int h = getHeight() - (y0 - getY()) - PAD;
        if (inside((int) mouseX, (int) mouseY, x0, y0, w, h) && scrollMax > 0) {
            scrollOffset = clamp(scrollOffset - (int) Math.round(scrollY * CELL), 0, scrollMax);
            return true;
        }
        return false;
    }

    @Override
    public void renderTooltip(Screen parent, GuiGraphics graphics, int mouseX, int mouseY) {
        for (Hit hit : itemHits) {
            if (hit.contains(mouseX, mouseY) && !hit.stack.isEmpty()) {
                graphics.renderTooltip(font, hit.stack, mouseX, mouseY);
                return;
            }
        }
        super.renderTooltip(parent, graphics, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ 分组

    private List<Group> buildGroups() {
        StorageContainerMenuBase<?> menu = menu();
        if (menu == null) {
            return List.of();
        }

        ClassifyUpgradeContainer container = getContainer();
        Predicate<ItemStack> filter = screen.getStackFilter();
        List<String> whitelist = container.getModWhitelist();

        Map<String, List<Entry>> byMod = new LinkedHashMap<>();
        for (String namespace : whitelist) {
            byMod.put(namespace, new ArrayList<>());
        }
        List<Entry> fallback = new ArrayList<>();

        int storageSlots = menu.getNumberOfStorageInventorySlots();
        for (int i = 0; i < storageSlots; i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || (filter != null && !filter.test(stack))) {
                continue;
            }
            String namespace = ModNames.namespaceOf(stack);
            List<Entry> target = byMod.get(namespace);
            if (target != null) {
                target.add(new Entry(slot.index, stack));
            } else {
                fallback.add(new Entry(slot.index, stack));
            }
        }

        fallback.sort(comparator(container.getFallbackSort()));

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> entry : byMod.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            groups.add(new Group(entry.getKey(), ModNames.displayName(entry.getKey()), entry.getValue(), false));
        }
        if (!fallback.isEmpty()) {
            groups.add(new Group("", Component.translatable("gui.sbaddon.classify.fallback"), fallback, true));
        }
        return groups;
    }

    private Comparator<Entry> comparator(SortBy sortBy) {
        Comparator<Entry> byName = Comparator.comparing(e -> e.stack.getHoverName().getString());
        return switch (sortBy) {
            case COUNT -> Comparator.<Entry>comparingInt(e -> -e.stack.getCount()).thenComparing(byName);
            case TAGS -> Comparator.<Entry, String>comparing(e -> {
                var tags = e.stack.getTags().map(Object::toString).sorted().findFirst();
                return tags.orElse("");
            }).thenComparing(byName);
            default -> byName;
        };
    }

    // ------------------------------------------------------------------ 小工具

    private StorageContainerMenuBase<?> menu() {
        Player player = getContainer().getPlayer();
        return player.containerMenu instanceof StorageContainerMenuBase<?> menu ? menu : null;
    }

    private int listTop() {
        return getY() + TITLE_H + BAR_H + 2;
    }

    private int gridX() {
        return getX() + PAD + 1;
    }

    private int gridY() {
        return listTop() + 1;
    }

    private int columns(int width) {
        return Math.max(1, (width - 4) / CELL);
    }

    private int boxHeight(Group group, int width) {
        int cols = columns(width);
        int rows = (group.entries.size() + cols - 1) / cols;
        return rows * CELL + 4;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean inside(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private record Entry(int slot, ItemStack stack) {
    }

    private record Group(String namespace, Component label, List<Entry> entries, boolean fallback) {
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Hit(int x, int y, int w, int h, int slot, ItemStack stack, String namespace) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
