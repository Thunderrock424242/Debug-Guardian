package com.thunder.debugguardian.debug.CrashHelper;

import com.thunder.debugguardian.config.DebugGuardianConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.LogEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class LiveLogMonitor {
    private static final Path outputLog = FMLPaths.GAMEDIR.get().resolve("logs/runtime_issues.log");
    private static final Set<String> sessionReportedErrors = new HashSet<>();
    private static final Map<String, String> errorMessages = new LinkedHashMap<>() {{
        put("Mixin apply failed",            "A mod failed to apply a Mixin. This could cause crashes or broken features.");
        put("ClassNotFoundException",        "A required class is missing. This usually means a dependency is missing or outdated.");
        put("NoClassDefFoundError",          "A mod tried to use a class that failed to load. This can happen due to a missing or mismatched mod.");
        put("OutOfMemoryError",              "The game has run out of memory. Try allocating more RAM or reducing mod load.");
        put("NullPointerException",          "A mod ran into a null value where it shouldn't. This may cause instability or crashes.");
        put("Exception in server tick loop", "A fatal error occurred during world tick. This may crash or corrupt the world.");
    }};

    /** Call once during mod initialization (after config is registered). */
    public static void start() {
        resetLog();

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getRootLogger().addAppender(new IssueCatchingAppender("LiveLogMonitor"));
    }

    private static void resetLog() {
        try {
            Files.createDirectories(outputLog.getParent());
            Files.writeString(outputLog, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Debug Guardian] Could not reset runtime log: " + e.getMessage());
        }
    }

    public static class IssueCatchingAppender extends AbstractAppender {
        protected IssueCatchingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false);
            start();
        }

        @Override
        public void append(LogEvent event) {
            String msg = event.getMessage().getFormattedMessage();
            boolean known = false;

            for (Map.Entry<String, String> entry : errorMessages.entrySet()) {
                String key    = entry.getKey();
                String advice = entry.getValue();

                if (msg.contains(key) && sessionReportedErrors.add(key)) {
                    writeToLog("[" + key + "] " + msg);
                    sendToChat(msg, advice, DebugGuardianConfig.reportLink);
                    known = true;
                    break;
                }
            }

            if (!known && event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) {
                writeToLog("[UNCLASSIFIED] " + msg);
            }
        }

        private void writeToLog(String message) {
            try {
                Files.writeString(
                        outputLog,
                        "[" + Instant.now() + "] " + message + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                System.err.println("[Debug Guardian] Failed to write runtime issue: " + e.getMessage());
            }
        }

        private void sendToChat(String logLine, String warningMessage, String reportUrl) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            mc.execute(() -> {
                mc.player.sendSystemMessage(Component.literal("§c[Debug Guardian] A potential error was detected:"));
                mc.player.sendSystemMessage(Component.literal("§7" + logLine));
                mc.player.sendSystemMessage(Component.literal("§6" + warningMessage));
                mc.player.sendSystemMessage(
                        Component.literal("§9[Click here to report the issue]")
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, reportUrl))
                                        .withUnderlined(true)
                                )
                );
            });
        }
    }

    /**
     * Call this to capture and report a caught Throwable.
     */
    public static void captureThrowable(Throwable thrown) {
        StringBuilder sb = new StringBuilder(thrown.toString()).append("\n");
        for (StackTraceElement el : thrown.getStackTrace()) {
            sb.append("    at ").append(el).append("\n");
        }

        try {
            Files.writeString(
                    outputLog,
                    "[" + Instant.now() + "] " + sb + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("[Debug Guardian] Failed to write throwable: " + e.getMessage());
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            mc.execute(() -> {
                mc.player.sendSystemMessage(Component.literal("§c[Debug Guardian] A fatal error occurred:"));
                mc.player.sendSystemMessage(Component.literal(
                        "§7" + thrown.getClass().getSimpleName() + ": " + thrown.getMessage()
                ));
                mc.player.sendSystemMessage(Component.literal("§ePlease report it to the modpack developer."));
                mc.player.sendSystemMessage(
                        Component.literal("§9[Click here to report the issue]")
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent(
                                                ClickEvent.Action.OPEN_URL,
                                                DebugGuardianConfig.reportLink
                                        ))
                                        .withUnderlined(true)
                                )
                );
            });
        }
    }
}
