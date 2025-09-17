package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.debug.monitor.CrashRiskMonitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tracks garbage collection pauses to warn about potential memory leaks
 * or misbehaving mods causing frequent full GCs.
 */
public class GcPauseMonitor {
    private static final long PAUSE_WARN_MS = 2_000; // 2 seconds
    private static long lastGcTime = 0;

    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            long totalGc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                    .sum();
            long delta = totalGc - lastGcTime;
            if (lastGcTime != 0 && delta > PAUSE_WARN_MS) {
                DebugGuardian.LOGGER.warn("Long GC pause detected: {} ms", delta);
                CrashRiskMonitor.recordSymptom(
                        "gc-pause",
                        delta > 5_000 ? CrashRiskMonitor.Severity.HIGH : CrashRiskMonitor.Severity.MEDIUM,
                        "GC pause lasted " + delta + " ms"
                );
            }
            lastGcTime = totalGc;
        }, 10, 10, TimeUnit.SECONDS);
    }
}
