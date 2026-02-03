package com.thunder.debugguardian.debug;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.thunder.debugguardian.DebugGuardian.LOGGER;

import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.monitor.CrashRiskMonitor;

public class Watchdog {

    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> scheduledTask;

    public static synchronized void start() {
        if (!DebugConfig.get().watchdogEnable) {
            stop();
            return;
        }
        ensureExecutor();
        reschedule();
    }

    public static synchronized void reloadFromConfig() {
        if (!DebugConfig.get().watchdogEnable) {
            stop();
            return;
        }
        ensureExecutor();
        reschedule();
    }

    private static void ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DebugGuardian Watchdog");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static void reschedule() {
        long interval = Math.max(1, DebugConfig.get().watchdogCheckIntervalSeconds);
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = executor.scheduleAtFixedRate(Watchdog::checkResources, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Stop the watchdog scheduler and clean up resources.
     */
    public static synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void checkResources() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / (1024 * 1024);

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();

        DebugConfig config = DebugConfig.get();
        long maxMemoryMb = config.watchdogMemoryCapMb;
        int maxThreads = config.watchdogThreadCap;

        if (usedMB > maxMemoryMb) {
            LOGGER.warn("⚠ High memory usage detected: {} MB (threshold {} MB)", usedMB, maxMemoryMb);
            CrashRiskMonitor.recordSymptom(
                    "watchdog-memory",
                    CrashRiskMonitor.Severity.HIGH,
                    "Heap usage exceeded " + maxMemoryMb + " MB (" + usedMB + " MB)"
            );
        }

        if (threadCount > maxThreads) {
            LOGGER.warn("⚠ High thread count detected: {} threads (threshold {})", threadCount, maxThreads);
            CrashRiskMonitor.recordSymptom(
                    "watchdog-threads",
                    CrashRiskMonitor.Severity.MEDIUM,
                    "Thread count exceeded " + maxThreads + " (" + threadCount + ")"
            );
        }
    }
}
