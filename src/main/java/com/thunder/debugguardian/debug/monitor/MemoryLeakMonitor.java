package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Watches heap usage and warns when memory remains
 * consistently high, hinting at a potential leak.
 */
public class MemoryLeakMonitor {
    private static final String THREAD_NAME = "debugguardian-memory-leak";
    private static int highUsageStreak = 0;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> scheduledTask;

    public static synchronized void start() {
        ensureScheduler();
        reschedule();
    }

    public static synchronized void reloadFromConfig() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        reschedule();
    }

    public static synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        highUsageStreak = 0;
    }

    private static void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, THREAD_NAME);
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static void reschedule() {
        int interval = Math.max(1, DebugConfig.get().memoryLeakCheckIntervalSeconds);
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = scheduler.scheduleAtFixedRate(
                MemoryLeakMonitor::checkMemory,
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    private static void checkMemory() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long used = mem.getHeapMemoryUsage().getUsed();
        long max = mem.getHeapMemoryUsage().getMax();
        if (max <= 0) {
            return;
        }
        double ratio = (double) used / max;
        DebugConfig config = DebugConfig.get();
        double warnRatio = config.memoryLeakWarnRatio;
        int warnStreak = Math.max(1, config.memoryLeakWarnStreak);

        if (ratio > warnRatio) {
            highUsageStreak++;
            if (highUsageStreak >= warnStreak) {
                DebugGuardian.LOGGER.warn(
                        "Possible memory leak: heap usage at {}% for {} checks (threshold {}% for {} checks)",
                        Math.round(ratio * 100), highUsageStreak,
                        Math.round(warnRatio * 100), warnStreak
                );
                CrashRiskMonitor.recordSymptom(
                        "memory-leak",
                        CrashRiskMonitor.Severity.HIGH,
                        "Heap usage > " + Math.round(ratio * 100) + "% for " + highUsageStreak
                                + " checks (threshold " + Math.round(warnRatio * 100) + "% for "
                                + warnStreak + " checks)"
                );
            }
        } else {
            if (highUsageStreak > 0) {
                DebugGuardian.LOGGER.info(
                        "Heap usage recovered after {} high-usage checks",
                        highUsageStreak
                );
            }
            highUsageStreak = 0;
        }
    }
}
