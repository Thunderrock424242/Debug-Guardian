package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.world.WorldInspectionResult;
import com.thunder.debugguardian.debug.world.WorldIntegrityScanner;
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
    private static final DateTimeFormatter REPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
        WorldInspectionResult result = WorldIntegrityScanner.scan(worldDir);
        String timestamp = FILE_TIMESTAMP.format(LocalDateTime.now());
        Path reportFile = reportDir.resolve("autoscan-" + timestamp + ".txt");

        try {
            Files.createDirectories(reportDir);
            List<String> lines = result.buildReportLines(LocalDateTime.now(), REPORT_TIMESTAMP);
            Files.write(reportFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write automated world integrity report", e);
        }

        if (!result.errors().isEmpty() || !result.warnings().isEmpty()) {
            CrashRiskMonitor.Severity severity = result.errors().isEmpty()
                    ? CrashRiskMonitor.Severity.MEDIUM
                    : CrashRiskMonitor.Severity.CRITICAL;
            CrashRiskMonitor.recordSymptom(
                    "world-integrity",
                    severity,
                    "World scan summary: " + result.summaryCounts()
            );
        }

        String summary = result.summaryCounts();
        if (!result.errors().isEmpty()) {
            DebugGuardian.LOGGER.error(
                    "Automated world scan detected issues: {}. Report saved to {}",
                    summary,
                    reportFile.toAbsolutePath()
            );
        } else if (!result.warnings().isEmpty()) {
            DebugGuardian.LOGGER.warn(
                    "Automated world scan produced warnings: {}. Report saved to {}",
                    summary,
                    reportFile.toAbsolutePath()
            );
        } else {
            DebugGuardian.LOGGER.info(
                    "Automated world scan completed cleanly. Report saved to {}",
                    reportFile.toAbsolutePath()
            );
        }
    }
}
