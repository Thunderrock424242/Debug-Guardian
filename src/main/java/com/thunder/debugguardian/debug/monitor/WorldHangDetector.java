package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.neoforged.fml.loading.FMLPaths;

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
    private static final Path DUMP_DIR =
            FMLPaths.GAMEDIR.get().resolve("debugguardian");

    private static final Path DUMP_DIR =
            FMLPaths.GAMEDIR.get().resolve("debugguardian");
  
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

                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                ThreadInfo info = bean.getThreadInfo(serverThread.getId());
                String lock = info != null ? String.valueOf(info.getLockName()) : "unknown";
                String owner = info != null ? String.valueOf(info.getLockOwnerName()) : "unknown";

                String culpritMod = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                StackTraceElement topFrame = stack.length > 0 ? stack[0] : null;
                StackTraceElement culpritFrame = ClassLoadingIssueDetector.findCulpritFrame(stack);
                DebugGuardian.LOGGER.warn(
                        "Server thread {} unresponsive for {} ms; waiting on {} owned by {}; blocked at {} via {} (mod: {})",
                        serverThread.getState(), elapsed, lock, owner, topFrame, culpritFrame, culpritMod);

                String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                StackTraceElement top = stack.length > 0 ? stack[0] : null;
                StackTraceElement culpritFrame = ClassLoadingIssueDetector.findCulpritFrame(stack);
                DebugGuardian.LOGGER.warn(
                        "Server thread {} unresponsive for {} ms; waiting on {} owned by {}; blocked at {} via {} (mod: {})",
                        serverThread.getState(), elapsed, lock, owner, top, culpritFrame, culprit);

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
                dumpThreads(bean);

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

    private static void dumpThreads(ThreadMXBean bean) {
        try {
            Files.createDirectories(DUMP_DIR);
            String ts = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = DUMP_DIR.resolve("world-hang-" + ts + ".log");
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE_NEW)) {
                for (ThreadInfo info : bean.dumpAllThreads(true, true)) {
                    writer.write(info.toString());
                    writer.newLine();
                }
            }
            DebugGuardian.LOGGER.warn("Thread dump written to {}", file);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write thread dump", e);
        }
    }
}

