package com.huanj.sbaddon.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 把控件挂进 Screen 的 children / renderables。
 * <p>
 * {@code Screen#addRenderableWidget} 是 protected，页签不是 Screen 调不到；
 * 走 {@link Invoker} 打开即可，比自己维护一份 children 列表稳 ——
 * 挂进去的控件能正常收到 Screen 的键盘事件分发（改名输入框靠这个）。
 */
@Mixin(Screen.class)
public interface ScreenAccess {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T sbaddon$addRenderableWidget(T widget);
}
