package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Checks thread usage and reports mods that spawn many threads.
 */
public class ThreadUsageMonitor {
    private static final int THREAD_THRESHOLD = 50;

    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                ThreadUsageMonitor::checkThreads, 10, 10, TimeUnit.SECONDS);
    }

    private static void checkThreads() {
        Map<String, Integer> counts = new HashMap<>();
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            String mod = ClassLoadingIssueDetector.identifyCulpritMod(e.getValue());
            counts.merge(mod, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > THREAD_THRESHOLD && !"Unknown".equals(e.getKey())) {
                DebugGuardian.LOGGER.warn(
                        "Mod {} is using {} threads", e.getKey(), e.getValue());
                CrashRiskMonitor.recordSymptom(
                        "threads-" + e.getKey(),
                        e.getValue() > THREAD_THRESHOLD * 2
                                ? CrashRiskMonitor.Severity.HIGH
                                : CrashRiskMonitor.Severity.MEDIUM,
                        "Mod " + e.getKey() + " spawned " + e.getValue() + " threads"
                );
            }
        }
    }
}
