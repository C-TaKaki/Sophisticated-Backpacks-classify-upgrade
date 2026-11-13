package com.huanj.sbaddon.compat.fxntstorage.mixin;

import com.huanj.sbaddon.client.BannerBackground;
import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.client.layout.ClassifySource;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyData;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyMenu;
import com.huanj.sbaddon.compat.fxntstorage.client.FxntClassifySource;
import com.huanj.sbaddon.compat.fxntstorage.net.FxntClassifyAction;
import com.huanj.sbaddon.mixin.ScreenAccess;
import net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu;
import net.fxnt.fxntstorage.backpack.client.menu.BackpackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FXNT Storage 背包界面的分类布局与配置面板。
 * <p>
 * 与精妙背包那边同一个思路：只改客户端的 {@code Slot.x/y}，不碰菜单数据 ——
 * 渲染、悬停、点击、Shift 点击全部自动跟随坐标。
 * <p>
 * 宿主差异（全部在这一个类里收口）：
 * <ul>
 *   <li>物品区是 12 列 × containerRows 行，槽位原点固定为 (30, 18)，不需要像 SB 那样动态推基准；</li>
 *   <li>FXNT 的格子底图画在界面贴图里，重排后必然错位 —— 所以整块区域先用格子底色盖掉，再按新布局逐格描边；</li>
 *   <li>FXNT 的页签系统由 {@code UpgradeType} 枚举驱动，外部模组无法注册 —— 所以自己的页签按钮和面板全部自绘，
 *       数据走独立的 C2S 数据包（{@link FxntClassifyAction}）。</li>
 * </ul>
 */
@Mixin(BackpackScreen.class)
public abstract class BackpackScreenMixin {

    // ---------------------------------------------------------------- 布局常量

    @Unique
    private static final int SBADDON_CELL = 18;
    @Unique
    private static final int SBADDON_COLS = 12;
    /** FXNT 物品槽坐标 = 29 + 1（内缩 1px），首行 y = 17 + 1。 */
    @Unique
    private static final int SBADDON_ORIGIN_X = 30;
    @Unique
    private static final int SBADDON_ORIGIN_Y = 18;
    @Unique
    private static final int SBADDON_HIDDEN = -10000;

    // ---------------------------------------------------------------- 面板常量

    @Unique
    private static final int SBADDON_PANEL_W = 150;
    @Unique
    private static final int SBADDON_HEADER_H = 18;
    @Unique
    private static final int SBADDON_ROW_H = 20;
    @Unique
    private static final int SBADDON_ROWS_VISIBLE = 6;
    @Unique
    private static final int SBADDON_FOOTER_H = 18;
    @Unique
    private static final int SBADDON_PANEL_H =
            SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H + SBADDON_FOOTER_H;
    @Unique
    private static final int SBADDON_PAD = 4;
    @Unique
    private static final int SBADDON_BTN = 9;
    @Unique
    private static final int SBADDON_AUTO_W = 60;

    @Unique
    private static final int SBADDON_COLOR_PANEL = 0xFFC6C6C6;
    @Unique
    private static final int SBADDON_COLOR_BORDER = 0xFF373737;
    @Unique
    private static final int SBADDON_COLOR_SLOT = 0xFF8B8B8B;
    @Unique
    private static final int SBADDON_COLOR_ROW = 0xFFADADAD;
    @Unique
    private static final int SBADDON_COLOR_TEXT = 0xFF1E1E1E;
    @Unique
    private static final int SBADDON_COLOR_TEXT_DIM = 0xFF5A5A5A;

    // ---------------------------------------------------------------- 状态

    @Unique
    private List<ClassifyLayout.Banner> sbaddon$banners = List.of();
    @Unique
    private List<ClassifyLayout.Placement> sbaddon$placements = List.of();
    @Unique
    private int sbaddon$scroll;
    @Unique
    private int sbaddon$maxScroll;
    @Unique
    private boolean sbaddon$applied;

    @Unique
    private FxntClassifyData.Snapshot sbaddon$snapshot = FxntClassifyData.Snapshot.empty();

    @Unique
    private boolean sbaddon$panelOpen;
    @Unique
    private int sbaddon$panelScroll;
    @Unique
    private int sbaddon$editingRow = -1;
    /** 改名用的输入框。1.21.1 的 Screen 没有 charTyped 可注入，只能挂一个真的 EditBox 让控件自己收字。 */
    @Unique
    private EditBox sbaddon$editBox;

