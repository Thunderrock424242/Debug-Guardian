package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Tracks world-generation progress and warns if no chunks are
 * generated for an extended period, which may indicate a stall
 * at "0%" during world creation.
 */
@EventBusSubscriber(modid = MOD_ID)
public class WorldGenProgressMonitor {
    private static final long WARN_MS = 60_000; // 1 minute
    private static long lastChunkTime = -1;
    private static boolean warned = false;

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load evt) {
        lastChunkTime = System.currentTimeMillis();
        warned = false;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load evt) {
        lastChunkTime = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        if (lastChunkTime < 0 || warned) return;
        long elapsed = System.currentTimeMillis() - lastChunkTime;
        if (elapsed > WARN_MS) {
            Thread serverThread = findServerThread();
            String culprit = serverThread != null
                    ? ClassLoadingIssueDetector.identifyCulpritMod(serverThread.getStackTrace())
                    : "Unknown";
            if ("Unknown".equals(culprit)) {
                culprit = findCulpritAcrossThreads();
            }
            if (!"Unknown".equals(culprit)) {
                DebugGuardian.LOGGER.warn("World gen stuck at 0% for {} ms; possible culprit mod {}", elapsed, culprit);
            } else {
                DebugGuardian.LOGGER.warn("World gen stuck at 0% for {} ms; culprit unknown", elapsed);
            }
            warned = true;
        }
    }

    private static Thread findServerThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    private static String findCulpritAcrossThreads() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            String mod = ClassLoadingIssueDetector.identifyCulpritMod(e.getValue());
            if (!"Unknown".equals(mod)) {
                counts.merge(mod, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse("Unknown");
    }
}
