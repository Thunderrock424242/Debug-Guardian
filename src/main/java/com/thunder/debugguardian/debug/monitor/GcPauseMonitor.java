package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.monitor.CrashRiskMonitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks garbage collection pauses to warn about potential memory leaks
 * or misbehaving mods causing frequent full GCs.
 */
public class GcPauseMonitor {
    private static final String THREAD_NAME = "debugguardian-gc-monitor";
    private static long lastGcTime = 0;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> scheduledTask;

    public static synchronized void start() {
        if (!DebugConfig.get().gcPauseMonitorEnable) {
            stop();
            return;
        }
        ensureScheduler();
        reschedule();
    }

    public static synchronized void reloadFromConfig() {
        if (!DebugConfig.get().gcPauseMonitorEnable) {
            stop();
            return;
        }
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
        lastGcTime = 0;
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
        long interval = Math.max(1, DebugConfig.get().gcPauseCheckIntervalSeconds);
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = scheduler.scheduleAtFixedRate(
                GcPauseMonitor::checkGcPauses,
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    private static void checkGcPauses() {
        long totalGc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
        long delta = totalGc - lastGcTime;
        long warnMs = DebugConfig.get().gcPauseWarnMs;
        if (lastGcTime != 0 && delta > warnMs) {
            DebugGuardian.LOGGER.warn("Long GC pause detected: {} ms (threshold {} ms)", delta, warnMs);
            CrashRiskMonitor.recordSymptom(
                    "gc-pause",
                    delta > warnMs * 2
                            ? CrashRiskMonitor.Severity.HIGH
                            : CrashRiskMonitor.Severity.MEDIUM,
                    "GC pause lasted " + delta + " ms (threshold " + warnMs + " ms)"
            );
        }
        lastGcTime = totalGc;
    }
}
