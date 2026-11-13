package com.huanj.sbaddon.client;

import com.huanj.sbaddon.SBAddon;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 演示屏入口：F8 打开。纯验证用，删掉演示屏时连同这个类一起删。
 * <p>
 * 注意：按键注册（RegisterKeyMappingsEvent）在 MOD 总线上，写在 {@link ClientSetup} 里；
 * 这个类挂在默认的 GAME 总线上收按键。
 */
@EventBusSubscriber(modid = SBAddon.MOD_ID, value = Dist.CLIENT)
public final class ClientKeybinds {

    public static final String CATEGORY = "key.categories.sbaddon";

    public static final KeyMapping OPEN_DEMO = new KeyMapping(
            "key.sbaddon.toggle_demo",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            CATEGORY);

    private ClientKeybinds() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_DEMO.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new ExpandCollapseDemoScreen());
            }
        }
    }
}
