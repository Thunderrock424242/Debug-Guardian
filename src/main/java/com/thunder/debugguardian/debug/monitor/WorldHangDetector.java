package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
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
            FMLPaths.GAMEDIR.get().resolve("logs").resolve("debugguardian");

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
                List<ThreadReport> reports = collectThreadReports();
                String analysis = reports.isEmpty()
                        ? ""
                        : new com.thunder.debugguardian.debug.external.AiLogAnalyzer().analyze(reports);
                writeUnifiedReport(
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")),
                        elapsed,
                        cpuDeltaMs,
                        info,
                        lock,
                        owner,
                        ownerId,
                        culprit,
                        top,
                        culpritFrame,
                        stack,
                        reports,
                        analysis
                );

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

    private static List<ThreadReport> collectThreadReports() {
        List<ThreadReport> reports = new ArrayList<>();
        try {
            for (ThreadInfo info : BEAN.dumpAllThreads(true, true)) {
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
                reports.add(new ThreadReport(info.getThreadName(), mod, info.getThreadState().name(), List.copyOf(frames)));
            }
        } catch (Exception e) {
            DebugGuardian.LOGGER.error("Failed to collect world hang thread dump", e);
        }
        return reports;
    }

    private static void writeUnifiedReport(String timestamp,
                                           long elapsed,
                                           long cpuDeltaMs,
                                           ThreadInfo info,
                                           String lock,
                                           String owner,
                                           long ownerId,
                                           String culprit,
                                           StackTraceElement top,
                                           StackTraceElement culpritFrame,
                                           StackTraceElement[] stack,
                                           List<ThreadReport> reports,
                                           String analysis) {
        Path file = DUMP_DIR.resolve("world-hang-" + timestamp + ".log");
        try {
            Files.createDirectories(DUMP_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write("World hang detected at " + timestamp + " (" + elapsed + " ms, cpu delta "
                        + cpuDeltaMs + " ms, mod: " + culprit + ")");
                writer.newLine();
                writer.newLine();

                writer.write("==== SERVER THREAD ====");
                writer.newLine();
                writer.write("State: " + (info != null ? info.getThreadState() : "unknown"));
                writer.newLine();
                writer.write("Waiting on: " + lock + " owned by " + owner + " (id " + ownerId + ")");
                writer.newLine();
                writer.write("Top frame: " + top);
                writer.newLine();
                writer.write("Culprit frame: " + culpritFrame);
                writer.newLine();
                if (info != null && info.getLockInfo() != null) {
                    writer.write("Lock info: " + info.getLockInfo());
                    writer.newLine();
                }
                if (info != null && info.getLockOwnerName() != null) {
                    writer.write("Lock owner: " + info.getLockOwnerName() + " (id " + info.getLockOwnerId() + ")");
                    writer.newLine();
                }
                for (StackTraceElement element : stack) {
                    writer.write("    at " + element);
                    writer.newLine();
                }
                writer.newLine();

                if (ownerId != -1) {
                    ThreadInfo ownerInfo = BEAN.getThreadInfo(ownerId, Integer.MAX_VALUE);
                    StackTraceElement[] ownerStack = ownerInfo != null ? ownerInfo.getStackTrace() : null;
                    writer.write("==== LOCK OWNER ====");
                    writer.newLine();
                    writer.write("Owner: " + owner);
                    writer.newLine();
                    if (ownerStack != null) {
                        for (StackTraceElement element : ownerStack) {
                            writer.write("    at " + element);
                            writer.newLine();
                        }
                    } else {
                        writer.write("No lock owner stack captured.");
                        writer.newLine();
                    }
                    writer.newLine();
                }

                writer.write("==== THREAD DUMPS ====");
                writer.newLine();
                if (reports.isEmpty()) {
                    writer.write("No non-framework threads captured.");
                    writer.newLine();
                } else {
                    for (ThreadReport report : reports) {
                        writer.write("Thread: " + report.thread() + " mod: " + report.mod()
                                + " state: " + report.state());
                        writer.newLine();
                        for (String frame : report.stack()) {
                            writer.write("\t" + frame);
                            writer.newLine();
                        }
                        writer.newLine();
                    }
                }

                writer.write("==== THREAD SUMMARY ====");
                writer.newLine();
                if (reports.isEmpty()) {
                    writer.write("No summary available.");
                    writer.newLine();
                } else {
                    for (ThreadReport report : reports) {
                        writer.write(report.thread() + " - " + report.mod() + " [" + report.state() + "] ("
                                + report.stack().size() + " frames)");
                        writer.newLine();
                    }
                }
                writer.newLine();

                writer.write("==== SUSPECTS ====");
                writer.newLine();
                if (reports.isEmpty()) {
                    writer.write("No suspects available.");
                    writer.newLine();
                } else {
                    Map<String, Long> counts = reports.stream()
                            .collect(Collectors.groupingBy(ThreadReport::mod, Collectors.counting()));
                    List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .toList();
                    for (Map.Entry<String, Long> entry : sorted) {
                        writer.write(entry.getKey() + " - " + entry.getValue() + " thread(s)");
                        writer.newLine();
                    }
                }
                writer.newLine();

                writer.write("==== ANALYSIS ====");
                writer.newLine();
                if (analysis == null || analysis.isBlank()) {
                    writer.write("No analysis available.");
                    writer.newLine();
                } else {
                    writer.write(analysis.trim());
                    writer.newLine();
                }
            }

            if (reports.isEmpty()) {
                DebugGuardian.LOGGER.warn("World hang report written to {} (no mod-owned frames captured)", file);
            } else {
                Map<String, Long> counts = reports.stream()
                        .collect(Collectors.groupingBy(ThreadReport::mod, Collectors.counting()));
                Map.Entry<String, Long> topEntry = counts.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .orElse(null);
                if (topEntry != null) {
                    DebugGuardian.LOGGER.warn("World hang report written to {} (top suspect: {} — {} thread(s))",
                            file, topEntry.getKey(), topEntry.getValue());
                } else {
                    DebugGuardian.LOGGER.warn("World hang report written to {}", file);
                }
            }
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write world hang report", e);
        }
    }
}
