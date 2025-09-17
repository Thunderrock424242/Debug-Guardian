package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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

    private static volatile long lastTick = System.currentTimeMillis();
    private static volatile StackTraceElement[] lastStackTrace;
    private static volatile int matchCount;
    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    static {
        if (BEAN.isThreadCpuTimeSupported() && !BEAN.isThreadCpuTimeEnabled()) {
            try {
                BEAN.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }
    private static volatile long lastCpuTime;

    /**
     * Starts periodic checks for an unresponsive server thread.
     */
    public static void start() {
        EXECUTOR.scheduleAtFixedRate(WorldHangDetector::checkHang, 10, 5, TimeUnit.SECONDS);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        lastTick = System.currentTimeMillis();
        lastCpuTime = BEAN.getThreadCpuTime(Thread.currentThread().threadId());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent evt) {
        EXECUTOR.shutdownNow();
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

                long nowCpu = BEAN.isThreadCpuTimeSupported() ? BEAN.getThreadCpuTime(serverThread.threadId()) : -1;
                long cpuDeltaMs = lastCpuTime > 0 && nowCpu >= 0 ? (nowCpu - lastCpuTime) / 1_000_000 : -1;
                ThreadInfo info = BEAN.getThreadInfo(serverThread.threadId(), Integer.MAX_VALUE);
                String lock = info != null ? String.valueOf(info.getLockName()) : "unknown";
                String owner = info != null ? String.valueOf(info.getLockOwnerName()) : "unknown";
                long ownerId = info != null ? info.getLockOwnerId() : -1;
                String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                StackTraceElement top = stack.length > 0 ? stack[0] : null;
                StackTraceElement culpritFrame = ClassLoadingIssueDetector.findCulpritFrame(stack);
                DebugGuardian.LOGGER.warn(
                        "Server thread {} unresponsive for {} ms (cpu delta {} ms); waiting on {} owned by {}; blocked at {} via {} (mod: {})",
                        serverThread.getState(), elapsed, cpuDeltaMs, lock, owner, top, culpritFrame, culprit);
                CrashRiskMonitor.recordSymptom(
                        "world-hang",
                        CrashRiskMonitor.Severity.CRITICAL,
                        "Server thread blocked for " + elapsed + " ms (culprit: " + culprit + ")"
                );

                if (ownerId != -1) {
                    ThreadInfo ownerInfo = BEAN.getThreadInfo(ownerId, Integer.MAX_VALUE);
                    StackTraceElement[] ownerStack = ownerInfo != null ? ownerInfo.getStackTrace() : null;
                    if (ownerStack != null && ownerStack.length > 0) {
                        DebugGuardian.LOGGER.warn("Lock owner {} at {}", owner, ownerStack[0]);
                        if (DebugGuardian.LOGGER.isDebugEnabled()) {
                            for (StackTraceElement element : ownerStack) {
                                DebugGuardian.LOGGER.debug("    owner at {}", element);
                            }
                        }
                    }
                }

                if (DebugGuardian.LOGGER.isDebugEnabled()) {
                    for (StackTraceElement element : stack) {
                        DebugGuardian.LOGGER.debug("    at {}", element);
                    }
                }
                dumpThreads(BEAN);

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

    private static boolean isFrameworkClass(StackTraceElement e) {
        String cn = e.getClassName();
        return cn.startsWith("java.") || cn.startsWith("javax.") ||
                cn.startsWith("sun.") || cn.startsWith("com.sun.") ||
                cn.startsWith("jdk.");
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
                    StackTraceElement[] stack = info.getStackTrace();
                    boolean hasAppFrame = false;
                    for (StackTraceElement frame : stack) {
                        if (!isFrameworkClass(frame)) {
                            hasAppFrame = true;
                            break;
                        }
                    }
                    if (!hasAppFrame) {
                        continue;
                    }
                    writer.write("\"" + info.getThreadName() + "\" id=" +
                            info.getThreadId() + " state=" + info.getThreadState());
                    writer.newLine();
                    for (StackTraceElement frame : stack) {
                        if (!isFrameworkClass(frame)) {
                            writer.write("\tat " + frame);
                            writer.newLine();
                        }
                    }
                    writer.newLine();
                }
            }
            DebugGuardian.LOGGER.warn("Filtered thread dump written to {}", file);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write thread dump", e);
        }
    }
}