    // ---------------------------------------------------------------- 影子成员

    /** FXNT 的页签列表，只用来算「我们的页签排在第几个」。 */
    @Shadow
    @Final
    private List<?> upgradePanels;

    /** 当前界面配置能显示的行数（4 / 7 / 9 档里实际用于物品区的行数）。 */
    @Shadow
    private int containerRows;

    @Shadow
    private int topVisibleRow;

    @Shadow
    protected abstract void initializeItemSlots();

    @Shadow
    protected abstract void setTopRowAndMoveThumb(int oldTopRow, int newTopRow);

    // ---------------------------------------------------------------- 布局

    @Inject(method = "render", at = @At("HEAD"))
    private void sbaddon$applyLayout(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                     CallbackInfo ci) {
        // 每帧先清空：升级被拿掉时这里提前 return，留着上一帧的布局会让横幅/格子残留
        sbaddon$banners = List.of();
        sbaddon$placements = List.of();
        sbaddon$maxScroll = 0;

        BackpackScreen self = sbaddon$self();
        BackpackMenu menu = self.getMenu();
        Slot upgradeSlot = FxntClassifyMenu.findUpgradeSlot(menu);
        if (upgradeSlot == null) {
            sbaddon$snapshot = FxntClassifyData.Snapshot.empty();
            if (sbaddon$applied) {
                // 升级被拿掉：把槽位还给 FXNT，恢复原版网格
                sbaddon$applied = false;
                sbaddon$scroll = 0;
                sbaddon$panelOpen = false;
                sbaddon$stopEditing(false);
                initializeItemSlots();
                setTopRowAndMoveThumb(0, topVisibleRow);
            }
            return;
        }

        int rows = Math.max(1, containerRows);
        HolderLookup.Provider registries = sbaddon$registries();
        sbaddon$snapshot = FxntClassifySource.snapshot(upgradeSlot.getItem(), registries);

        ClassifySource source = new FxntClassifySource(menu, sbaddon$snapshot.samples(),
                sbaddon$snapshot.names());
        ClassifyLayout.Result result = ClassifyLayout.compute(
                source, SBADDON_COLS, rows, sbaddon$scroll, null, SBADDON_ORIGIN_X, SBADDON_ORIGIN_Y);

        sbaddon$maxScroll = Math.max(0, result.totalRows() - rows);
        if (sbaddon$scroll > sbaddon$maxScroll) {
            sbaddon$scroll = sbaddon$maxScroll;
            result = ClassifyLayout.compute(
                    source, SBADDON_COLS, rows, sbaddon$scroll, null, SBADDON_ORIGIN_X, SBADDON_ORIGIN_Y);
        }

        int slotCount = menu.layout.items().getCount();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = menu.getSlot(i);
            slot.x = SBADDON_HIDDEN;
            slot.y = SBADDON_HIDDEN;
        }
        for (ClassifyLayout.Placement placement : result.placements()) {
            Slot slot = menu.getSlot(placement.slotIndex());
            slot.x = placement.x();
            slot.y = placement.y();
        }

