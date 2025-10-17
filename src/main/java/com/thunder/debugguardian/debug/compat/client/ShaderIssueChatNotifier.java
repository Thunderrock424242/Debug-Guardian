package com.thunder.debugguardian.debug.compat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side helper for surfacing shader-specific compatibility alerts in chat.
 */
@OnlyIn(Dist.CLIENT)
public final class ShaderIssueChatNotifier {
    private ShaderIssueChatNotifier() {
    }

    public static void warn(String title, String details) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§c[Debug Guardian] Shader conflict: " + title));
                mc.player.sendSystemMessage(Component.literal("§6" + details));
            }
        });
    }
}
