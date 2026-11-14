package com.huanj.sbaddon.compat.fxntstorage.mixin;

import com.huanj.sbaddon.client.BannerBackground;
import com.huanj.sbaddon.client.layout.ClassifyCollapse;
import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.client.layout.ClassifyMotion;
import com.huanj.sbaddon.client.layout.ClassifySource;
import com.huanj.sbaddon.client.widget.ExpandCollapseButton;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
import java.util.HashMap;
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
    /** 贴图里单元格的左上角（三套尺寸 4/7/9 行都是这个位置，18px 步长可无缝平铺）。 */
    @Unique
    private static final int SBADDON_CELL_TEX_U = 29;
    @Unique
    private static final int SBADDON_CELL_TEX_V = 17;
    @Unique
    private static final int SBADDON_CELL_TEX_W = 282;
    @Unique
    private static final int SBADDON_ORIGIN_X = 30;
    @Unique
    private static final int SBADDON_ORIGIN_Y = 18;
    @Unique
    private static final int SBADDON_HIDDEN = -10000;

    // ---------------------------------------------------------------- 面板常量
    // 全部对齐精妙背包的 ClassifyTab：行 20 / 填充 18 / 行尾三个 9px 按钮。

    @Unique
    private static final int SBADDON_PANEL_W = 150;
    @Unique
    private static final int SBADDON_HEADER_H = 22;
    @Unique
    private static final int SBADDON_ROW_H = 20;
    @Unique
    private static final int SBADDON_ROW_FILL_H = 18;
    @Unique
    private static final int SBADDON_ROWS_VISIBLE = 6;
    @Unique
    private static final int SBADDON_FOOTER_H = 20;
    @Unique
    private static final int SBADDON_PANEL_H =
            SBADDON_HEADER_H + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H + SBADDON_FOOTER_H;
    @Unique
    private static final int SBADDON_PAD = 4;
    /** 行尾：改名（铅笔）/ 删除（叉）/ 拖拽手柄（三横），各 9px，间距 1px。 */
    @Unique
    private static final int SBADDON_EDIT_W = 9;
    @Unique
    private static final int SBADDON_DELETE_W = 9;
    @Unique
    private static final int SBADDON_HANDLE_W = 9;
    @Unique
    private static final int SBADDON_AUTO_W = 44;
    @Unique
    private static final int SBADDON_AUTO_H = 12;
    /** 改名输入框内文字相对边框的内缩（也是 EditBox 控件矩形相对边框的偏移）。 */
    @Unique
    private static final int SBADDON_TEXT_INSET = 4;

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
    private static final int SBADDON_COLOR_ROW_DRAG = 0xFF9CB088;
    @Unique
    private static final int SBADDON_COLOR_ROW_HL = 0xFFB8B8B8;
    @Unique
    private static final int SBADDON_COLOR_ROW_SH = 0xFF5E5E5E;
    @Unique
    private static final int SBADDON_COLOR_TEXT = 0xFF1E1E1E;
    @Unique
    private static final int SBADDON_COLOR_TEXT_DIM = 0xFF5A5A5A;
    @Unique
    private static final int SBADDON_COLOR_HANDLE = 0xFF3C3C3C;

    // ---------------------------------------------------------------- 状态

    @Unique
    private List<ClassifyLayout.Banner> sbaddon$banners = List.of();
    @Unique
    private List<ClassifyLayout.Placement> sbaddon$placements = List.of();
    /** 槽位 / 横幅的过渡动画（展开收起时下面的组滑上来）。 */
    @Unique
    private final ClassifyMotion sbaddon$motion = new ClassifyMotion();
    /** 横幅 id → 本帧显示 y。 */
    @Unique
    private final Map<String, Integer> sbaddon$bannerDrawY = new HashMap<>();
    /** 横幅右侧展开按钮的命中区：id → {x, y}。 */
    @Unique
    private final Map<String, int[]> sbaddon$toggleHits = new HashMap<>();
    /** 上一帧滚动行，滚动时不播过渡动画。 */
    @Unique
    private int sbaddon$lastMotionScroll = -1;
    @Unique
    private int sbaddon$scroll;
    @Unique
    private int sbaddon$maxScroll;
    @Unique
    private boolean sbaddon$applied;

    @Unique
    private ResourceLocation sbaddon$cellTexture;
    @Unique
    private String sbaddon$cellTextureSuffix = "";
    /** 当前那套贴图的实际高度（宽固定 282）。blit 必须按真实尺寸算 UV，否则整片错位。 */
    @Unique
    private int sbaddon$cellTextureHeight;

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
    /** 改名态右侧勾/叉的命中区：{x, y, w, h}。渲染时写入，点击时读。 */
    @Unique
    private int[] sbaddon$saveHit;
    @Unique
    private int[] sbaddon$cancelHit;

    /** 本帧各行的命中区：{行号, x, y, w, h, 手柄x, 删除x, 改名x}。 */
    @Unique
    private final List<int[]> sbaddon$rowHits = new ArrayList<>();

    // 拖拽 + 让位动画（与精妙背包 ClassifyTab 同一套算法）
    @Unique
    private int sbaddon$dragRow = -1;
    @Unique
    private int sbaddon$dragTarget = -1;
    @Unique
    private double sbaddon$dragMouseY;
    /**
     * 行的显示 y，**按分组 id 记录**而不是按行号。
     * <p>
     * 行号会随排序/删除变化，服务端回包那一刻「第 3 行」可能换成了别的条目 —— 用行号当 key
     * 会让所有行错位跳一下（这就是之前"重排后闪跳"的来源）。分组 id 跟着条目走，
     * 重排后每条自己滑到新槽位，中间不会闪。
     */
    @Unique
    private final Map<String, Float> sbaddon$rowY = new HashMap<>();
    @Unique
    private int sbaddon$lastPanelScroll;
    /** Shift 放权期间宿主滚动行的快照，方法返回时还原。 */
    @Unique
    private int sbaddon$savedTopRow;
    @Unique
    private boolean sbaddon$scrollGuard;
    /**
     * 客户端临时顺序：拖拽/增删后立刻生效，服务端回包只当确认。
     * <p>
     * 精妙背包那边 {@code applyOrder} 是本地立即改数据，所以松手瞬间就到位、没有空窗；
     * 我们这边的数据在服务端的物品 NBT 里，往返期间列表还是旧顺序 ——
     * 旧顺序会让被拖条目的目标位置变回原槽位，于是"滑回去再滑过来"。
     * 用这份覆盖抹平空窗：服务端顺序对上后自动丢弃。
     */
    @Unique
    private List<ItemStack> sbaddon$localOrder;

    /** 当前应当渲染/交互的样本顺序。 */
    @Unique
    private List<ItemStack> sbaddon$samples() {
        return sbaddon$localOrder != null ? sbaddon$localOrder : sbaddon$snapshot.samples();
    }

    /** 服务端顺序已经和本地覆盖一致 → 丢掉覆盖，回到权威数据。 */
    @Unique
    private void sbaddon$syncLocalOrder() {
        if (sbaddon$localOrder == null) {
            return;
        }
        List<ItemStack> server = sbaddon$snapshot.samples();
        if (server.size() != sbaddon$localOrder.size()) {
            return;
        }
        for (int i = 0; i < server.size(); i++) {
            if (!sbaddon$rowKey(server.get(i), i).equals(sbaddon$rowKey(sbaddon$localOrder.get(i), i))) {
                return;
            }
        }
        sbaddon$localOrder = null;
    }

    /** 行位置缓存的 key：优先分组 id（一条样本一个分组），退化时用行号占位。 */
    @Unique
    private static String sbaddon$rowKey(ItemStack sample, int index) {
        String id = sample == null || sample.isEmpty() ? null : ClassifyGroups.groupId(sample);
        return id != null ? id : "idx:" + index;
    }

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
        sbaddon$bannerDrawY.clear();
        sbaddon$toggleHits.clear();

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
                sbaddon$localOrder = null;
                sbaddon$stopEditing(false);
                sbaddon$cancelDrag();
                sbaddon$motion.reset();
                sbaddon$restoreVanillaSlots(menu);
            }
            return;
        }

        int rows = Math.max(1, containerRows);
        HolderLookup.Provider registries = sbaddon$registries();
        sbaddon$snapshot = FxntClassifySource.snapshot(upgradeSlot.getItem(), registries);

        ClassifySource source = new FxntClassifySource(menu, sbaddon$samples(),
                sbaddon$snapshot.names());
        ClassifyLayout.Result result = ClassifyLayout.compute(
                source, SBADDON_COLS, rows, sbaddon$scroll, null, ClassifyCollapse::isCollapsed,
                SBADDON_ORIGIN_X, SBADDON_ORIGIN_Y);

        sbaddon$maxScroll = Math.max(0, result.totalRows() - rows);
        if (sbaddon$scroll > sbaddon$maxScroll) {
            sbaddon$scroll = sbaddon$maxScroll;
            result = ClassifyLayout.compute(
                    source, SBADDON_COLS, rows, sbaddon$scroll, null, ClassifyCollapse::isCollapsed,
                    SBADDON_ORIGIN_X, SBADDON_ORIGIN_Y);
        }

        // 滚动时整片内容平移，插值会变成"追着滚"的补偿动画 —— 丢掉缓存让它瞬间落位
        if (sbaddon$scroll != sbaddon$lastMotionScroll) {
            sbaddon$motion.reset();
            sbaddon$lastMotionScroll = sbaddon$scroll;
        }

        sbaddon$motion.beginFrame();
        int slotCount = menu.layout.items().getCount();
        for (int i = 0; i < slotCount; i++) {
            Slot slot = menu.getSlot(i);
            slot.x = SBADDON_HIDDEN;
            slot.y = SBADDON_HIDDEN;
        }
        List<ClassifyLayout.Placement> animated = new ArrayList<>(result.placements().size());
        for (ClassifyLayout.Placement placement : result.placements()) {
            Slot slot = menu.getSlot(placement.slotIndex());
            if (!slot.hasItem()) {
                // 空槽只是网格背景，不参与过渡：让格子瞬间落位，
                // 否则折叠/展开时下面那一大片空格子会整体滑动，看起来就是贴图在大块大块地动
                sbaddon$motion.drop(placement.slotIndex());
                slot.x = placement.x();
                slot.y = placement.y();
            } else {
                // 有物品的槽才插值：展开/收起时物品滑过去，不是瞬移
                float[] pos = sbaddon$motion.slot(placement.slotIndex(), placement.x(), placement.y());
                slot.x = Math.round(pos[0]);
                slot.y = Math.round(pos[1]);
            }
            // 格子贴图也按显示坐标平铺，动画期间不会和物品错开
            animated.add(new ClassifyLayout.Placement(placement.slotIndex(), slot.x, slot.y));
        }
        sbaddon$bannerDrawY.clear();
        for (ClassifyLayout.Banner banner : result.banners()) {
            sbaddon$bannerDrawY.put(banner.id(),
                    Math.round(sbaddon$motion.bannerY(banner.id(), banner.y())));
        }
        sbaddon$motion.endFrame();

        sbaddon$banners = result.banners();
        sbaddon$placements = animated;
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
        int max = Math.max(0, sbaddon$samples().size() - SBADDON_ROWS_VISIBLE);
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

        // 2) 逐格贴 FXNT 自己的单元格贴图（18×18，18px 步长无缝），视觉与未开分类时一致
        sbaddon$blitCells(graphics, left, top);

        // 3) 整行分类横幅
        sbaddon$drawBanners(graphics, left, top, mouseX, mouseY);

        // 4) 配置面板
        sbaddon$drawPanel(graphics, mouseX, mouseY);
    }

    /**
     * 逐格平铺 FXNT 的单元格贴图。
     * <p>
     * 之前是「平色铺底 + 只画块外框」，因为内部格子的四邻都存在，一条线都不画 —— 结果一片纯灰。
     * 直接贴原生 18×18 格子：边框、内凹高光全部由贴图给出，和没开分类时一模一样。
     */
    @Unique
    private void sbaddon$blitCells(GuiGraphics graphics, int left, int top) {
        if (sbaddon$placements.isEmpty()) {
            return;
        }
        ResourceLocation texture = sbaddon$cellTexture();
        for (ClassifyLayout.Placement placement : sbaddon$placements) {
            // placement 是「物品」坐标，贴图格子比它左上各多 1px
            // 注意用 9 参数重载：7 参数版本默认按 256×256 算 UV，这张贴图是 282×{225,281,317}，
            // 用它会把格子整片拉歪
            graphics.blit(texture, left + placement.x() - 1, top + placement.y() - 1,
                    SBADDON_CELL_TEX_U, SBADDON_CELL_TEX_V, SBADDON_CELL, SBADDON_CELL,
                    SBADDON_CELL_TEX_W, sbaddon$cellTextureHeight);
        }
    }

    /** 单元格贴图随可见行数换套（4 / 7 / 9 行），三套的格子原点与步长都一样。 */
    @Unique
    private ResourceLocation sbaddon$cellTexture() {
        int rows = Math.max(1, containerRows);
        String suffix = rows <= 4 ? "4" : rows <= 7 ? "7" : "9";
        if (sbaddon$cellTexture == null || !sbaddon$cellTextureSuffix.equals(suffix)) {
            sbaddon$cellTexture = ResourceLocation.fromNamespaceAndPath("fxntstorage",
                    "textures/gui/container/backpack_screen_" + suffix + ".png");
            sbaddon$cellTextureSuffix = suffix;
            // 三套贴图实测尺寸：4 行 282×225 / 7 行 282×281 / 9 行 282×317
            sbaddon$cellTextureHeight = switch (suffix) {
                case "4" -> 225;
                case "7" -> 281;
                default -> 317;
            };
        }
        return sbaddon$cellTexture;
    }

    @Unique
    private void sbaddon$drawBanners(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        if (sbaddon$banners.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (ClassifyLayout.Banner banner : sbaddon$banners) {
            int x = left + banner.x() - 1;
            int drawY = sbaddon$bannerDrawY.getOrDefault(banner.id(), banner.y());
            int y = top + drawY - 1;
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

            // 横幅右端的展开/收起按钮
            int btnX = right - ExpandCollapseButton.SIZE - 2;
            int btnY = y + (ClassifyLayout.CELL - ExpandCollapseButton.SIZE) / 2;
            boolean btnHovered = ExpandCollapseButton.isOver(btnX, btnY, mouseX, mouseY);
            ExpandCollapseButton.render(graphics, btnX, btnY,
                    ClassifyCollapse.expandAmount(banner.id()), btnHovered, true);
            sbaddon$toggleHits.put(banner.id(), new int[]{btnX, btnY});

            String label = banner.label().getString();
            int textLeft = x + 20 + 4;
            // 右侧留出按钮的位置，文字不要压到箭头上
            int textWidth = Math.max(0, btnX - 3 - textLeft);
            if (font.width(label) > textWidth) {
                label = font.plainSubstrByWidth(label, Math.max(0, textWidth - font.width("..."))) + "...";
            }
            graphics.drawString(font, label, textLeft, y + 5, 0xFF1D2433, false);
        }
    }

    // ---------------------------------------------------------------- 面板几何

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
    private int sbaddon$rowX() {
        return sbaddon$panelLeft() + SBADDON_PAD;
    }

    @Unique
    private int sbaddon$rowW() {
        return SBADDON_PANEL_W - SBADDON_PAD * 2;
    }

    /** 列表第一行的 y。 */
    @Unique
    private int sbaddon$listTop() {
        return sbaddon$panelTop() + SBADDON_HEADER_H;
    }

    @Unique
    private int sbaddon$footerTop() {
        return sbaddon$listTop() + SBADDON_ROWS_VISIBLE * SBADDON_ROW_H;
    }

    // ---------------------------------------------------------------- 让位动画

    /** 该行的目标 y：拖动中的行跟手，其余行按「谁让位」错开一格。 */
    @Unique
    private float sbaddon$targetY(int r) {
        if (sbaddon$dragRow == r) {
            double follow = sbaddon$dragMouseY - SBADDON_ROW_H / 2.0;
            double clamped = Math.max(sbaddon$listTop() - SBADDON_ROW_H / 2.0,
                    Math.min(follow, sbaddon$footerTop()));
            return (float) clamped;
        }
        int shifted = r;
        if (sbaddon$dragRow >= 0 && sbaddon$dragTarget >= 0) {
            if (sbaddon$dragRow < r && r <= sbaddon$dragTarget) {
                // 后面的行整体上移一格，把位置让出来
                shifted = r - 1;
            } else if (sbaddon$dragTarget <= r && r < sbaddon$dragRow) {
                // 前面的行整体下移一格
                shifted = r + 1;
            }
        }
        return sbaddon$listTop() + (shifted - sbaddon$panelScroll) * (float) SBADDON_ROW_H;
    }

    /** 每帧刷新一次所有可见行的显示位置。 */
    @Unique
    private void sbaddon$updateRowPositions(List<ItemStack> samples) {
        if (sbaddon$lastPanelScroll != sbaddon$panelScroll) {
            // 滚动时内容整列平移，插值会变成"从上往下滑"的补偿动画 —— 直接丢掉缓存
            sbaddon$rowY.clear();
            sbaddon$lastPanelScroll = sbaddon$panelScroll;
        }
        int total = samples.size();
        int last = Math.min(total, sbaddon$panelScroll + SBADDON_ROWS_VISIBLE);

        // 只保留仍存在且可见的记录：删掉的条目不会在缓存里留垃圾
        Set<String> alive = new HashSet<>();
        for (int r = sbaddon$panelScroll; r < last; r++) {
            alive.add(sbaddon$rowKey(samples.get(r), r));
        }
        if (sbaddon$dragRow >= 0 && sbaddon$dragRow < total) {
            alive.add(sbaddon$rowKey(samples.get(sbaddon$dragRow), sbaddon$dragRow));
        }
        sbaddon$rowY.keySet().retainAll(alive);

        for (int r = sbaddon$panelScroll; r < last; r++) {
            sbaddon$stepTo(r, samples.get(r));
        }
        if (sbaddon$dragRow >= 0 && sbaddon$dragRow < total) {
            sbaddon$stepTo(sbaddon$dragRow, samples.get(sbaddon$dragRow));
        }
    }

    /**
     * 朝目标位置插值。拖动中的行不做插值（否则跟不上鼠标），其余行按 0.45 的系数收敛，
     * 60fps 下大约 4 帧到位，观感是"滑过去"而不是"跳过去"。
     */
    @Unique
    private void sbaddon$stepTo(int r, ItemStack sample) {
        String key = sbaddon$rowKey(sample, r);
        float target = sbaddon$targetY(r);
        Float current = sbaddon$rowY.get(key);
        if (current == null || sbaddon$dragRow == r) {
            sbaddon$rowY.put(key, target);
            return;
        }
        float diff = target - current;
        sbaddon$rowY.put(key, Math.abs(diff) < 0.4f ? target : current + diff * 0.45f);
    }

    @Unique
    private float sbaddon$displayY(int r, ItemStack sample) {
        Float v = sbaddon$rowY.get(sbaddon$rowKey(sample, r));
        return v != null ? v : sbaddon$listTop() + (r - sbaddon$panelScroll) * (float) SBADDON_ROW_H;
    }

    // ---------------------------------------------------------------- 面板绘制

    /**
     * 面板绘制。视觉与功能都对齐精妙背包的分类页：标题栏带图标格、每条是凸起立体行、
     * 行尾「铅笔 / 叉 / 三横手柄」，拖手柄可排序（其余行让位滑动），底部固定行放绿色「一键分类」。
     */
    @Unique
    private void sbaddon$drawPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();
        int panelTop = sbaddon$panelTop();
        ItemStack upgradeIcon = new ItemStack(FxntCompatContent.CLASSIFY_UPGRADE.get());

        sbaddon$rowHits.clear();
        sbaddon$saveHit = null;
        sbaddon$cancelHit = null;

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

        // 服务端顺序一旦与本地覆盖一致就丢掉覆盖，回到权威数据
        sbaddon$syncLocalOrder();
        List<ItemStack> samples = sbaddon$samples();
        // 拖拽状态每帧按鼠标位置推进（不依赖 mouseDragged 注入），让位动画才有数据可插值
        sbaddon$tickDrag(mouseY);
        sbaddon$clampPanelScroll();
        sbaddon$updateRowPositions(samples);

        // 先画普通行，被拖的行最后画，压在其他行上面
        int last = Math.min(samples.size(), sbaddon$panelScroll + SBADDON_ROWS_VISIBLE);
        for (int r = sbaddon$panelScroll; r < last; r++) {
            if (r == sbaddon$dragRow) {
                continue;
            }
            sbaddon$drawEntryRow(graphics, font, mouseX, mouseY, r, samples.get(r),
                    Math.round(sbaddon$displayY(r, samples.get(r))));
        }
        if (sbaddon$dragRow >= 0 && sbaddon$dragRow < samples.size()) {
            int r = sbaddon$dragRow;
            sbaddon$drawEntryRow(graphics, font, mouseX, mouseY, r, samples.get(r),
                    Math.round(sbaddon$displayY(r, samples.get(r))));
        }

        // 底部固定行：空槽 + 提示 + 绿色「一键分类」（和精妙背包的空行同款结构）
        int rowX = sbaddon$rowX();
        int rowW = sbaddon$rowW();
        int footerTop = sbaddon$footerTop();
        boolean carrying = !sbaddon$self().getMenu().getCarried().isEmpty();
        boolean dropHover = carrying && sbaddon$inside(mouseX, mouseY,
                rowX + 1, footerTop, SBADDON_CELL, SBADDON_ROW_FILL_H);
        sbaddon$drawRowFace(graphics, rowX, footerTop, rowW, SBADDON_ROW_FILL_H, dropHover);
        GuiHelper.renderSlotsBackground(graphics, rowX + 1, footerTop, 1, 1);
        if (dropHover) {
            // 手里拿着东西又悬在空槽上：把槽位描一圈绿，明确"可以松手放这里"
            graphics.fill(rowX + 1, footerTop, rowX + 1 + SBADDON_CELL, footerTop + 1, 0xFF7DBF6A);
            graphics.fill(rowX + 1, footerTop + SBADDON_CELL - 1, rowX + 1 + SBADDON_CELL,
                    footerTop + SBADDON_CELL, 0xFF7DBF6A);
            graphics.fill(rowX + 1, footerTop, rowX + 2, footerTop + SBADDON_CELL, 0xFF7DBF6A);
            graphics.fill(rowX + SBADDON_CELL, footerTop, rowX + 1 + SBADDON_CELL,
                    footerTop + SBADDON_CELL, 0xFF7DBF6A);
        }
        int autoX = rowX + rowW - SBADDON_AUTO_W - 6;
        int autoY = footerTop + (SBADDON_ROW_FILL_H - SBADDON_AUTO_H) / 2;
        if (sbaddon$editingRow < 0) {
            // 这一格是真交互：从背包里拖一件物品松手在这里，就把它所属的分组加进分类表
            String hint = sbaddon$truncate(
                    Component.translatable("gui.sbaddon.classify.row.empty").getString(),
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

        sbaddon$drawScrollbar(graphics, samples.size());
    }

    /** 一条分类：立体行底 + 图标格 + 名称 + 铅笔/叉/手柄。 */
    @Unique
    private void sbaddon$drawEntryRow(GuiGraphics graphics, Font font, int mouseX, int mouseY,
                                      int r, ItemStack sample, int y) {
        int x = sbaddon$rowX();
        int w = sbaddon$rowW();
        int h = SBADDON_ROW_FILL_H;
        boolean dragging = sbaddon$dragRow == r;
        boolean hovered = !dragging && sbaddon$inside(mouseX, mouseY, x, y, w, h);

        int bg = dragging ? SBADDON_COLOR_ROW_DRAG
                : hovered ? SBADDON_COLOR_ROW_HOVER : SBADDON_COLOR_ROW;
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.fill(x, y, x + w, y + 1, SBADDON_COLOR_ROW_HL);
        graphics.fill(x, y + h - 1, x + w, y + h, SBADDON_COLOR_ROW_SH);

        // 图标格：和精妙背包设置页同一个槽位底图，物品正好落在凹槽里
        int iconX = x + 1;
        int iconY = y;
        GuiHelper.renderSlotsBackground(graphics, iconX, iconY, 1, 1);
        ClassifyGroups.Row row = ClassifyGroups.describe(sample, sbaddon$snapshot.names());
        graphics.renderItem(row.icon(), iconX + 1, iconY + 1);

        int handleX = x + w - SBADDON_HANDLE_W - 3;
        int deleteX = handleX - SBADDON_DELETE_W - 1;
        int editX = deleteX - SBADDON_EDIT_W - 1;
        int nameX = iconX + SBADDON_CELL + 2;
        int nameW = Math.max(0, editX - nameX - 3);
        boolean editing = sbaddon$editingRow == r;

        if (editing) {
            // 编辑态：名称位置换成输入框，右侧只留勾/叉，铅笔、叉、手柄全部让位
            sbaddon$drawRenameFrame(graphics, font, mouseX, mouseY, x, y, w, h, nameX);
        } else {
            graphics.drawString(font, sbaddon$truncate(row.label().getString(), nameW), nameX, y + 5,
                    SBADDON_COLOR_TEXT, false);
            sbaddon$drawPencil(graphics, editX, y, h,
                    sbaddon$inside(mouseX, mouseY, editX, y, SBADDON_EDIT_W, h));
            sbaddon$drawCross(graphics, deleteX, y, h,
                    sbaddon$inside(mouseX, mouseY, deleteX, y, SBADDON_DELETE_W, h));
            sbaddon$drawHandle(graphics, handleX, y, h, dragging);
        }

        sbaddon$rowHits.add(new int[]{r, x, y, w, h, handleX, deleteX, editX});
    }

    /** 改名态的一行：深色输入框（EditBox 自己画字）+ 右侧勾/叉。 */
    @Unique
    private void sbaddon$drawRenameFrame(GuiGraphics graphics, Font font, int mouseX, int mouseY,
                                         int x, int y, int w, int h, int nameX) {
        int right = x + w - 3;
        int cancelX = right - SBADDON_EDIT_W;
        int saveX = cancelX - SBADDON_EDIT_W - 1;
        int boxX = nameX;
        int boxW = Math.max(12, saveX - 3 - boxX);
        int boxY = y + 3;
        int boxH = h - 6;

        // 输入框每帧跟着行动画走，并保持「文字位置」的几何（见 startRename 里的说明）
        EditBox box = sbaddon$editBox;
        if (box != null) {
            box.setX(boxX + SBADDON_TEXT_INSET);
            box.setY(boxY + (boxH - 8) / 2);
            box.setWidth(Math.max(8, boxW - SBADDON_TEXT_INSET * 2));
        }

        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF1B1B1B);
        int border = 0xFF7AA0D8;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 1, border);
        graphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, border);
        graphics.fill(boxX, boxY, boxX + 1, boxY + boxH, border);
        graphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, border);

        sbaddon$drawCheck(graphics, saveX, y, h, sbaddon$inside(mouseX, mouseY, saveX, y, SBADDON_EDIT_W, h));
        sbaddon$drawCrossGlyph(graphics, cancelX, y, h,
                sbaddon$inside(mouseX, mouseY, cancelX, y, SBADDON_EDIT_W, h));
        sbaddon$saveHit = new int[]{saveX, y, SBADDON_EDIT_W, h};
        sbaddon$cancelHit = new int[]{cancelX, y, SBADDON_EDIT_W, h};
    }

    @Unique
    private void sbaddon$drawBox(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, SBADDON_COLOR_BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, SBADDON_COLOR_PANEL);
    }

    /** 一行：底色 + 顶部高光 + 底部阴影，和精妙背包分类页的行完全一致。 */
    @Unique
    private void sbaddon$drawRowFace(GuiGraphics graphics, int x, int y, int w, int h, boolean hovered) {
        graphics.fill(x, y, x + w, y + h, hovered ? SBADDON_COLOR_ROW_HOVER : SBADDON_COLOR_ROW);
        graphics.fill(x, y, x + w, y + 1, SBADDON_COLOR_ROW_HL);
        graphics.fill(x, y + h - 1, x + w, y + h, SBADDON_COLOR_ROW_SH);
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

    /** 拖拽手柄：三条横线，和精妙背包的 drawHandle 同参数。 */
    @Unique
    private void sbaddon$drawHandle(GuiGraphics graphics, int x, int y, int h, boolean active) {
        int color = active ? 0xFF1E1E1E : SBADDON_COLOR_HANDLE;
        int spacing = 3;
        int totalH = 2 * spacing + 1;
        int cx = x + 2;
        int cy = y + (h - totalH) / 2;
        for (int i = 0; i < 3; i++) {
            int lineY = cy + i * spacing;
            graphics.fill(cx, lineY, cx + 5, lineY + 1, color);
        }
    }

    /** 行尾「改名」：一支 45° 铅笔，和精妙背包的 drawEdit 同一画法。 */
    @Unique
    private void sbaddon$drawPencil(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + SBADDON_EDIT_W, y + h - 2, 0xFF4A6FA3);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF20344D;
        int size = 7;
        int cx = x + (SBADDON_EDIT_W - size) / 2;
        int cy = y + (h - size) / 2;
        for (int i = 0; i < 5; i++) {
            graphics.fill(cx + i, cy + 5 - i, cx + i + 1, cy + 6 - i, color);
        }
        graphics.fill(cx + 5, cy, cx + 7, cy + 2, color);
    }

    /** 行尾「删除」：7×7 两条 45° 对角线，和精妙背包的 drawDelete 一致。 */
    @Unique
    private void sbaddon$drawCross(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + SBADDON_DELETE_W, y + h - 2, 0xFFA34A4A);
        }
        sbaddon$drawXGlyph(graphics, x, y, h, SBADDON_DELETE_W,
                hovered ? 0xFFFFFFFF : 0xFF3C1A1A);
    }

    /** 改名态右侧的「取消」叉：同画法，颜色偏取消。 */
    @Unique
    private void sbaddon$drawCrossGlyph(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + SBADDON_EDIT_W, y + h - 2, 0xFFA34A4A);
        }
        sbaddon$drawXGlyph(graphics, x, y, h, SBADDON_EDIT_W,
                hovered ? 0xFFFFFFFF : 0xFFE89B9B);
    }

    @Unique
    private void sbaddon$drawXGlyph(GuiGraphics graphics, int x, int y, int h, int w, int color) {
        int size = 7;
        int cx = x + (w - size) / 2;
        int cy = y + (h - size) / 2;
        for (int i = 0; i < size; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            graphics.fill(cx + size - 1 - i, cy + i, cx + size - i, cy + i + 1, color);
        }
    }

    /** 改名态右侧的「保存」勾：两段对角线拼出来的对号。 */
    @Unique
    private void sbaddon$drawCheck(GuiGraphics graphics, int x, int y, int h, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y + 2, x + SBADDON_EDIT_W, y + h - 2, 0xFF4F8A4F);
        }
        int color = hovered ? 0xFFFFFFFF : 0xFF9BE89B;
        int cx = x + (SBADDON_EDIT_W - 7) / 2;
        int cy = y + (h - 7) / 2;
        for (int i = 0; i < 3; i++) {
            graphics.fill(cx + 1 + i, cy + 3 + i, cx + 2 + i, cy + 4 + i, color);
        }
        for (int i = 0; i < 4; i++) {
            graphics.fill(cx + 3 + i, cy + 5 - i, cx + 4 + i, cy + 6 - i, color);
        }
    }

    /** 内容超出可视行数时，右侧画一条滚动条（轨道 + 滑块），参数同精妙背包。 */
    @Unique
    private void sbaddon$drawScrollbar(GuiGraphics graphics, int total) {
        if (total <= SBADDON_ROWS_VISIBLE) {
            return;
        }
        int x = sbaddon$panelLeft() + SBADDON_PANEL_W - 3;
        int top = sbaddon$listTop();
        int trackH = SBADDON_ROWS_VISIBLE * SBADDON_ROW_H - 2;
        graphics.fill(x, top, x + 3, top + trackH, 0xFF6A6A6A);

        int thumbH = Math.max(10, trackH * SBADDON_ROWS_VISIBLE / total);
        int maxScroll = total - SBADDON_ROWS_VISIBLE;
        int thumbY = top + (trackH - thumbH) * Math.min(sbaddon$panelScroll, maxScroll)
                / Math.max(1, maxScroll);
        graphics.fill(x, thumbY, x + 3, thumbY + thumbH, 0xFFD8D8D8);
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
        // 横幅右端的展开/收起按钮优先：命中就吃掉这次点击，别让它落到下面的槽位上
        for (Map.Entry<String, int[]> entry : sbaddon$toggleHits.entrySet()) {
            int[] hit = entry.getValue();
            if (ExpandCollapseButton.isOver(hit[0], hit[1], mouseX, mouseY)) {
                ClassifyCollapse.toggle(entry.getKey());
                cir.setReturnValue(true);
                return;
            }
        }
        int panelLeft = sbaddon$panelLeft();
        int tabTop = sbaddon$tabTop();

        // 页签按钮
        if (sbaddon$inside(mouseX, mouseY, panelLeft, tabTop, 20, 20)) {
            sbaddon$panelOpen = !sbaddon$panelOpen;
            sbaddon$stopEditing(false);
            sbaddon$cancelDrag();
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

        // 编辑态优先吃点击：勾=保存，叉=放弃，点别处按放弃处理
        if (sbaddon$editingRow >= 0) {
            if (sbaddon$saveHit != null && sbaddon$inside(mouseX, mouseY,
                    sbaddon$saveHit[0], sbaddon$saveHit[1], sbaddon$saveHit[2], sbaddon$saveHit[3])) {
                sbaddon$stopEditing(true);
                return;
            }
            if (sbaddon$cancelHit != null && sbaddon$inside(mouseX, mouseY,
                    sbaddon$cancelHit[0], sbaddon$cancelHit[1], sbaddon$cancelHit[2], sbaddon$cancelHit[3])) {
                sbaddon$stopEditing(false);
                return;
            }
            sbaddon$stopEditing(false);
            return;
        }

        for (int[] hit : sbaddon$rowHits) {
            // 布局：{行号, x, y, w, h, 手柄x, 删除x, 改名x}
            if (sbaddon$inside(mouseX, mouseY, hit[7], hit[2], SBADDON_EDIT_W, hit[4])) {
                sbaddon$startRename(hit[0]);
                return;
            }
            if (sbaddon$inside(mouseX, mouseY, hit[6], hit[2], SBADDON_DELETE_W, hit[4])) {
                // 本地先删，服务端回包只作确认（否则收起那一行会有 1-2 帧的错位）
                List<ItemStack> base = new ArrayList<>(sbaddon$samples());
                if (hit[0] >= 0 && hit[0] < base.size()) {
                    base.remove(hit[0]);
                    sbaddon$localOrder = base;
                }
                sbaddon$send(FxntClassifyAction.remove(hit[0]));
                sbaddon$clampPanelScroll();
                return;
            }
            if (sbaddon$inside(mouseX, mouseY, hit[5], hit[2], SBADDON_HANDLE_W, hit[4])) {
                sbaddon$dragRow = hit[0];
                sbaddon$dragTarget = hit[0];
                sbaddon$dragMouseY = mouseY;
                return;
            }
        }

        // 底部空槽：手里拿着物品点到这里 = 把该物品所属分组加进分类表（取副本，物品不从手上消失）
        ItemStack carried = sbaddon$self().getMenu().getCarried();
        if (!carried.isEmpty() && sbaddon$inside(mouseX, mouseY,
                sbaddon$rowX() + 1, sbaddon$footerTop(), SBADDON_CELL, SBADDON_ROW_FILL_H)) {
            sbaddon$send(FxntClassifyAction.add(
                    BuiltInRegistries.ITEM.getKey(carried.getItem()).toString()));
            sbaddon$rowY.clear();
            return;
        }

        int autoX = sbaddon$rowX() + sbaddon$rowW() - SBADDON_AUTO_W - 6;
        int autoY = sbaddon$footerTop() + (SBADDON_ROW_FILL_H - SBADDON_AUTO_H) / 2;
        if (sbaddon$inside(mouseX, mouseY, autoX, autoY, SBADDON_AUTO_W, SBADDON_AUTO_H)) {
            sbaddon$send(FxntClassifyAction.auto());
            sbaddon$rowY.clear();
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onDrag(double mouseX, double mouseY, int button, double dragX, double dragY,
                                CallbackInfoReturnable<Boolean> cir) {
        if (sbaddon$dragRow < 0) {
            return;
        }
        sbaddon$tickDrag(mouseY);
        cir.setReturnValue(true);
    }

    /**
     * 拖拽状态的每帧推进：拿鼠标当前 y 更新「跟手位置」和「落点行」。
     * <p>
     * 只做位置推进，**不在这里判断松手** —— {@code MouseHandler.isLeftPressed} 只在
     * 没有打开任何界面时才会更新（开着 GUI 时它一直是 false），拿它判松手会把拖拽每帧打死。
     * 松手统一由 {@code mouseReleased} 收尾。
     */
    @Unique
    private void sbaddon$tickDrag(double mouseY) {
        if (sbaddon$dragRow < 0) {
            return;
        }
        int total = sbaddon$samples().size();
        if (total <= 0) {
            sbaddon$finishDrag();
            return;
        }
        sbaddon$dragMouseY = mouseY;

        int visible = SBADDON_ROWS_VISIBLE;
        int maxScroll = Math.max(0, total - visible);
        int top = sbaddon$listTop();
        int bottom = top + visible * SBADDON_ROW_H;

        // 拖到列表上下边缘就自动滚一行，这样才能把条目拖到当前看不见的位置
        if (mouseY < top + SBADDON_ROW_H && sbaddon$panelScroll > 0) {
            sbaddon$panelScroll--;
        } else if (mouseY > bottom - SBADDON_ROW_H && sbaddon$panelScroll < maxScroll) {
            sbaddon$panelScroll++;
        }

        int row = sbaddon$panelScroll + (int) Math.floor((mouseY - top) / (double) SBADDON_ROW_H);
        sbaddon$dragTarget = Math.min(Math.max(row, 0), total - 1);
    }

    /** 结束拖拽：本地立刻应用新顺序（无空窗），再发服务端确认。 */
    @Unique
    private void sbaddon$finishDrag() {
        int from = sbaddon$dragRow;
        int to = sbaddon$dragTarget;
        sbaddon$dragRow = -1;
        sbaddon$dragTarget = -1;
        if (from < 0 || to < 0 || from == to) {
            return;
        }
        List<ItemStack> base = new ArrayList<>(sbaddon$samples());
        if (from >= base.size() || to >= base.size()) {
            return;
        }
        ItemStack moved = base.remove(from);
        base.add(to, moved);
        // 和精妙背包的 applyOrder 等价：本地立即生效，服务端回包只是确认
        sbaddon$localOrder = base;
        sbaddon$send(FxntClassifyAction.reorder(from, to));
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onRelease(double mouseX, double mouseY, int button,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (sbaddon$dragRow < 0) {
            return;
        }
        sbaddon$finishDrag();
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!sbaddon$applied) {
            // 没装分类升级：Shift 也一律放权，不消费滚轮（让鼠标手势等模组正常收到事件）
            return;
        }
        int delta = scrollY > 0 ? -1 : 1;
        int panelLeft = sbaddon$panelLeft();
        int panelTop = sbaddon$panelTop();

        // 拖拽中滚轮一律不响应：自动滚动由 mouseDragged 按位置驱动，
        // 手动滚一下会让「拖到哪行」的映射跳一格
        if (sbaddon$dragRow >= 0) {
            cir.setReturnValue(true);
            return;
        }

        if (sbaddon$panelOpen
                && sbaddon$inside(mouseX, mouseY, panelLeft, panelTop, SBADDON_PANEL_W, SBADDON_PANEL_H)) {
            // 滚动会换掉行索引对应的条目，正在改名/拖动时先收尾
            if (sbaddon$editingRow >= 0) {
                sbaddon$stopEditing(false);
            }
            sbaddon$panelScroll += delta;
            sbaddon$clampPanelScroll();
            cir.setReturnValue(true);
            return;
        }

        BackpackScreen self = sbaddon$self();
        // 按住 Shift 时**不消费**滚轮：直接返回让原方法继续（它内部会调 super，从而触发
        // NeoForge 的 MouseScrolled.Post —— 鼠标手势那类模组就是靠它做 Shift+滚轮 ±1）。
        // 不能用 setReturnValue(true)：那会掐断方法体，把 super 一起吞掉。
        // 分类视图的槽位坐标每帧由我们重算，宿主自己滚一格不影响画面。
        if (Screen.hasShiftDown()) {
            sbaddon$savedTopRow = topVisibleRow;
            sbaddon$scrollGuard = true;
            return;
        }
        if (sbaddon$maxScroll > 0 && sbaddon$inside(mouseX, mouseY,
                self.getGuiLeft() + SBADDON_ORIGIN_X, self.getGuiTop() + SBADDON_ORIGIN_Y,
                SBADDON_COLS * SBADDON_CELL, Math.max(1, containerRows) * SBADDON_CELL)) {
            sbaddon$scroll = Math.max(0, Math.min(sbaddon$maxScroll, sbaddon$scroll + delta));
            cir.setReturnValue(true);
        }
    }

    /** Shift 放权的那一次滚轮：把宿主滚动行还原回去（分类布局每帧重排槽位，不需要额外同步）。 */
    @Inject(method = "mouseScrolled", at = @At("RETURN"))
    private void sbaddon$afterScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!sbaddon$scrollGuard) {
            return;
        }
        sbaddon$scrollGuard = false;
        topVisibleRow = sbaddon$savedTopRow;
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
    private void sbaddon$startRename(int row) {
        sbaddon$stopEditing(false);
        List<ItemStack> samples = sbaddon$samples();
        if (row < 0 || row >= samples.size()) {
            return;
        }
        String id = ClassifyGroups.groupId(samples.get(row));
        String custom = id == null ? null : sbaddon$snapshot.names().get(id);

        int y = Math.round(sbaddon$displayY(row, samples.get(row)));
        int x = sbaddon$rowX();
        int w = sbaddon$rowW();
        int nameX = x + 1 + SBADDON_CELL + 2;
        int cancelX = x + w - 3 - SBADDON_EDIT_W;
        int saveX = cancelX - SBADDON_EDIT_W - 1;
        int boxW = Math.max(12, saveX - 3 - nameX);

        Font font = Minecraft.getInstance().font;
        // bordered=false 的 EditBox 把文字画在控件**左上角原坐标**（不减边、不垂直居中），
        // 所以控件矩形必须直接取「文字应该出现的位置」：边框内缩 4px、垂直居中。
        EditBox box = new EditBox(font, nameX + SBADDON_TEXT_INSET,
                y + 3 + (SBADDON_ROW_FILL_H - 6 - 8) / 2,
                Math.max(8, boxW - SBADDON_TEXT_INSET * 2), 8,
                Component.translatable("gui.sbaddon.classify.title"));
        box.setMaxLength(32);
        // 边框自己画（和精妙背包同一个深色输入框），EditBox 只负责收字和画字
        box.setBordered(false);
        box.setTextColor(0xFFE8E8E8);
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
        sbaddon$saveHit = null;
        sbaddon$cancelHit = null;

        if (box != null) {
            ((ScreenAccess) sbaddon$self()).sbaddon$removeWidget(box);
            ((ContainerEventHandler) sbaddon$self()).setFocused(null);
        }
        if (!commit || box == null || row < 0) {
            return;
        }
        List<ItemStack> samples = sbaddon$samples();
        if (row >= samples.size()) {
            return;
        }
        String id = ClassifyGroups.groupId(samples.get(row));
        if (id == null) {
            return;
        }
        sbaddon$send(FxntClassifyAction.rename(id, box.getValue()));
    }

    @Unique
    private void sbaddon$cancelDrag() {
        if (sbaddon$dragRow < 0) {
            return;
        }
        sbaddon$dragRow = -1;
        sbaddon$dragTarget = -1;
        sbaddon$rowY.clear();
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
