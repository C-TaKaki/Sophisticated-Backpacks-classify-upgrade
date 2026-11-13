package com.huanj.sbaddon.compat.fxntstorage.mixin;

import com.huanj.sbaddon.client.BannerBackground;
import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.client.layout.ClassifySource;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyData;
import com.huanj.sbaddon.compat.fxntstorage.FxntClassifyMenu;
import com.huanj.sbaddon.compat.fxntstorage.FxntCompatContent;
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
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.GuiHelper;
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
    private static final int SBADDON_HEADER_H = 22;
    @Unique
    private static final int SBADDON_ROW_H = 20;
    @Unique
    private static final int SBADDON_ROWS_VISIBLE = 6;
    @Unique
    private static final int SBADDON_FOOTER_H = 20;
    @Unique
    private static final int SBADDON_PANEL_H =
            SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H + SBADDON_FOOTER_H;
    @Unique
    private static final int SBADDON_PAD = 4;
    @Unique
    private static final int SBADDON_BTN = 9;
    @Unique
    private static final int SBADDON_AUTO_W = 44;
    @Unique
    private static final int SBADDON_AUTO_H = 12;

    @Unique
    private static final int SBADDON_COLOR_PANEL = 0xFFC6C6C6;
    @Unique
    private static final int SBADDON_COLOR_BORDER = 0xFF373737;
    @Unique
    private static final int SBADDON_COLOR_SLOT = 0xFF8B8B8B;
    @Unique
    private static final int SBADDON_COLOR_ROW = 0xFF8E8E8E;
    @Unique
    private static final int SBADDON_COLOR_ROW_HOVER = 0xFF9E9E9E;
    @Unique
    private static final int SBADDON_COLOR_ROW_HL = 0xFFB8B8B8;
    @Unique
    private static final int SBADDON_COLOR_ROW_SH = 0xFF5E5E5E;
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
                sbaddon$restoreVanillaSlots(menu);
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

    /**
     * 把物品槽摆回 FXNT 的原始位置。
     * <p>
     * 不走它的 {@code initializeItemSlots()}（私有方法，mixin 影子调用虽然可行但多一层风险）：
     * 公式在 {@code BackpackScreen#initializeItemSlots} 里是固定的
     * {@code x = 29 + col * 18 + 1}、{@code y = 17 + row * 18 + 1}，滚出可视区的行置 -2000。
     */
    @Unique
    private void sbaddon$restoreVanillaSlots(BackpackMenu menu) {
        int rows = Math.max(1, containerRows);
        int slots = menu.layout.items().getCount();
        for (int i = 0; i < slots; i++) {
            Slot slot = menu.getSlot(i);
            int row = i / SBADDON_COLS - topVisibleRow;
            if (row < 0 || row >= rows) {
                slot.x = SBADDON_ORIGIN_X;
                slot.y = -2000;
            } else {
                slot.x = SBADDON_ORIGIN_X + (i % SBADDON_COLS) * SBADDON_CELL;
                slot.y = SBADDON_ORIGIN_Y + row * SBADDON_CELL;
            }
        }
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

    /**
     * 面板绘制。视觉照抄精妙背包那套分类页：标题栏带图标格、每条是凸起立体行
     * （顶 1px 高光 / 底 1px 阴影）、行尾三个深色字形按钮、底部固定行放绿色「一键分类」。
     */
    @Unique
    private void sbaddon$drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();
        int panelTop = sbaddon$panelTop();
        ItemStack upgradeIcon = new ItemStack(FxntCompatContent.CLASSIFY_UPGRADE.get());

        if (!sbaddon$panelOpen) {
            // 收起态：一个凸起的小页签，里面是升级物品图标
            sbaddon$drawRaised(graphics, panelLeft, tabTop, 20, 20, false);
            GuiHelper.renderSlotsBackground(graphics, panelLeft + 1, tabTop + 1, 1, 1);
            graphics.renderItem(upgradeIcon, panelLeft + 2, tabTop + 2);
            return;
        }

        sbaddon$drawBox(graphics, panelLeft, panelTop, SBADDON_PANEL_W, SBADDON_PANEL_H);

        // 标题栏：图标格 + 文字
        int iconX = panelLeft + SBADDON_PAD;
        int iconY = panelTop + 3;
        GuiHelper.renderSlotsBackground(graphics, iconX, iconY, 1, 1);
        graphics.renderItem(upgradeIcon, iconX + 1, iconY + 1);
        graphics.drawString(font, Component.translatable("gui.sbaddon.classify.title"),
                iconX + SBADDON_CELL + 2, panelTop + 6, SBADDON_COLOR_TEXT, false);

        List<ItemStack> samples = sbaddon$snapshot.samples();
        Map<String, String> names = sbaddon$snapshot.names();
        sbaddon$clampPanelScroll();

        int rowX = panelLeft + SBADDON_PAD;
        int rowW = SBADDON_PANEL_W - SBADDON_PAD * 2;
        int rowAreaTop = panelTop + SBADDON_HEADER_H;
        int rowH = SBADDON_ROW_H - 2;

        for (int i = 0; i < SBADDON_ROWS_VISIBLE; i++) {
            int index = sbaddon$panelScroll + i;
            if (index >= samples.size()) {
                break;
            }
            int ry = rowAreaTop + i * SBADDON_ROW_H;
            boolean hovered = sbaddon$inside(mouseX, mouseY, rowX, ry, rowW, rowH);
            sbaddon$drawRowFace(graphics, rowX, ry, rowW, rowH, hovered);

            ClassifyGroups.Row row = ClassifyGroups.describe(samples.get(index), names);

            // 图标格：和精妙背包设置页同一个槽位底图，物品正好落在凹槽里
            int cellX = rowX + 1;
            int cellY = ry + 1;
            GuiHelper.renderSlotsBackground(graphics, cellX, cellY, 1, 1);
            graphics.renderItem(row.icon(), cellX + 1, cellY + 1);

            int crossX = rowX + rowW - SBADDON_BTN;
            int downX = crossX - SBADDON_BTN - 2;
            int upX = downX - SBADDON_BTN - 2;

            String label = sbaddon$truncate(row.label().getString(),
                    Math.max(0, upX - (cellX + SBADDON_CELL + 2) - 2));
            if (sbaddon$editingRow != index) {
                // 正在改名时文字由挂上去的 EditBox 自己画，这里让位
                graphics.drawString(font, label, cellX + SBADDON_CELL + 2, ry + 5,
                        SBADDON_COLOR_TEXT, false);
            }

            int btnY = ry + (rowH - SBADDON_BTN) / 2;
            sbaddon$drawArrow(graphics, upX, btnY, true, sbaddon$inside(mouseX, mouseY, upX, btnY, SBADDON_BTN, SBADDON_BTN));
            sbaddon$drawArrow(graphics, downX, btnY, false, sbaddon$inside(mouseX, mouseY, downX, btnY, SBADDON_BTN, SBADDON_BTN));
            sbaddon$drawCross(graphics, crossX, btnY, sbaddon$inside(mouseX, mouseY, crossX, btnY, SBADDON_BTN, SBADDON_BTN));
        }

        // 底部固定行：空槽 + 提示 + 绿色「一键分类」（和精妙背包的空行同款结构）
        int footerTop = panelTop + SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
        sbaddon$drawRowFace(graphics, rowX, footerTop, rowW, rowH, false);
        GuiHelper.renderSlotsBackground(graphics, rowX + 1, footerTop + 1, 1, 1);
        int autoX = rowX + rowW - SBADDON_AUTO_W - 6;
        int autoY = footerTop + (rowH - SBADDON_AUTO_H) / 2;
        if (sbaddon$editingRow < 0) {
            // 这一格只是"还能再加分类"的提示位：FXNT 没有样本槽，加不出幽灵拖放，
            // 所以文字如实说明是点右边的按钮扫描，不做假的"拖入"提示
            String hint = sbaddon$truncate(
                    Component.translatable("gui.sbaddon.classify.empty.hint").getString(),
                    Math.max(0, autoX - (rowX + SBADDON_CELL + 4) - 4));
            graphics.drawString(font, hint, rowX + SBADDON_CELL + 4, footerTop + 5,
                    SBADDON_COLOR_TEXT_DIM, false);
        }
        boolean autoHovered = sbaddon$inside(mouseX, mouseY, autoX, autoY, SBADDON_AUTO_W, SBADDON_AUTO_H);
        graphics.fill(autoX, autoY, autoX + SBADDON_AUTO_W, autoY + SBADDON_AUTO_H,
                autoHovered ? 0xFF6F8F5A : 0xFF5D7A49);
        graphics.fill(autoX, autoY, autoX + SBADDON_AUTO_W, autoY + 1, 0xFF9DBB85);
        graphics.fill(autoX, autoY + SBADDON_AUTO_H - 1, autoX + SBADDON_AUTO_W,
                autoY + SBADDON_AUTO_H, 0xFF3E5432);
        String autoLabel = Component.translatable("gui.sbaddon.classify.auto").getString();
        graphics.drawString(font, autoLabel,
                autoX + (SBADDON_AUTO_W - font.width(autoLabel)) / 2, autoY + 2, 0xFFF2F2F2, false);

        // 列表滚动条
        int total = samples.size();
        if (total > SBADDON_ROWS_VISIBLE) {
            int trackX = panelLeft + SBADDON_PANEL_W - 4;
            int trackH = SBADDON_ROWS_VISIBLE * SBADDON_ROW_H - 2;
            graphics.fill(trackX, rowAreaTop, trackX + 3, rowAreaTop + trackH, 0xFF6A6A6A);
            int thumbH = Math.max(10, trackH * SBADDON_ROWS_VISIBLE / total);
            int maxScroll = total - SBADDON_ROWS_VISIBLE;
            int thumbY = rowAreaTop + (trackH - thumbH) * Math.min(sbaddon$panelScroll, maxScroll)
                    / Math.max(1, maxScroll);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFD8D8D8);
        }
    }

    @Unique
    private void sbaddon$drawBox(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, SBADDON_COLOR_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, SBADDON_COLOR_PANEL);
    }

    /** 一行：底色 + 顶部高光 + 底部阴影，和精妙背包分类页的行完全一致。 */
    @Unique
    private void sbaddon$drawRowFace(GuiGraphics graphics, int x, int y, int w, int h, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFF9E9E9E : 0xFF8E8E8E);
        graphics.fill(x, y, x + w, y + 1, 0xFFB8B8B8);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF5E5E5E);
    }

    /** 凸起方块（收起态页签用）。 */
    @Unique
    private void sbaddon$drawRaised(GuiGraphics graphics, int x, int y, int w, int h, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFFB0B0B0 : 0xFFC6C6C6);
        graphics.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF555555);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF555555);
    }

    /** 行尾「上移 / 下移」：一个小三角，用精妙背包手柄同款的深灰色。 */
    @Unique
    private void sbaddon$drawArrow(GuiGraphics graphics, int x, int y, boolean up, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y, x + SBADDON_BTN, y + SBADDON_BTN, 0xFF4A6FA3);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF3C3C3C;
        for (int i = 0; i < 4; i++) {
            int lineY = up ? y + 2 + i : y + 6 - i;
            graphics.fill(x + 2 + i, lineY, x + 7 - i, lineY + 1, color);
        }
    }

    /** 行尾「删除」：7×7 两条 45° 对角线，画法和精妙背包的删除按钮一致。 */
    @Unique
    private void sbaddon$drawCross(GuiGraphics graphics, int x, int y, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y, x + SBADDON_BTN, y + SBADDON_BTN, 0xFFA34A4A);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF3C1A1A;
        int size = 7;
        int cx = x + (SBADDON_BTN - size) / 2;
        int cy = y + (SBADDON_BTN - size) / 2;
        for (int i = 0; i < size; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            graphics.fill(cx + size - 1 - i, cy + i, cx + size - i, cy + i + 1, color);
        }
    }

    @Unique
    private String sbaddon$truncate(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
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
            int btnY = ry + (SBADDON_ROW_H - 2 - SBADDON_BTN) / 2;
            int crossX = rowX + rowW - SBADDON_BTN;
            int downX = crossX - SBADDON_BTN - 2;
            int upX = downX - SBADDON_BTN - 2;

            if (sbaddon$inside(mouseX, mouseY, crossX, btnY, SBADDON_BTN, SBADDON_BTN)) {
                if (index < samples.size()) {
                    sbaddon$send(FxntClassifyAction.remove(index));
                    sbaddon$stopEditing(false);
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
            if (index < samples.size() && mouseX >= rowX + 21 && mouseX < upX - 2) {
                // 点名字 → 就地改名（挂一个 EditBox 盖在文字上，字由它自己收）
                sbaddon$startRename(index, rowX + 21, ry + 2, Math.max(40, upX - rowX - 23),
                        SBADDON_ROW_H - 6, samples.get(index));
                return;
            }
            if (index < samples.size()) {
                sbaddon$stopEditing(false);
            }
        }

        int footerY = panelTop + SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
        int autoX = rowX + rowW - SBADDON_AUTO_W - 6;
        int autoY = footerY + (SBADDON_ROW_H - 2 - SBADDON_AUTO_H) / 2;
        if (sbaddon$inside(mouseX, mouseY, autoX, autoY, SBADDON_AUTO_W, SBADDON_AUTO_H)) {
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
