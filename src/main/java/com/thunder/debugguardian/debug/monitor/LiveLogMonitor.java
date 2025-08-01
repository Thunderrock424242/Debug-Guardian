package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.errors.ErrorTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.LogEvent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LiveLogMonitor {
    private static final Path RUNTIME_LOG =
            FMLPaths.GAMEDIR.get().resolve("logs/runtime_issues.log");
    private static final Set<String> seenErrors = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> errorClassifications = new LinkedHashMap<>();

    static {
        errorClassifications.put("Mixin apply failed",
                "A mod failed to apply a mixin; features may break.");
        errorClassifications.put("ClassNotFoundException",
                "Missing class; a dependency may be absent or outdated.");
        errorClassifications.put("NoClassDefFoundError",
                "Failed to load a class; check for mod mismatches.");
        errorClassifications.put("OutOfMemoryError",
                "Ran out of memory; consider allocating more RAM.");
        errorClassifications.put("NullPointerException",
                "Unexpected null value; mod instability possible.");
        errorClassifications.put("Exception in server tick loop",
                "Fatal error during world tick; risk of corruption.");
    }

    public static void start() {
        if (!DebugConfig.get().loggingEnableLiveMonitor) return;
        resetLog();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig root = ctx.getConfiguration().getRootLogger();
        LiveAppender appender = new LiveAppender("LiveLogMonitor");
        root.addAppender(appender, null, null);
        ctx.updateLoggers();
    }

    private static void resetLog() {
        try {
            Files.createDirectories(RUNTIME_LOG.getParent());
            Files.writeString(RUNTIME_LOG, "",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Could not reset runtime log", e);
        }
    }

    private static class LiveAppender extends AbstractAppender {
        protected LiveAppender(String name) {
            super(name, new ErrorTracker(),
                    PatternLayout.newBuilder().withPattern("%m").build(), false);
            start();
        }

        @Override
        public void append(LogEvent event) {
            String msg = event.getMessage().getFormattedMessage();
            String classification = null;
            String matchedKey   = null;

            // find first matching classification
            for (Map.Entry<String, String> e : errorClassifications.entrySet()) {
                if (msg.contains(e.getKey()) && seenErrors.add(e.getKey())) {
                    classification = e.getValue();
                    matchedKey     = e.getKey();
                    break;
                }
            }

            if (classification != null) {
                // only detect culprit for class-loading errors
                String culprit = "";
                if ("ClassNotFoundException".equals(matchedKey)
                        || "NoClassDefFoundError".equals(matchedKey)) {
                    culprit = ClassLoadingIssueDetector
                            .identifyCulpritMod(event.getThrown());
                }

                // build log entry
                String logEntry = "[" + Instant.now() + "] " + msg;
                if (!culprit.isEmpty()) {
                    logEntry += " (requested by: " + culprit + ")";
                }
                write(RUNTIME_LOG, logEntry);

                // build chat notification
                String adviceMsg = classification
                        + (culprit.isEmpty()
                        ? ""
                        : "\n§7(requested by: " + culprit + ")");
                notifyPlayer(msg, adviceMsg, buildReportUrl());
            }
            else if (event.getLevel().isMoreSpecificThan(
                    org.apache.logging.log4j.Level.ERROR)) {
                write(RUNTIME_LOG, "[UNCLASSIFIED] ["
                        + Instant.now() + "] " + msg);
            }
        }
    }

    private static void write(Path path, String line) {
        try {
            Files.writeString(path, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Debug Guardian] Failed writing runtime log: "
                    + e.getMessage());
        }
    }

    private static void notifyPlayer(String logLine, String advice,
                                     String reportUrl) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        mc.execute(() -> {
            mc.player.sendSystemMessage(
                    Component.literal("§c[Debug Guardian] Detected: " + logLine));
            mc.player.sendSystemMessage(Component.literal("§6" + advice));
            mc.player.sendSystemMessage(
                    Component.literal("§9[Report issue]")
                            .withStyle(style -> style
                                    .withClickEvent(
                                            new ClickEvent(
                                                    ClickEvent.Action.OPEN_URL, reportUrl))
                                    .withUnderlined(true)
                            )
            );
        });
    }

    public static void captureThrowable(Throwable thrown) {
        String culprit = ClassLoadingIssueDetector
                .identifyCulpritMod(thrown);
        StringBuilder sb = new StringBuilder(thrown.toString())
                .append("\n( requested by: ").append(culprit).append(" )\n");
        for (StackTraceElement el : thrown.getStackTrace()) {
            sb.append("    at ").append(el).append("\n");
        }
        write(RUNTIME_LOG, "[" + Instant.now() + "] " + sb.toString());

        String shortMsg = thrown.getClass().getSimpleName()
                + ": " + thrown.getMessage();
        notifyPlayer(
                shortMsg,
                "A fatal error occurred; see log for details.\n§7(requested by: "
                        + culprit + ")",
                buildReportUrl()
        );
    }

    private static String buildReportUrl() {
        return "https://github.com/"
                + DebugConfig.get().reportingGithubRepository
                + "/issues/new";
    }
}