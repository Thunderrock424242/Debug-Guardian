package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Detects when the server thread stops ticking for an extended period and
 * attempts to identify the mod responsible based on the thread's stack trace.
 */
@EventBusSubscriber(modid = MOD_ID)
public class WorldHangDetector {
    private static final long HANG_THRESHOLD_MS = 10_000; // 10 seconds
    private static volatile long lastTick = System.currentTimeMillis();

    /**
     * Starts periodic checks for an unresponsive server thread.
     */
    public static void start() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                WorldHangDetector::checkHang, 10, 5, TimeUnit.SECONDS);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        lastTick = System.currentTimeMillis();
    }

    private static void checkHang() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTick;
        if (elapsed > HANG_THRESHOLD_MS) {
            Thread serverThread = findServerThread();
            StackTraceElement[] stack = serverThread != null ? serverThread.getStackTrace() : null;
            String culprit = stack != null
                    ? ClassLoadingIssueDetector.identifyCulpritMod(stack)
                    : "Unknown";
            if (!"Unknown".equals(culprit)) {
                DebugGuardian.LOGGER.warn(
                        "Server thread unresponsive for {} ms; possible culprit mod {}", elapsed, culprit);
            } else {
                DebugGuardian.LOGGER.warn(
                        "Server thread unresponsive for {} ms; culprit unknown", elapsed);
            }
            lastTick = now; // reset to avoid spamming
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
}

