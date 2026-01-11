package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors for JVM-level thread deadlocks and writes a detailed report when detected.
 */
public final class DeadlockDetector {
    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    private static final Path DUMP_DIR = FMLPaths.GAMEDIR.get().resolve("debugguardian");
    private static final long CHECK_INTERVAL_SECONDS = 10L;

    private static ScheduledExecutorService executor;
    private static volatile boolean reported;

    private DeadlockDetector() {
    }

    public static void start() {
        synchronized (DeadlockDetector.class) {
            if (executor != null && !executor.isShutdown()) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "debugguardian-deadlock");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(DeadlockDetector::checkDeadlock,
                    CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    public static void stop() {
        synchronized (DeadlockDetector.class) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        reported = false;
    }

    private static void checkDeadlock() {
        long[] deadlocked = BEAN.findDeadlockedThreads();
        if (deadlocked == null || deadlocked.length == 0) {
            reported = false;
            return;
        }
        if (reported) {
            return;
        }

        ThreadInfo[] infos = BEAN.getThreadInfo(deadlocked, true, true);
        if (infos == null || infos.length == 0) {
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path reportFile = DUMP_DIR.resolve("deadlock-" + timestamp + ".log");
        try {
            Files.createDirectories(DUMP_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(reportFile, StandardOpenOption.CREATE_NEW)) {
                writer.write("Deadlock detected at " + timestamp + " with " + infos.length + " thread(s).\n");
                for (ThreadInfo info : infos) {
                    StackTraceElement[] stack = info.getStackTrace();
                    String mod = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                    writer.write("Thread: " + info.getThreadName()
                            + " state: " + info.getThreadState()
                            + " mod: " + mod + "\n");
                    LockInfo lock = info.getLockInfo();
                    if (lock != null) {
                        writer.write("  Waiting on: " + lock + "\n");
                    }
                    if (info.getLockOwnerName() != null) {
                        writer.write("  Lock owner: " + info.getLockOwnerName()
                                + " (id " + info.getLockOwnerId() + ")\n");
                    }
                    for (StackTraceElement element : stack) {
                        writer.write("    at " + element + "\n");
                    }
                    writer.newLine();
                }
            }
            DebugGuardian.LOGGER.error("Thread deadlock detected; report written to {}", reportFile);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write deadlock report", e);
        }

        CrashRiskMonitor.recordSymptom(
                "deadlock",
                CrashRiskMonitor.Severity.CRITICAL,
                "Thread deadlock detected"
        );
        reported = true;
    }
}
