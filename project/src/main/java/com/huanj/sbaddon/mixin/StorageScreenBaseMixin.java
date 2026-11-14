package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.BannerBackground;
import com.huanj.sbaddon.client.ClassifyTab;
import com.huanj.sbaddon.client.ClassifyViewState;
import com.huanj.sbaddon.client.layout.ClassifyCollapse;
import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.client.layout.ClassifyMotion;
import com.huanj.sbaddon.client.layout.SbClassifySource;
import com.huanj.sbaddon.client.widget.ExpandCollapseButton;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.InventoryScrollPanel;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 把背包网格重排成「按模组分块 + 整行分类横幅」。
 * <p>
 * 只改客户端的槽位坐标，不动菜单数据：物品渲染、悬停、tooltip、点击、Shift 点击全部自动跟随坐标。
 * 网格原点从 slot0 的「自然坐标」推导 —— 一旦检测到 SB 重置了槽位（init / resize），就重新取基准。
 */
@Mixin(StorageScreenBase.class)
public abstract class StorageScreenBaseMixin {

    @Unique
    private static final int SBADDON_HIDDEN = -10000;

    /** 横幅里图标和文字整体的右移量。 */
    @Unique
    private static final int ICON_TEXT_SHIFT = 4;

    @Unique
    private int sbaddon$originX = Integer.MIN_VALUE;
    @Unique
    private int sbaddon$originY = Integer.MIN_VALUE;
    @Unique
    private int sbaddon$appliedSlot0X = Integer.MIN_VALUE;
    @Unique
    private int sbaddon$appliedSlot0Y = Integer.MIN_VALUE;
    @Unique
    private int sbaddon$scroll;
    @Unique
    private int sbaddon$maxScroll;

    @Unique
    private List<ClassifyLayout.Banner> sbaddon$banners = List.of();
    @Unique
    private List<ClassifyLayout.Placement> sbaddon$placements = List.of();

    /** 槽位 / 横幅的过渡动画（展开收起时让下面的组滑上来，而不是瞬移）。 */
    @Unique
    private final ClassifyMotion sbaddon$motion = new ClassifyMotion();
    /** 横幅 id → 本帧的显示 y（含过渡偏移）。 */
    @Unique
    private final Map<String, Integer> sbaddon$bannerDrawY = new java.util.HashMap<>();
    /** 横幅整行的命中区：id → {x, y, w, h}。绘制时写入，点击时读。点整条横幅都能切换展开/收起。 */
    @Unique
    private final Map<String, int[]> sbaddon$bannerHits = new java.util.HashMap<>();
    /** 上一帧的滚动行，滚动时不播过渡动画（否则整片内容会"追"着滚）。 */
    @Unique
    private int sbaddon$lastMotionScroll = -1;
    /** 上一帧的存储槽数量：换背包 / 加装升级后容量变了，动画缓存必须丢掉，不然会拿旧索引去插值。 */
    @Unique
    private int sbaddon$lastMotionSlots = -1;

    /** 上一帧是否真的套用过分类布局（用来判断需不需要把槽位还给 SB）。 */
    @Unique
    private boolean sbaddon$layoutApplied;
    /** 本次滚轮是否临时打开了「只允许拖滚动条」限制，需要在方法返回时复原。 */
    @Unique
    private boolean sbaddon$restoredScrollRestrict;

    /** SB 自己的槽位排版：分类关掉/升级被拿掉时靠它把槽位摆回原样。 */
    @Shadow
    protected abstract void updateStorageSlotsPositions();

