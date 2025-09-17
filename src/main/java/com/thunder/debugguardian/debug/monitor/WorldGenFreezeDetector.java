package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Periodically checks the server thread for long-running world generation and
 * attempts to attribute the work to a mod based on the stack trace.
 */
public class WorldGenFreezeDetector {
    private static final long FREEZE_THRESHOLD_MS = 20_000; // 20 seconds
    private static long worldGenStart = -1;

    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            Thread serverThread = findServerThread();
            if (serverThread == null) return;
            StackTraceElement[] stack = serverThread.getStackTrace();
            boolean inWorldGen = isWorldGenStack(stack);
            long now = System.currentTimeMillis();
            if (inWorldGen) {
                if (worldGenStart < 0) {
                    worldGenStart = now;
                } else if (now - worldGenStart > FREEZE_THRESHOLD_MS) {
                    String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                    if (!"Unknown".equals(culprit)) {
                        DebugGuardian.LOGGER.warn(
                                "Possible worldgen freeze caused by mod {}", culprit);
                        CrashRiskMonitor.recordSymptom(
                                "worldgen-freeze",
                                CrashRiskMonitor.Severity.HIGH,
                                "World generation hung, suspect mod " + culprit
                        );
                    } else {
                        DebugGuardian.LOGGER.warn(
                                "Possible worldgen freeze detected, culprit unknown");
                        CrashRiskMonitor.recordSymptom(
                                "worldgen-freeze",
                                CrashRiskMonitor.Severity.MEDIUM,
                                "World generation hung without identifiable culprit"
                        );
                    }
                    worldGenStart = now; // reset to avoid spamming
                }
            } else {
                worldGenStart = -1;
            }
        }, 10, 5, TimeUnit.SECONDS);
    }

    private static Thread findServerThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    private static boolean isWorldGenStack(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (cls.contains("ChunkStatus") ||
                    cls.contains("WorldGenRegion") ||
                    cls.contains("ChunkGenerator")) {
                return true;
            }
        }
        return false;
    }
}
