package com.thunder.debugguardian.debug;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.thunder.debugguardian.DebugGuardian.LOGGER;

public class Watchdog {

    private static final int MAX_THREADS = 300;
    private static final long MAX_MEMORY_MB = 8000;

    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
            long usedMB = heapUsage.getUsed() / (1024 * 1024);

            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            int threadCount = threadBean.getThreadCount();

            if (usedMB > MAX_MEMORY_MB) {
                LOGGER.warn("⚠ High memory usage detected: {} MB", usedMB);
            }

            if (threadCount > MAX_THREADS) {
                LOGGER.warn("⚠ High thread count detected: {} threads", threadCount);
            }
        }, 10, 10, TimeUnit.SECONDS); // check every 10 seconds
    }

    private static void logWarning(String msg) {
        System.out.println("[Debug Guardian Watchdog] " + msg);
        // Optionally also log to runtime_issues.log or notify players
    }
}
