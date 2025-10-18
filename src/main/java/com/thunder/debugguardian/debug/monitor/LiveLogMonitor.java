package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.errors.ErrorTracker;
import com.thunder.debugguardian.debug.monitor.client.LogNotificationSender;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

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
    private static final ClientNotifier CLIENT_NOTIFIER;

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
        errorClassifications.put("Missing registry",
                "A registry entry could not be found; check mod versions.");
        errorClassifications.put("Duplicate mod",
                "Duplicate mod IDs detected; remove extras.");
        errorClassifications.put("Invalid config",
                "A configuration file failed to load; verify its contents.");
        errorClassifications.put("Failed to handle handshake",
                "Network handshake failed; a mod may be incompatible.");
        errorClassifications.put("Resource reload failed",
                "A resource pack failed to reload; check data or assets.");
        errorClassifications.put("Registry remapping failed",
                "Registry remapping failed during world load; check mod updates.");
        errorClassifications.put("Error generating chunk",
                "A chunk failed to generate; world may be corrupted.");
        errorClassifications.put("Failed to save chunk",
                "Chunk data failed to save; check disk health or mods.");
    }

    static {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CLIENT_NOTIFIER = LogNotificationSender::notifyPlayer;
        } else {
            CLIENT_NOTIFIER = (logLine, advice, reportUrl) -> {
            };
        }
    }

    public static void start() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
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
                    PatternLayout.newBuilder().withPattern("%m").build(), false,
                    Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            String sourceMod = identifySourceMod(event);
            if (!DebugConfig.isModLogOutputEnabled(sourceMod)) {
                return;
            }

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
                String culprit = "Unknown".equals(sourceMod) ? "" : sourceMod;
                if (culprit.isEmpty()
                        && ("ClassNotFoundException".equals(matchedKey)
                        || "NoClassDefFoundError".equals(matchedKey))) {
                    culprit = ClassLoadingIssueDetector
                            .identifyCulpritMod(event.getThrown());
                    if ("Unknown".equals(culprit)) {
                        culprit = "";
                    }
                }

                // build log entry
                String logEntry = "[" + Instant.now() + "] " + msg;
                if (!culprit.isEmpty()) {
                    logEntry += " (requested by: " + culprit + ")";
                }
                write(logEntry);

                // build chat notification
                String adviceMsg = classification
                        + (culprit.isEmpty()
                        ? ""
                        : "\n§7(requested by: " + culprit + ")");
                sendClientNotification(msg, adviceMsg, buildReportUrl());
                CrashRiskMonitor.recordSymptom(
                        "log-" + matchedKey,
                        severityForKey(matchedKey),
                        classification + (culprit.isEmpty() ? "" : " (" + culprit + ")")
                );
            }
            else if (event.getLevel().isMoreSpecificThan(
                    org.apache.logging.log4j.Level.ERROR)) {
                write("[UNCLASSIFIED] ["
                        + Instant.now() + "] " + msg);
                String snippet = msg.length() > 120 ? msg.substring(0, 117) + "..." : msg;
                CrashRiskMonitor.recordSymptom(
                        "log-unclassified",
                        CrashRiskMonitor.Severity.MEDIUM,
                        "Unclassified error: " + snippet
                );
            }
        }
    }

    private static void write(String line) {
        try {
            Files.writeString(LiveLogMonitor.RUNTIME_LOG, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Debug Guardian] Failed writing runtime log: "
                    + e.getMessage());
        }
    }

    private static CrashRiskMonitor.Severity severityForKey(String key) {
        if (key == null) {
            return CrashRiskMonitor.Severity.MEDIUM;
        }
        return switch (key) {
            case "OutOfMemoryError", "Exception in server tick loop", "Missing registry",
                    "Failed to save chunk" -> CrashRiskMonitor.Severity.CRITICAL;
            case "ClassNotFoundException", "NoClassDefFoundError", "Mixin apply failed",
                    "Registry remapping failed" -> CrashRiskMonitor.Severity.HIGH;
            case "Failed to handle handshake", "Resource reload failed", "Invalid config" ->
                    CrashRiskMonitor.Severity.MEDIUM;
            default -> CrashRiskMonitor.Severity.MEDIUM;
        };
    }

    private static void sendClientNotification(String logLine, String advice, String reportUrl) {
        CLIENT_NOTIFIER.notify(logLine, advice, reportUrl);
    }

    @FunctionalInterface
    private interface ClientNotifier {
        void notify(String logLine, String advice, String reportUrl);
    }

    public static void captureThrowable(Throwable thrown) {
        String culprit = ClassLoadingIssueDetector
                .identifyCulpritMod(thrown);
        if (!DebugConfig.isModLogOutputEnabled(culprit)) {
            return;
        }
        StringBuilder sb = new StringBuilder(thrown.toString())
                .append("\n( requested by: ").append(culprit).append(" )\n");
        for (StackTraceElement el : thrown.getStackTrace()) {
            sb.append("    at ").append(el).append("\n");
        }
        write("[" + Instant.now() + "] " + sb.toString());

        String shortMsg = thrown.getClass().getSimpleName()
                + ": " + thrown.getMessage();
        sendClientNotification(
                shortMsg,
                "A fatal error occurred; see log for details.\n§7(requested by: "
                        + culprit + ")",
                buildReportUrl()
        );
        CrashRiskMonitor.recordSymptom(
                "log-crash-" + thrown.getClass().getSimpleName(),
                CrashRiskMonitor.Severity.CRITICAL,
                "Uncaught exception: " + shortMsg
        );
    }

    private static String buildReportUrl() {
        return "https://github.com/"
                + DebugConfig.get().reportingGithubRepository
                + "/issues/new";
    }

    private static String identifySourceMod(LogEvent event) {
        if (event == null) {
            return "Unknown";
        }
        if (event.getThrown() != null) {
            String culprit = ClassLoadingIssueDetector
                    .identifyCulpritMod(event.getThrown());
            if (!"Unknown".equals(culprit)) {
                return culprit;
            }
        }
        ReadOnlyStringMap contextData = event.getContextData();
        if (contextData != null && !contextData.isEmpty()) {
            String modId = contextData.getValue("modId");
            if (modId == null) {
                modId = contextData.getValue("modid");
            }
            if (modId != null && !modId.isBlank() && !"Unknown".equalsIgnoreCase(modId)) {
                return modId;
            }
        }
        String loggerName = event.getLoggerName();
        String fromLogger = ClassLoadingIssueDetector.identifyModByLoggerName(loggerName);
        if (!"Unknown".equals(fromLogger)) {
            return fromLogger;
        }
        StackTraceElement source = event.getSource();
        if (source != null) {
            String fromSource = ClassLoadingIssueDetector
                    .identifyCulpritMod(new StackTraceElement[]{source});
            if (!"Unknown".equals(fromSource)) {
                return fromSource;
            }
        }
        return "Unknown";
    }
}
