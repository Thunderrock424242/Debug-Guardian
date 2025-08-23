package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Detects when the server thread stops ticking for an extended period and
 * attempts to identify the mod responsible. Consecutive matching stack traces
 * are required before a warning is emitted to avoid false positives, and the
 * full stack trace is logged at DEBUG for deeper analysis.
 */
@EventBusSubscriber(modid = MOD_ID)
public class WorldHangDetector {
    private static final long HANG_THRESHOLD_MS = 10_000; // 10 seconds
    private static final int REQUIRED_MATCHES = 3;
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "debugguardian-hang-detector");
                t.setDaemon(true);
                return t;
            });
    private static volatile long lastTick = System.currentTimeMillis();
    private static volatile StackTraceElement[] lastStackTrace;
    private static volatile int matchCount;

    /**
     * Starts periodic checks for an unresponsive server thread.
     */
    public static void start() {
        EXECUTOR.scheduleAtFixedRate(WorldHangDetector::checkHang, 10, 5, TimeUnit.SECONDS);
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
            if (serverThread == null) {
                return;
            }

            StackTraceElement[] stack = serverThread.getStackTrace();
            if (lastStackTrace != null && Arrays.equals(stack, lastStackTrace)) {
                matchCount++;
            } else {
                matchCount = 1;
                lastStackTrace = stack;
            }

            if (matchCount >= REQUIRED_MATCHES) {
                String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                StackTraceElement top = stack.length > 0 ? stack[0] : null;
                DebugGuardian.LOGGER.warn(
                        "Server thread {} unresponsive for {} ms; possible culprit mod {} at {}",
                        serverThread.getState(), elapsed, culprit, top);
                if (DebugGuardian.LOGGER.isDebugEnabled()) {
                    for (StackTraceElement element : stack) {
                        DebugGuardian.LOGGER.debug("    at {}", element);
                    }
                }
                matchCount = 0;
                lastStackTrace = null;
                lastTick = now; // reset to avoid spamming
            }
        } else {
            matchCount = 0;
            lastStackTrace = null;
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

