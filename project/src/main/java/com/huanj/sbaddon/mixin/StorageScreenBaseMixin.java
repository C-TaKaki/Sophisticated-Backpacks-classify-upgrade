package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.BannerBackground;
import com.huanj.sbaddon.client.ClassifyTab;
import com.huanj.sbaddon.client.layout.ClassifyGroups;
import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.compat.create.CreateAttributeFilterCompat;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
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

    /** 上一帧是否真的套用过分类布局（用来判断需不需要把槽位还给 SB）。 */
    @Unique
    private boolean sbaddon$layoutApplied;

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

        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        AbstractContainerMenu rawMenu = self.getMenu();
        if (!(rawMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        ClassifyUpgradeContainer container = sbaddon$findContainer(menu);
        boolean active = container != null && container.isUpgradeEnabled();
        if (!active) {
            // 关闭分类或升级被拿掉：把我们藏起来的槽位还给 SB，网格恢复原版排版
            if (sbaddon$layoutApplied) {
                sbaddon$layoutApplied = false;
                sbaddon$originX = Integer.MIN_VALUE;
                sbaddon$appliedSlot0X = Integer.MIN_VALUE;
                sbaddon$scroll = 0;
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
        if (slot0.x != sbaddon$appliedSlot0X || slot0.y != sbaddon$appliedSlot0Y) {
            // 首帧，或 SB 刚把槽位重设为自然位置（init / resize）
            sbaddon$originX = slot0.x;
            sbaddon$originY = slot0.y;
            sbaddon$scroll = 0;
        }
        if (sbaddon$originX == Integer.MIN_VALUE) {
            return;
        }

        Predicate<net.minecraft.world.item.ItemStack> filter = self.getStackFilter();
        ClassifyLayout.Result result = ClassifyLayout.compute(
                menu, container, cols, rows, sbaddon$scroll, filter, sbaddon$originX, sbaddon$originY);

        sbaddon$maxScroll = Math.max(0, result.totalRows() - rows);
        if (sbaddon$scroll > sbaddon$maxScroll) {
            sbaddon$scroll = sbaddon$maxScroll;
            result = ClassifyLayout.compute(
                    menu, container, cols, rows, sbaddon$scroll, filter, sbaddon$originX, sbaddon$originY);
        }

        for (int i = 0; i < storageSlots; i++) {
            Slot slot = menu.getSlot(i);
            slot.x = SBADDON_HIDDEN;
            slot.y = SBADDON_HIDDEN;
        }
        for (ClassifyLayout.Placement placement : result.placements()) {
            Slot slot = menu.getSlot(placement.slotIndex());
            slot.x = placement.x();
            slot.y = placement.y();
        }

        sbaddon$appliedSlot0X = slot0.x;
        sbaddon$appliedSlot0Y = slot0.y;
        sbaddon$banners = result.banners();
        sbaddon$placements = result.placements();
        sbaddon$layoutApplied = true;
    }

    /**
     * SB 的格子背景是按「所有可见槽位」铺的一张连续网格，而分类布局在中间插入了整行横幅，
     * 两者必然错位 —— 物品看起来压出格子、数量数字骑在边框上，就是这个原因。
     * 这里干掉它的背景，改成按我们自己的布局逐格绘制。
     */
    @Inject(method = "drawSlotBg", at = @At("HEAD"), cancellable = true)
    private void sbaddon$drawSlotBg(GuiGraphics graphics, int left, int top, int slotsCount, CallbackInfo ci) {
        if (!sbaddon$placements.isEmpty()) {
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
        if (sbaddon$maxScroll <= 0) {
            return;
        }
        int delta = scrollY > 0 ? -1 : 1;
        sbaddon$scroll = Math.max(0, Math.min(sbaddon$maxScroll, sbaddon$scroll + delta));
        cir.setReturnValue(true);
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
            int y = top + banner.y() - 1;
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
            // 模组名过长时按剩余宽度截断加省略号，不让文字冲出横幅
            String label = banner.label().getString();
            int textLeft = x + 20 + ICON_TEXT_SHIFT;
            int textWidth = Math.max(0, right - textLeft - 2);
            if (font.width(label) > textWidth) {
                label = font.plainSubstrByWidth(label, Math.max(0, textWidth - font.width("..."))) + "...";
            }
            graphics.drawString(font, label, textLeft, y + 5, 0xFF1D2433, false);
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
