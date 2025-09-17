package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Watches heap usage and warns when memory remains
 * consistently high, hinting at a potential leak.
 */
public class MemoryLeakMonitor {
    private static final double WARN_RATIO = 0.9; // 90% heap usage
    private static final int WARN_STREAK = 3;     // checks before warning
    private static int highUsageStreak = 0;

    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                MemoryLeakMonitor::checkMemory, 30, 30, TimeUnit.SECONDS);
    }

    private static void checkMemory() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long used = mem.getHeapMemoryUsage().getUsed();
        long max = mem.getHeapMemoryUsage().getMax();
        double ratio = (double) used / max;

        if (ratio > WARN_RATIO) {
            highUsageStreak++;
            if (highUsageStreak >= WARN_STREAK) {
                DebugGuardian.LOGGER.warn(
                        "Possible memory leak: heap usage at {}% for {} checks",
                        Math.round(ratio * 100), highUsageStreak
                );
                CrashRiskMonitor.recordSymptom(
                        "memory-leak",
                        CrashRiskMonitor.Severity.HIGH,
                        "Heap usage > " + Math.round(ratio * 100) + "% for " + highUsageStreak + " checks"
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

