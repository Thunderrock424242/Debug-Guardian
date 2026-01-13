package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Automatically scans the active world when the dedicated/server instance is
 * starting. Results are logged to disk and surfaced through the crash risk
 * monitor so administrators are alerted to corruption early.
 */
@EventBusSubscriber(modid = MOD_ID)
public final class WorldIssueMonitor {
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService executor;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("Summary:\\s*(\\d+)\\s+critical,\\s*(\\d+)\\s+warnings");

    private WorldIssueMonitor() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!DebugConfig.get().worldAutoScanOnStart) {
            return;
        }
        Path worldDir = event.getServer().getWorldPath(LevelResource.ROOT);
        Path reportDir = FMLPaths.GAMEDIR.get().resolve("debugguardian").resolve("worldchecks");
        try {
            getExecutor().submit(() -> runScan(worldDir, reportDir));
        } catch (RejectedExecutionException e) {
            DebugGuardian.LOGGER.warn("Automated world scan skipped because the executor is shutting down.", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private static ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "debugguardian-world-autoscan");
                    t.setDaemon(true);
                    return t;
                });
            }
            return executor;
        }
    }

    private static void runScan(Path worldDir, Path reportDir) {
        String timestamp = FILE_TIMESTAMP.format(LocalDateTime.now());
        Path reportFile = reportDir.resolve("autoscan-" + timestamp + ".txt");

        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to create automated world integrity report directory", e);
            return;
        }

        Process process;
        try {
            process = new ProcessBuilder(
                    "java",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "com.thunder.debugguardian.debug.external.WorldCheckHelper",
                    worldDir.toAbsolutePath().toString(),
                    reportFile.toAbsolutePath().toString()
            )
                    .inheritIO()
                    .start();
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to launch automated world scan helper", e);
            return;
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DebugGuardian.LOGGER.warn("Automated world scan was interrupted");
            return;
        }

        Summary summary = readSummary(reportFile);
        if (summary == null) {
            DebugGuardian.LOGGER.warn(
                    "Automated world scan completed with exit code {} but no summary could be read. Report: {}",
                    exitCode,
                    reportFile.toAbsolutePath()
            );
            return;
        }

        String summaryCounts = summary.summaryCounts();
        if (summary.errors > 0 || summary.warnings > 0) {
            CrashRiskMonitor.Severity severity = summary.errors == 0
                    ? CrashRiskMonitor.Severity.MEDIUM
                    : CrashRiskMonitor.Severity.CRITICAL;
            CrashRiskMonitor.recordSymptom(
                    "world-integrity",
                    severity,
                    "World scan summary: " + summaryCounts
            );
        }

        if (summary.errors > 0) {
            DebugGuardian.LOGGER.error(
                    "Automated world scan detected issues: {}. Report saved to {}",
                    summaryCounts,
                    reportFile.toAbsolutePath()
            );
        } else if (summary.warnings > 0) {
            DebugGuardian.LOGGER.warn(
                    "Automated world scan produced warnings: {}. Report saved to {}",
                    summaryCounts,
                    reportFile.toAbsolutePath()
            );
        } else {
            DebugGuardian.LOGGER.info(
                    "Automated world scan completed cleanly. Report saved to {}",
                    reportFile.toAbsolutePath()
            );
        }
    }

    private static Summary readSummary(Path reportFile) {
        if (Files.notExists(reportFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(reportFile, StandardCharsets.UTF_8);
            Integer errors = null;
            Integer warnings = null;
            for (String line : lines) {
                if (errors == null) {
                    Matcher summaryMatch = SUMMARY_PATTERN.matcher(line);
                    if (summaryMatch.find()) {
                        errors = Integer.parseInt(summaryMatch.group(1));
                        warnings = Integer.parseInt(summaryMatch.group(2));
                        continue;
                    }
                }
                if (errors != null && warnings != null) {
                    break;
                }
            }
            if (errors == null || warnings == null) {
                return null;
            }
            return new Summary(errors, warnings);
        } catch (IOException e) {
            DebugGuardian.LOGGER.warn("Failed to read automated world scan report", e);
            return null;
        }
    }

    private static final class Summary {
        private final int errors;
        private final int warnings;
        private Summary(int errors, int warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }

        private String summaryCounts() {
            return errors + " critical, " + warnings + " warnings";
        }
    }
}