    @Inject(method = "render", at = @At("HEAD"))
    private void sbaddon$applyLayout(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        // 每帧先清空：升级被关掉 / 被拿掉时这里会提前 return，
        // 留着上一帧的布局会导致 SB 的格子背景被取消绘制，界面上出现空白行
        sbaddon$banners = List.of();
        sbaddon$placements = List.of();
        sbaddon$maxScroll = 0;
        sbaddon$bannerDrawY.clear();
        sbaddon$bannerHits.clear();

        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        AbstractContainerMenu rawMenu = self.getMenu();
        if (!(rawMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        ClassifyUpgradeContainer container = sbaddon$findContainer(menu);
        boolean active = container != null && container.isUpgradeEnabled();
        // 分类视图接管槽位排版时，让 SB 的滚动面板「只画物品、不滚动、不画条」
        ClassifyViewState.lockScrollPanel = active;
        if (!active) {
            // 关闭分类或升级被拿掉：把我们藏起来的槽位还给 SB，网格恢复原版排版
            if (sbaddon$layoutApplied) {
                sbaddon$layoutApplied = false;
                sbaddon$originX = Integer.MIN_VALUE;
                sbaddon$appliedSlot0X = Integer.MIN_VALUE;
                sbaddon$scroll = 0;
                sbaddon$motion.reset();
                updateStorageSlotsPositions();
            }
            return;
        }


        int cols = self.getSlotsOnLine();
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        if (cols <= 0 || storageSlots <= 0) {
            return;
        }
        // 面板实际能显示的行数：imageHeight = 114 + 18 * 可见行数。
        // 不能用 storageSlots/cols —— 那会让超出面板的槽位铺到玩家物品栏上，把玩家槽盖住。
        int visibleRows = Math.max(1, (self.getYSize() - StorageScreenBase.HEIGHT_WITHOUT_STORAGE_SLOTS) / 18);
        int rows = visibleRows;

        Slot slot0 = menu.getSlot(0);
        boolean slot0Moved = slot0.x != sbaddon$appliedSlot0X || slot0.y != sbaddon$appliedSlot0Y;
        if (slot0Moved && slot0.x >= 0 && slot0.y >= 0) {
            // 首帧，或 SB 刚把槽位重设为自然位置（init / resize）
            sbaddon$originX = slot0.x;
            sbaddon$originY = slot0.y;
            sbaddon$scroll = 0;
        }
        if (sbaddon$originX == Integer.MIN_VALUE) {
            return;
        }

        Predicate<net.minecraft.world.item.ItemStack> filter = self.getStackFilter();
        SbClassifySource source = new SbClassifySource(menu, container);
        ClassifyLayout.Result result = ClassifyLayout.compute(
                source, cols, rows, sbaddon$scroll, filter, ClassifyCollapse::isCollapsed,
                sbaddon$originX, sbaddon$originY);

        sbaddon$maxScroll = Math.max(0, result.totalRows() - rows);
        if (sbaddon$scroll > sbaddon$maxScroll) {
            sbaddon$scroll = sbaddon$maxScroll;
            result = ClassifyLayout.compute(
                    source, cols, rows, sbaddon$scroll, filter, ClassifyCollapse::isCollapsed,
                    sbaddon$originX, sbaddon$originY);
        }

        // 滚动时内容整体平移，插值会变成"追着滚"的补偿动画 —— 直接丢缓存让它瞬间落位。
        // 槽位数量变了同理（换背包 / 加装存储升级），否则会按旧索引从错误的位置滑过来
        if (sbaddon$scroll != sbaddon$lastMotionScroll || storageSlots != sbaddon$lastMotionSlots) {
            sbaddon$motion.reset();
            sbaddon$lastMotionScroll = sbaddon$scroll;
            sbaddon$lastMotionSlots = storageSlots;
        }

        sbaddon$motion.beginFrame();
        for (int i = 0; i < storageSlots; i++) {
            Slot slot = menu.getSlot(i);
            slot.x = SBADDON_HIDDEN;
            slot.y = SBADDON_HIDDEN;
        }
        List<ClassifyLayout.Placement> animated = new java.util.ArrayList<>(result.placements().size());
        for (ClassifyLayout.Placement placement : result.placements()) {
            Slot slot = menu.getSlot(placement.slotIndex());
            if (!slot.hasItem()) {
                // 空槽只是网格背景：不参与过渡，瞬间落到目标位置。
                // 否则折叠时下面那一大片空格子会整体滑过来，看起来就是"贴图在一大块一大块地动"。
                sbaddon$motion.drop(placement.slotIndex());
                slot.x = placement.x();
                slot.y = placement.y();
            } else {
                // 有物品的槽才插值：展开/收起时物品是滑过去，不是跳过去
                float[] pos = sbaddon$motion.slot(placement.slotIndex(), placement.x(), placement.y());
                slot.x = Math.round(pos[0]);
                slot.y = Math.round(pos[1]);
            }
            // 格子背景也按显示坐标画，否则动画期间物品会和格线错开
            animated.add(new ClassifyLayout.Placement(placement.slotIndex(), slot.x, slot.y));
        }
        sbaddon$bannerDrawY.clear();
        for (ClassifyLayout.Banner banner : result.banners()) {
            sbaddon$bannerDrawY.put(banner.id(),
                    Math.round(sbaddon$motion.bannerY(banner.id(), banner.y())));
        }
        sbaddon$motion.endFrame();

        sbaddon$appliedSlot0X = slot0.x;
        sbaddon$appliedSlot0Y = slot0.y;
        sbaddon$banners = result.banners();
        sbaddon$placements = animated;
        sbaddon$layoutApplied = true;
    }

    /**
     * SB 的格子背景是按「所有可见槽位」铺的一张连续网格，而分类布局在中间插入了整行横幅，
     * 两者必然错位 —— 物品看起来压出格子、数量数字骑在边框上，就是这个原因。
     * 这里干掉它的背景，改成按我们自己的布局逐格绘制。
     */
    @Inject(method = "drawSlotBg", at = @At("HEAD"), cancellable = true)
    private void sbaddon$drawSlotBg(GuiGraphics graphics, int left, int top, int slotsCount, CallbackInfo ci) {
        // 只看"分类视图是否接管"，不看有没有格子：
        // 所有分类都收起时一个格子都不该画，但原生那块灰底照样会画出来，看着像一片空格子
        if (sbaddon$layoutApplied) {
            ci.cancel();
        }
    }

    /**
     * 样本槽里真正存的是玩家拖进来的样本物品，但界面上要显示该模组**创造标签页的图标**。
     * <p>
     * SB 重写了 {@code renderSlot}，页签控件又画在槽位之前，槽位物品会盖住我们画的图标，
     * 所以这里直接掐掉样本槽的自身渲染，图标由 {@link ClassifyTab} 自己画。
     */
    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void sbaddon$hideSampleSlotItem(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        if (slot instanceof ClassifyUpgradeContainer.SampleSlot && ClassifyTab.getActiveTab() != null) {
            ci.cancel();
        }
    }

    /** 大背包带滚动面板时 SB 不调 drawSlotBg，所以统一在 renderBg 末尾自己铺格子。 */
    @Inject(method = "renderBg", at = @At("TAIL"))
    private void sbaddon$drawAlignedSlotBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY,
                                           CallbackInfo ci) {
        if (sbaddon$placements.isEmpty()) {
            return;
        }
        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        int left = self.getGuiLeft();
        int top = self.getGuiTop();
        for (ClassifyLayout.Placement placement : sbaddon$placements) {
            // 槽位坐标天然比格子边框内缩 1px，这里补回去
            net.p3pp3rf1y.sophisticatedcore.client.gui.utils.GuiHelper.renderSlotsBackground(
                    graphics, left + placement.x() - 1, top + placement.y() - 1, 1, 1);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        // 先问打开的分类页签：鼠标落在它的列表上就只滚列表，别带动背包网格
        ClassifyTab tab = ClassifyTab.getActiveTab();
        if (tab != null && tab.scrollList(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
            return;
        }
        // 按住 Shift 时**不消费**这次滚轮：直接返回，让原方法继续往下走。
        // 关键是不能 setReturnValue(true) —— 那会掐断方法体，连带跳过 super.mouseScrolled，
        // 而 NeoForge 的 ScreenEvent.MouseScrolled.Post 是在 super 里发的（鼠标手势等模组
        // 靠它做 Shift+滚轮 ±1）。分类视图里槽位坐标由我们每帧根据 sbaddon$scroll 重算，
        // 宿主自己滚一格不会反映到画面上，所以"放权"和"不滚动"在这里可以共存。
        if (Screen.hasShiftDown()) {
            // 让 SB 的滚动面板忽略这次滚轮（restrict = 只有鼠标压在滚动条上才允许滚），
            // 这样事件仍然沿 super 传下去给别的模组，但背包内容不会动。
            InventoryScrollPanel.setRestrictScrollToScrollbar(true);
            sbaddon$restoredScrollRestrict = true;
            return;
        }
        if (sbaddon$maxScroll <= 0) {
            return;
        }
        int delta = scrollY > 0 ? -1 : 1;
        sbaddon$scroll = Math.max(0, Math.min(sbaddon$maxScroll, sbaddon$scroll + delta));
        cir.setReturnValue(true);
    }

    /** 本次滚轮临时加的限制用完即撤，别影响后续正常滚动。 */
    @Inject(method = "mouseScrolled", at = @At("RETURN"))
    private void sbaddon$afterScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (sbaddon$restoredScrollRestrict) {
            InventoryScrollPanel.setRestrictScrollToScrollbar(false);
            sbaddon$restoredScrollRestrict = false;
        }
    }

    /**
     * 横幅画在 {@code renderBg} 末尾（order 大于格子背景那次注入，排在它后面），
     * 这样它只盖住背包底图和格子线；物品、文字、tooltip 都是之后画的，不会被横幅压住。
     * <p>
     * 早前注入在 {@code render} 的 TAIL，结果连 tooltip 都被横幅盖了 —— tooltip 虽然是
     * {@code render} 之后才提交的，但和横幅 blit 不在同一个渲染批次里，顺序不可控。
     */
    @Inject(method = "renderBg", at = @At("TAIL"), order = 1100)
    private void sbaddon$drawBanners(GuiGraphics graphics, float partialTick, int mouseX, int mouseY,
                                     CallbackInfo ci) {
        if (sbaddon$banners.isEmpty()) {
            return;
        }
        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        int left = self.getLeftX();
        int top = self.getTopY();
        Font font = Minecraft.getInstance().font;

        for (ClassifyLayout.Banner banner : sbaddon$banners) {
            // 格子背景是以「槽位坐标 - 1」为左上角画的 18×18（见 drawAlignedSlotBg），
            // 横幅这里同样外扩 1px，才会和整行格子的边框严丝合缝对齐；
            // 不外扩的话横幅会整体右移、下移 1px，视觉上就是"标签没对齐格子"。
            int x = left + banner.x() - 1;
            int drawY = sbaddon$bannerDrawY.getOrDefault(banner.id(), banner.y());
            int y = top + drawY - 1;
            int right = x + banner.width();
            int inset = ClassifyLayout.BANNER_INSET;
            // 纯代码绘制的像素横幅底（原图 25×25，中间横向平铺），四边各向内缩 3px
            int boxW = banner.width() - inset * 2;
            int boxH = ClassifyLayout.CELL - inset * 2;
            BannerBackground.render(graphics, x + inset, y + inset, boxW, boxH);
            if (!banner.icon().isEmpty()) {
                // 图标缩小 10% 后在 18×18 格子里水平垂直居中（16×0.9=14.4，两侧各留 1.8），
                // 再整体右移 10px，和文字一起往内让出左边距
                float iconSize = 16.0f * 0.9f;
                float offset = (ClassifyLayout.CELL - iconSize) / 2.0f + ICON_TEXT_SHIFT;
                graphics.pose().pushPose();
                graphics.pose().translate(x + offset, y + (ClassifyLayout.CELL - iconSize) / 2.0f, 0.0f);
                graphics.pose().scale(0.9f, 0.9f, 1.0f);
                graphics.renderItem(banner.icon(), 0, 0);
                graphics.pose().popPose();
            }
            // 横幅右端的展开/收起按钮：16×16 贴图在 18px 高的横幅里垂直居中
            int btnX = right - ExpandCollapseButton.SIZE - 2;
            int btnY = y + (ClassifyLayout.CELL - ExpandCollapseButton.SIZE) / 2;
            boolean btnHovered = ExpandCollapseButton.isOver(btnX, btnY, mouseX, mouseY);
            ExpandCollapseButton.render(graphics, btnX, btnY,
                    ClassifyCollapse.expandAmount(banner.id()), btnHovered, true);
            // 命中区记整条横幅（按钮在其中），点横幅任意位置都能切换展开/收起
            sbaddon$bannerHits.put(banner.id(),
                    new int[]{x, y, banner.width(), ClassifyLayout.CELL});

            // 模组名过长时按剩余宽度截断加省略号，不让文字冲出横幅（右侧要留出按钮的位置）
            String label = banner.label().getString();
            int textLeft = x + 20 + ICON_TEXT_SHIFT;
            int textWidth = Math.max(0, btnX - 3 - textLeft);
            if (font.width(label) > textWidth) {
                label = font.plainSubstrByWidth(label, Math.max(0, textWidth - font.width("..."))) + "...";
            }
            graphics.drawString(font, label, textLeft, y + 5, 0xFF1D2433, false);
        }
    }

    /**
     * 点击分类横幅切换该组的展开/收起。
     * <p>
     * 整条横幅都是热区（右侧箭头按钮只是视觉提示）：状态存在 {@link ClassifyCollapse} 里，
     * 重复点击就是来回切，动画每次都从当前箭头位置继续，不会跳。命中就吃掉这次点击，
     * 避免穿透到下面的槽位。
     * <p>
     * 命中区是上一帧渲染时写下的（{@code sbaddon$bannerHits}），坐标已经是屏幕坐标。
     * 没有横幅（一条都没展开过 / 分类视图未接管）时直接放行，不影响原版点击。
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sbaddon$toggleGroup(double mouseX, double mouseY, int button,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || sbaddon$bannerHits.isEmpty()) {
            return;
        }
        for (Map.Entry<String, int[]> entry : sbaddon$bannerHits.entrySet()) {
            int[] hit = entry.getValue();
            if (mouseX >= hit[0] && mouseX < hit[0] + hit[2]
                    && mouseY >= hit[1] && mouseY < hit[1] + hit[3]) {
                ClassifyCollapse.toggle(entry.getKey());
                cir.setReturnValue(true);
                return;
            }
        }
    }

    /**
     * 配置页里样本槽画的图标是「模组创造标签页图标」，槽里装的却是玩家拖进去的那件物品，
     * 于是出现「图标是齿轮、提示写升降机滑轮」的错位。
     * <p>
     * 这里接管样本槽的 tooltip：改成「模组分类名 + 物品名」，
     * 第一行对应的就是图标所属的模组，两边就不打架了。
     */
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void sbaddon$classifySampleTooltip(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        Slot hovered = ((AbstractContainerScreenAccess) this).sbaddon$getHoveredSlot();
        if (!(hovered instanceof ClassifyUpgradeContainer.SampleSlot) || hovered.getItem().isEmpty()) {
            return;
        }
        ItemStack stack = hovered.getItem();
        List<Component> lines;
        List<Component> summary = CreateAttributeFilterCompat.summary(stack);
        if (summary != null) {
            // 属性过滤器：直接展示 Create 自己算出来的摘要（匹配模式 + 每条属性）
            lines = summary;
        } else {
            StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
            ClassifyUpgradeContainer container = self.getMenu() instanceof StorageContainerMenuBase<?> menu
                    ? sbaddon$findContainer(menu) : null;
            Map<String, String> names = container != null ? container.getCustomNames() : Map.of();
            lines = List.of(ClassifyGroups.describe(stack, names).label());
        }
        graphics.renderTooltip(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
        ci.cancel();
    }

    /**
     * 分类页「就地改名」的键盘入口。
     * <p>
     * 页签是普通 Widget，不是 Screen 的焦点控件，SB 也不会把键盘事件派发给页签 ——
     * 所以在这里（屏幕入口）先问一遍打开的分类页：正在改名就由它接管，屏幕不再处理，
     * 免得一边打字一边触发 E / Esc 之类的背包热键。
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sbaddon$renameKeyPressed(int keyCode, int scanCode, int modifiers,
                                          CallbackInfoReturnable<Boolean> cir) {
        ClassifyTab tab = ClassifyTab.getActiveTab();
        if (tab != null && tab.handleKeyPressed(keyCode, scanCode, modifiers)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void sbaddon$renameCharTyped(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        ClassifyTab tab = ClassifyTab.getActiveTab();
        if (tab != null && tab.handleCharTyped(codePoint, modifiers)) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private ClassifyUpgradeContainer sbaddon$findContainer(StorageContainerMenuBase<?> menu) {
        for (UpgradeContainerBase<?, ?> container : menu.getUpgradeContainers().values()) {
            if (container instanceof ClassifyUpgradeContainer classify) {
                return classify;
            }
        }
        return null;
    }
}
