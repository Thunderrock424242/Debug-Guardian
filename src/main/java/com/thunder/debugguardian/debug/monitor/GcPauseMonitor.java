package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;

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
            }
            lastGcTime = totalGc;
        }, 10, 10, TimeUnit.SECONDS);
    }
}
