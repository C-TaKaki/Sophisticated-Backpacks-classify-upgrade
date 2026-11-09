package com.huanj.sbaddon.mixin;

import com.huanj.sbaddon.client.layout.ClassifyLayout;
import com.huanj.sbaddon.upgrade.ClassifyUpgradeContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
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

    @Inject(method = "render", at = @At("HEAD"))
    private void sbaddon$applyLayout(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        sbaddon$banners = List.of();
        sbaddon$maxScroll = 0;

        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        AbstractContainerMenu rawMenu = self.getMenu();
        if (!(rawMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        ClassifyUpgradeContainer container = sbaddon$findContainer(menu);
        if (container == null) {
            return;
        }

        int cols = self.getSlotsOnLine();
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        if (cols <= 0 || storageSlots <= 0) {
            return;
        }
        int rows = Math.max(1, (storageSlots + cols - 1) / cols);

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
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void sbaddon$onScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (sbaddon$maxScroll <= 0) {
            return;
        }
        int delta = scrollY > 0 ? -1 : 1;
        sbaddon$scroll = Math.max(0, Math.min(sbaddon$maxScroll, sbaddon$scroll + delta));
        cir.setReturnValue(true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void sbaddon$drawBanners(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (sbaddon$banners.isEmpty()) {
            return;
        }
        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        int left = self.getLeftX();
        int top = self.getTopY();
        Font font = Minecraft.getInstance().font;

        for (ClassifyLayout.Banner banner : sbaddon$banners) {
            int x = left + banner.x();
            int y = top + banner.y();
            int right = x + banner.width();
            int bottom = y + ClassifyLayout.CELL;
            // 不透明整行填充：把背景贴图里的格子边框线彻底盖掉，标签行不留任何框线
            graphics.fill(x, y, right, bottom, 0xFF2F353C);
            graphics.fill(x, y, right, y + 1, 0xFF454E57);
            graphics.fill(x, bottom - 1, right, bottom, 0xFF23282D);
            if (!banner.icon().isEmpty()) {
                graphics.renderItem(banner.icon(), x + 1, y + 1);
            }
            graphics.drawString(font, banner.label(), x + 20, y + 5, 0xFFE6E6E6, false);
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
