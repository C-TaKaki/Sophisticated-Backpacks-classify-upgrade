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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

/**
 * 把背包网格重排成「按模组分块 + 整行分类横幅」。
 * <p>
 * 只改客户端的槽位坐标，不动菜单数据：
 * <ul>
 *   <li>物品渲染、悬停、tooltip、点击、Shift 点击全部自动跟随槽位坐标，无需再接管。</li>
 *   <li>横幅行没有槽位，整行留给自绘的分类标题。</li>
 * </ul>
 */
@Mixin(StorageScreenBase.class)
public abstract class StorageScreenBaseMixin {

    @Unique
    private static final int SBADDON_HIDDEN = -10000;

    /** 背包网格原点（相对 GUI 左上角）。AT 只作用于 Minecraft 类，SB 的字段只能用 shadow 读。 */
    @Shadow
    @Final
    private static int SLOTS_X_OFFSET;

    @Shadow
    @Final
    private static int SLOTS_Y_OFFSET;

    @Unique
    private List<ClassifyLayout.Banner> sbaddon$banners = List.of();

    @Inject(method = "render", at = @At("HEAD"))
    private void sbaddon$applyLayout(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        sbaddon$banners = List.of();

        StorageScreenBase<?> self = (StorageScreenBase<?>) (Object) this;
        ClassifyUpgradeContainer container = sbaddon$findContainer(self);
        if (container == null) {
            return;
        }

        int cols = self.getSlotsOnLine();
        AbstractContainerMenu rawMenu = self.getMenu();
        if (cols <= 0 || !(rawMenu instanceof StorageContainerMenuBase<?> menu)) {
            return;
        }
        int storageSlots = menu.getNumberOfStorageInventorySlots();
        if (storageSlots <= 0) {
            return;
        }
        int rows = Math.max(1, (storageSlots + cols - 1) / cols);

        Predicate<net.minecraft.world.item.ItemStack> filter = self.getStackFilter();
        ClassifyLayout.Result result = ClassifyLayout.compute(
                menu, container, cols, rows, filter, SLOTS_X_OFFSET, SLOTS_Y_OFFSET);

        // 先全部隐藏，再按布局摆放；避免上一帧的残留位置
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

        sbaddon$banners = result.banners();
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
            graphics.fill(x, y, right, y + 17, 0x66202428);
            graphics.fill(x, y + 16, right, y + 17, 0x664A5560);
            if (!banner.icon().isEmpty()) {
                graphics.renderItem(banner.icon(), x + 1, y + 1);
            }
            graphics.drawString(font, banner.label(), x + 20, y + 5, 0xFFE6E6E6, false);
        }
    }

    @Unique
    private ClassifyUpgradeContainer sbaddon$findContainer(StorageScreenBase<?> self) {
        AbstractContainerMenu menu = self.getMenu();
        if (!(menu instanceof StorageContainerMenuBase<?> storageMenu)) {
            return null;
        }
        for (UpgradeContainerBase<?, ?> container : storageMenu.getUpgradeContainers().values()) {
            if (container instanceof ClassifyUpgradeContainer classify) {
                return classify;
            }
        }
        return null;
    }
}