        sbaddon$banners = result.banners();
        sbaddon$placements = result.placements();
        sbaddon$applied = true;
    }

    /** 面板列表一屏装不下时，滚动位置要跟着条目数收敛。 */
    @Unique
    private void sbaddon$clampPanelScroll() {
        int max = Math.max(0, sbaddon$snapshot.samples().size() - SBADDON_ROWS_VISIBLE);
        if (sbaddon$panelScroll > max) {
            sbaddon$panelScroll = max;
        }
        if (sbaddon$panelScroll < 0) {
            sbaddon$panelScroll = 0;
        }
    }

    // ---------------------------------------------------------------- 绘制

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void sbaddon$drawClassify(GuiGraphics graphics, float delta, int mouseX, int mouseY,
                                      CallbackInfo ci) {
        if (!sbaddon$applied) {
            return;
        }
        BackpackScreen self = sbaddon$self();
        int left = self.getGuiLeft();
        int top = self.getGuiTop();
        int rows = Math.max(1, containerRows);

        // 1) 盖掉贴图里画死的那片格子底（重排后必然错位）
        int areaX = left + SBADDON_ORIGIN_X;
        int areaY = top + SBADDON_ORIGIN_Y - 1;
        graphics.fill(areaX, areaY, areaX + SBADDON_COLS * SBADDON_CELL,
                areaY + rows * SBADDON_CELL, SBADDON_COLOR_SLOT);

        // 2) 按新布局逐格补边框（只在邻居格不存在时画，避免相邻格出现 2px 粗线）
        sbaddon$drawCellBorders(graphics, left, top);

        // 3) 整行分类横幅
        sbaddon$drawBanners(graphics, left, top);

        // 4) 配置面板
        sbaddon$drawPanel(graphics, mouseX, mouseY);
    }

    @Unique
    private void sbaddon$drawCellBorders(GuiGraphics graphics, int left, int top) {
        Set<Long> cells = new HashSet<>();
        for (ClassifyLayout.Placement placement : sbaddon$placements) {
            cells.add(sbaddon$cellKey(placement.x(), placement.y()));
        }
        for (ClassifyLayout.Placement placement : sbaddon$placements) {
            int x = left + placement.x();
            int y = top + placement.y();
            if (!cells.contains(sbaddon$cellKey(placement.x(), placement.y() - SBADDON_CELL))) {
                graphics.fill(x, y, x + SBADDON_CELL, y + 1, SBADDON_COLOR_BORDER);
            }
            if (!cells.contains(sbaddon$cellKey(placement.x(), placement.y() + SBADDON_CELL))) {
                graphics.fill(x, y + SBADDON_CELL - 1, x + SBADDON_CELL, y + SBADDON_CELL,
                        SBADDON_COLOR_BORDER);
            }
            if (!cells.contains(sbaddon$cellKey(placement.x() - SBADDON_CELL, placement.y()))) {
                graphics.fill(x, y, x + 1, y + SBADDON_CELL, SBADDON_COLOR_BORDER);
            }
            if (!cells.contains(sbaddon$cellKey(placement.x() + SBADDON_CELL, placement.y()))) {
                graphics.fill(x + SBADDON_CELL - 1, y, x + SBADDON_CELL, y + SBADDON_CELL,
                        SBADDON_COLOR_BORDER);
            }
        }
    }

    @Unique
    private static long sbaddon$cellKey(int x, int y) {
        return ((long) (x / SBADDON_CELL) << 32) ^ (y / SBADDON_CELL);
    }

    @Unique
    private void sbaddon$drawBanners(GuiGraphics graphics, int left, int top) {
        if (sbaddon$banners.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (ClassifyLayout.Banner banner : sbaddon$banners) {
            int x = left + banner.x() - 1;
            int y = top + banner.y() - 1;
            int right = x + banner.width();
            int boxH = ClassifyLayout.CELL - ClassifyLayout.BANNER_INSET * 2;
            BannerBackground.render(graphics, x, y, banner.width(), boxH);

            if (!banner.icon().isEmpty()) {
                float iconSize = 16.0f * 0.9f;
                float offset = (ClassifyLayout.CELL - iconSize) / 2.0f + 4;
                graphics.pose().pushPose();
                graphics.pose().translate(x + offset, y + (ClassifyLayout.CELL - iconSize) / 2.0f, 0.0f);
                graphics.pose().scale(0.9f, 0.9f, 1.0f);
                graphics.renderItem(banner.icon(), 0, 0);
                graphics.pose().popPose();
            }

            String label = banner.label().getString();
            int textLeft = x + 20 + 4;
            int textWidth = Math.max(0, right - textLeft - 2);
            if (font.width(label) > textWidth) {
                label = font.plainSubstrByWidth(label, Math.max(0, textWidth - font.width("..."))) + "...";
            }
            graphics.drawString(font, label, textLeft, y + 5, 0xFF1D2433, false);
        }
    }

    // ---------------------------------------------------------------- 面板

    @Unique
    private int sbaddon$panelLeft() {
        BackpackScreen self = sbaddon$self();
        return self.getGuiLeft() + self.getXSize();
    }

    @Unique
    private int sbaddon$tabTop() {
        return sbaddon$self().getGuiTop() + 5 + 24 * upgradePanels.size();
    }

    @Unique
    private int sbaddon$panelTop() {
        return sbaddon$tabTop() - 2;
    }

    @Unique
    private void sbaddon$drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();
        int panelTop = sbaddon$panelTop();

        if (!sbaddon$panelOpen) {
            // 收起态：一个 20×20 的小页签，画升级物品图标
            sbaddon$drawBox(graphics, panelLeft, tabTop, 20, 20);
            ItemStack icon = new ItemStack(com.huanj.sbaddon.compat.fxntstorage.FxntCompatContent
                    .CLASSIFY_UPGRADE.get());
            graphics.renderItem(icon, panelLeft + 2, tabTop + 2);
            return;
        }

        sbaddon$drawBox(graphics, panelLeft, panelTop, SBADDON_PANEL_W, SBADDON_PANEL_H);

        graphics.drawString(font, Component.translatable("gui.sbaddon.classify.title"),
                panelLeft + 5, panelTop + 5, SBADDON_COLOR_TEXT, false);

        List<ItemStack> samples = sbaddon$snapshot.samples();
        Map<String, String> names = sbaddon$snapshot.names();
        sbaddon$clampPanelScroll();

        int rowX = panelLeft + SBADDON_PAD;
        int rowW = SBADDON_PANEL_W - SBADDON_PAD * 2;
        int rowAreaTop = panelTop + SBADDON_HEADER_H;

        for (int i = 0; i < SBADDON_ROWS_VISIBLE; i++) {
            int index = sbaddon$panelScroll + i;
            int ry = rowAreaTop + i * SBADDON_ROW_H;
            graphics.fill(rowX, ry, rowX + rowW, ry + SBADDON_ROW_H - 2, SBADDON_COLOR_ROW);

            if (index >= samples.size()) {
                if (index == 0 && sbaddon$editingRow < 0) {
                    graphics.drawString(font, Component.translatable("gui.sbaddon.classify.empty.hint"),
                            rowX + 4, ry + 6, SBADDON_COLOR_TEXT_DIM, false);
                }
                continue;
            }

            ItemStack sample = samples.get(index);
            ClassifyGroups.Row row = ClassifyGroups.describe(sample, names);

            // 图标位：直接画模组标签页的图标，和背包里其他分类横幅保持一致
            graphics.fill(rowX + 1, ry + 1, rowX + 17, ry + 17, SBADDON_COLOR_SLOT);
            graphics.renderItem(row.icon(), rowX + 1, ry + 1);

            int btnY = ry + 6;
            int crossX = rowX + rowW - SBADDON_BTN;
            int downX = crossX - SBADDON_BTN - 2;
            int upX = downX - SBADDON_BTN - 2;

            String label = row.label().getString();
            int labelWidth = Math.max(0, upX - (rowX + 22) - 2);
            if (font.width(label) > labelWidth) {
                label = font.plainSubstrByWidth(label, Math.max(0, labelWidth - font.width("..."))) + "...";
            }
            if (sbaddon$editingRow != index) {
                // 正在改名时文字由挂上去的 EditBox 自己画，这里让位
                graphics.drawString(font, label, rowX + 22, btnY + 1, SBADDON_COLOR_TEXT, false);
            }

            sbaddon$drawRowButton(graphics, font, upX, btnY, "^", mouseX, mouseY);
            sbaddon$drawRowButton(graphics, font, downX, btnY, "v", mouseX, mouseY);
            sbaddon$drawRowButton(graphics, font, crossX, btnY, "x", mouseX, mouseY);
        }

        // 底部：一键分类
        int footerY = panelTop + SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
        int autoX = rowX;
        boolean hovered = sbaddon$inside(mouseX, mouseY, autoX, footerY + 2, SBADDON_AUTO_W, 14);
        graphics.fill(autoX, footerY + 2, autoX + SBADDON_AUTO_W, footerY + 16,
                hovered ? 0xFF8E8E8E : SBADDON_COLOR_SLOT);
        graphics.drawString(font, Component.translatable("gui.sbaddon.classify.auto"),
                autoX + 4, footerY + 5, SBADDON_COLOR_TEXT, false);

        // 列表滚动条
        int total = samples.size();
        if (total > SBADDON_ROWS_VISIBLE) {
            int trackX = panelLeft + SBADDON_PANEL_W - 3;
            int trackTop = rowAreaTop;
            int trackH = SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
            graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackH, SBADDON_COLOR_BORDER);
            int thumbH = Math.max(8, trackH * SBADDON_ROWS_VISIBLE / total);
            int thumbY = trackTop + (trackH - thumbH) * sbaddon$panelScroll / (total - SBADDON_ROWS_VISIBLE);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, SBADDON_COLOR_PANEL);
        }
    }

    @Unique
    private void sbaddon$drawBox(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, SBADDON_COLOR_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, SBADDON_COLOR_PANEL);
    }

    @Unique
    private void sbaddon$drawRowButton(GuiGraphics graphics, Font font, int x, int y, String glyph,
                                       int mouseX, int mouseY) {
        boolean hovered = sbaddon$inside(mouseX, mouseY, x, y, SBADDON_BTN, SBADDON_BTN);
        graphics.fill(x, y, x + SBADDON_BTN, y + SBADDON_BTN, hovered ? 0xFF6E6E6E : 0xFF9A9A9A);
        graphics.fill(x, y, x + SBADDON_BTN, y + 1, SBADDON_COLOR_BORDER);
        graphics.drawString(font, glyph, x + 2, y + 1, 0xFFFFFFFF, false);
    }

    @Unique
    private static boolean sbaddon$inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ---------------------------------------------------------------- 输入

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!sbaddon$applied || button != 0) {
            return;
        }
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();

        // 页签按钮
        if (sbaddon$inside(mouseX, mouseY, panelLeft, tabTop, 20, 20)) {
            sbaddon$panelOpen = !sbaddon$panelOpen;
            sbaddon$stopEditing(false);
            cir.setReturnValue(true);
            return;
        }

        if (!sbaddon$panelOpen) {
            return;
        }
        int panelTop = sbaddon$panelTop();
        if (!sbaddon$inside(mouseX, mouseY, panelLeft, panelTop, SBADDON_PANEL_W, SBADDON_PANEL_H)) {
            return;
        }

        // 面板内的一切点击都吃掉，避免穿透到下面的槽位
        cir.setReturnValue(true);

        int rowX = panelLeft + SBADDON_PAD;
        int rowW = SBADDON_PANEL_W - SBADDON_PAD * 2;
        int rowAreaTop = panelTop + SBADDON_HEADER_H;
        List<ItemStack> samples = sbaddon$snapshot.samples();

        int slot = (int) ((mouseY - rowAreaTop) / SBADDON_ROW_H);
        if (slot >= 0 && slot < SBADDON_ROWS_VISIBLE) {
            int index = sbaddon$panelScroll + slot;
            int ry = rowAreaTop + slot * SBADDON_ROW_H;
            int btnY = ry + 6;
            int crossX = rowX + rowW - SBADDON_BTN;
            int downX = crossX - SBADDON_BTN - 2;
            int upX = downX - SBADDON_BTN - 2;

            if (sbaddon$inside(mouseX, mouseY, crossX, btnY, SBADDON_BTN, SBADDON_BTN)) {
                if (index < samples.size()) {
                    sbaddon$send(FxntClassifyAction.remove(index));
                    sbaddon$editingRow = -1;
                }
                return;
            }
            if (sbaddon$inside(mouseX, mouseY, downX, btnY, SBADDON_BTN, SBADDON_BTN)) {
                if (index < samples.size()) {
                    sbaddon$send(FxntClassifyAction.move(index, 1));
                }
                return;
            }
            if (sbaddon$inside(mouseX, mouseY, upX, btnY, SBADDON_BTN, SBADDON_BTN)) {
                if (index < samples.size()) {
                    sbaddon$send(FxntClassifyAction.move(index, -1));
                }
                return;
            }
            if (index < samples.size() && mouseX >= rowX + 20 && mouseX < upX - 2) {
                // 点名字 → 就地改名（挂一个 EditBox 盖在文字上，字由它自己收）
                sbaddon$startRename(index, rowX + 20, ry + 2, Math.max(40, upX - rowX - 22),
                        SBADDON_ROW_H - 6, samples.get(index));
                return;
            }
            if (index < samples.size()) {
                sbaddon$stopEditing(false);
            }
        }

        int footerY = panelTop + SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
        if (sbaddon$inside(mouseX, mouseY, rowX, footerY + 2, SBADDON_AUTO_W, 14)) {
            sbaddon$send(FxntClassifyAction.auto());
            sbaddon$stopEditing(false);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!sbaddon$applied) {
            return;
        }
        int delta = scrollY > 0 ? -1 : 1;
        int panelLeft = sbaddon$panelLeft();
        int panelTop = sbaddon$panelTop();

        if (sbaddon$panelOpen && sbaddon$inside(mouseX, mouseY, panelLeft, panelTop,
                SBADDON_PANEL_W, SBADDON_PANEL_H)) {
            sbaddon$panelScroll += delta;
            sbaddon$clampPanelScroll();
            cir.setReturnValue(true);
            return;
        }

        BackpackScreen self = sbaddon$self();
        if (sbaddon$maxScroll > 0 && sbaddon$inside(mouseX, mouseY,
                self.getGuiLeft() + SBADDON_ORIGIN_X, self.getGuiTop() + SBADDON_ORIGIN_Y,
                SBADDON_COLS * SBADDON_CELL, Math.max(1, containerRows) * SBADDON_CELL)) {
            sbaddon$scroll = Math.max(0, Math.min(sbaddon$maxScroll, sbaddon$scroll + delta));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderScrollbar", at = @At("HEAD"), cancellable = true)
    private void sbaddon$hideScrollbar(GuiGraphics graphics, CallbackInfo ci) {
        // 分类视图自己按行滚动，FXNT 那个基于行号的滚动条会显示错误位置，直接不画
        if (sbaddon$applied) {
            ci.cancel();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sbaddon$keyPressed(int keyCode, int scanCode, int modifiers,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (sbaddon$editingRow < 0 || sbaddon$editBox == null) {
            return;
        }
        // 界面 resize 会重建控件列表，输入框就不在了：当作取消
        if (!sbaddon$self().children().contains(sbaddon$editBox)) {
            sbaddon$stopEditing(false);
            return;
        }
        if (keyCode == 257 || keyCode == 335) {
            sbaddon$stopEditing(true);
        } else if (keyCode == 256) {
            sbaddon$stopEditing(false);
        } else {
            // 退格、方向键、Delete 这些交给输入框自己处理；
            // 其余按键一律吞掉，免得边打字边触发背包热键
            sbaddon$editBox.keyPressed(keyCode, scanCode, modifiers);
        }
        cir.setReturnValue(true);
    }

    /** 打字本身由 EditBox 通过焦点链收到，这里不需要再接管 charTyped。 */

    @Unique
    private void sbaddon$startRename(int row, int x, int y, int w, int h, ItemStack sample) {
        sbaddon$stopEditing(false);
        String id = ClassifyGroups.groupId(sample);
        String custom = id == null ? null : sbaddon$snapshot.names().get(id);

        Font font = Minecraft.getInstance().font;
        EditBox box = new EditBox(font, x, y, w, h, Component.translatable("gui.sbaddon.classify.title"));
        box.setMaxLength(32);
        box.setBordered(true);
        box.setValue(custom == null ? "" : custom);
        box.setFocused(true);
        sbaddon$editBox = ((ScreenAccess) sbaddon$self()).sbaddon$addRenderableWidget(box);
        ((ContainerEventHandler) sbaddon$self()).setFocused(sbaddon$editBox);
        sbaddon$editingRow = row;
    }

    @Unique
    private void sbaddon$stopEditing(boolean commit) {
        EditBox box = sbaddon$editBox;
        int row = sbaddon$editingRow;
        sbaddon$editBox = null;
        sbaddon$editingRow = -1;

        if (box != null) {
            ((ScreenAccess) sbaddon$self()).sbaddon$removeWidget(box);
            ((ContainerEventHandler) sbaddon$self()).setFocused(null);
        }
        if (!commit || box == null || row < 0) {
            return;
        }
        List<ItemStack> samples = sbaddon$snapshot.samples();
        if (row >= samples.size()) {
            return;
        }
        String id = ClassifyGroups.groupId(samples.get(row));
        if (id == null) {
            return;
        }
        sbaddon$send(FxntClassifyAction.rename(id, box.getValue()));
    }

    /** 面板在界面外，得加进排除区，否则点一下就把背包关掉了。 */
    @Inject(method = "getExclusionZones", at = @At("RETURN"), cancellable = true)
    private void sbaddon$exclusionZones(CallbackInfoReturnable<List<Rect2i>> cir) {
        if (!sbaddon$applied) {
            return;
        }
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();
        List<Rect2i> zones = new ArrayList<>(cir.getReturnValue());
        zones.add(new Rect2i(panelLeft, tabTop, 20, 20));
        if (sbaddon$panelOpen) {
            zones.add(new Rect2i(panelLeft, sbaddon$panelTop(), SBADDON_PANEL_W, SBADDON_PANEL_H));
        }
        cir.setReturnValue(zones);
    }

    // ---------------------------------------------------------------- 工具

    @Unique
    private BackpackScreen sbaddon$self() {
        return (BackpackScreen) (Object) this;
    }

    @Unique
    private static HolderLookup.Provider sbaddon$registries() {
        return Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.registryAccess();
    }

    @Unique
    private static void sbaddon$send(FxntClassifyAction payload) {
        PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
    }
}
