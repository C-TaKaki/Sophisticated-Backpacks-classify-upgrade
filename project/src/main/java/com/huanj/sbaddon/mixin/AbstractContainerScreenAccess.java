package com.huanj.sbaddon.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读一下当前鼠标悬停在哪个槽位上。
 * <p>
 * {@code hoveredSlot} 是 {@code AbstractContainerScreen} 的 protected 字段，
 * {@code StorageScreenBase} 继承它但 mixin 不能直接 @Shadow 父类字段，只能走访问器。
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccess {

    @Accessor("hoveredSlot")
    Slot sbaddon$getHoveredSlot();
}
