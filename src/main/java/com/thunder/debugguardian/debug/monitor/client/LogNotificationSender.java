package com.thunder.debugguardian.debug.monitor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Client-side helpers for surfacing live log alerts to the player.
 */
public final class LogNotificationSender {
    private LogNotificationSender() {
    }

    public static void notifyPlayer(String logLine, String advice, String reportUrl) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        mc.execute(() -> {
            mc.player.sendSystemMessage(
                    Component.literal("§c[Debug Guardian] Detected: " + logLine));
            mc.player.sendSystemMessage(Component.literal("§6" + advice));
            mc.player.sendSystemMessage(
                    Component.literal("§9[Report issue]")
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent(
                                            ClickEvent.Action.OPEN_URL, reportUrl))
                                    .withUnderlined(true)
                            )
            );
        });
    }
}
