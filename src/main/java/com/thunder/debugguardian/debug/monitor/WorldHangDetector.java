package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.debug.external.AiLogAnalyzer;
import com.thunder.debugguardian.debug.external.LogAnalyzer;
import com.thunder.debugguardian.debug.external.ThreadReport;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
                ThreadDumpResult dump = dumpThreads(BEAN);
                if (dump != null) {
                    analyzeDump(dump, elapsed);
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

    private static boolean isFrameworkClass(StackTraceElement e) {
        String cn = e.getClassName();
        return cn.startsWith("java.") || cn.startsWith("javax.") ||
                cn.startsWith("sun.") || cn.startsWith("com.sun.") ||
                cn.startsWith("jdk.");
    }

    private static ThreadDumpResult dumpThreads(ThreadMXBean bean) {
        List<ThreadReport> reports = new ArrayList<>();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path file = DUMP_DIR.resolve("world-hang-" + ts + ".log");

        try {
            Files.createDirectories(DUMP_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                for (ThreadInfo info : bean.dumpAllThreads(true, true)) {
                    StackTraceElement[] stack = info.getStackTrace();
                    if (stack == null || stack.length == 0) {
                        continue;
                    }

                    List<String> frames = new ArrayList<>();
                    boolean hasAppFrame = false;
                    for (StackTraceElement frame : stack) {
                        if (!isFrameworkClass(frame)) {
                            hasAppFrame = true;
                            frames.add("at " + frame);
                        }
                    }

                    if (!hasAppFrame) {
                        continue;
                    }

                    String mod = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                    writer.write("Thread: " + info.getThreadName() + " mod: " + mod + " state: " + info.getThreadState());
                    writer.newLine();
                    for (String frame : frames) {
                        writer.write("\t" + frame);
                        writer.newLine();
                    }
                    writer.newLine();

                    reports.add(new ThreadReport(info.getThreadName(), mod, info.getThreadState().name(), List.copyOf(frames)));
                }
            }
            DebugGuardian.LOGGER.warn("Filtered thread dump written to {}", file);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write thread dump", e);
            return new ThreadDumpResult(null, ts, reports);
        }

        return new ThreadDumpResult(file, ts, reports);
    }

    private static void analyzeDump(ThreadDumpResult dump, long elapsed) {
        List<ThreadReport> threads = dump.threads();
        if (threads.isEmpty()) {
            DebugGuardian.LOGGER.warn("World hang detected ({} ms) but no mod-owned frames were captured in the dump.", elapsed);
            return;
        }

        writeSummary(dump.timestamp(), threads);
        writeSuspects(dump.timestamp(), threads);
        writeExplanation(dump.timestamp(), threads, elapsed);
    }

    private static void writeSummary(String timestamp, List<ThreadReport> threads) {
        List<String> lines = new ArrayList<>();
        for (ThreadReport tr : threads) {
            lines.add(tr.thread() + " - " + tr.mod() + " [" + tr.state() + "] (" + tr.stack().size() + " frames)");
        }
        Path summary = DUMP_DIR.resolve("world-hang-" + timestamp + "-summary.txt");
        try {
            Files.write(summary, lines, StandardCharsets.UTF_8);
            DebugGuardian.LOGGER.warn("World hang thread summary written to {} ({} thread(s))", summary, threads.size());
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write world hang summary", e);
        }
    }

    private static void writeSuspects(String timestamp, List<ThreadReport> threads) {
        Map<String, Long> counts = threads.stream()
                .collect(Collectors.groupingBy(ThreadReport::mod, Collectors.counting()));
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();
        List<String> lines = sorted.stream()
                .map(e -> e.getKey() + " - " + e.getValue() + " thread(s)")
                .collect(Collectors.toList());
        Path suspects = DUMP_DIR.resolve("world-hang-" + timestamp + "-suspects.txt");
        try {
            Files.write(suspects, lines, StandardCharsets.UTF_8);
            if (!sorted.isEmpty()) {
                Map.Entry<String, Long> top = sorted.get(0);
                DebugGuardian.LOGGER.warn("World hang suspect summary written to {} (top: {} — {} thread(s))",
                        suspects, top.getKey(), top.getValue());
            } else {
                DebugGuardian.LOGGER.warn("World hang suspect summary written to {}", suspects);
            }
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write world hang suspects", e);
        }
    }

    private static void writeExplanation(String timestamp, List<ThreadReport> threads, long elapsed) {
        LogAnalyzer analyzer = new AiLogAnalyzer();
        String explanation = analyzer.analyze(threads);
        Path explanationFile = DUMP_DIR.resolve("world-hang-" + timestamp + "-analysis.txt");
        try {
            Files.writeString(explanationFile, explanation, StandardCharsets.UTF_8);
            String headline = explanation.lines().findFirst().orElse(explanation).trim();
            DebugGuardian.LOGGER.warn("World hang analysis ({} ms) written to {} — {}", elapsed, explanationFile, headline);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write world hang analysis", e);
        }
    }

    private record ThreadDumpResult(Path file, String timestamp, List<ThreadReport> threads) {
    }
}

