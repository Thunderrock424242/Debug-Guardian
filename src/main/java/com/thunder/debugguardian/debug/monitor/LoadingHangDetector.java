package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.debug.external.ThreadReport;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.level.LevelEvent;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Watches the client loading phase and reports when the main thread appears
 * stuck on the same stack trace for an extended period.
 */
@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
public final class LoadingHangDetector {
    private static final long CHECK_INTERVAL_SECONDS = 10L;
    private static final long HANG_THRESHOLD_MS = 60_000L;
    private static final int REQUIRED_MATCHES = 4;
    private static final long MIN_CPU_DELTA_MS = 50L;
    private static final Path DUMP_DIR = FMLPaths.GAMEDIR.get().resolve("logs").resolve("debugguardian");
    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "debugguardian-loading-hang");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean loadComplete;
    private static volatile boolean worldJoined;
    private static volatile StackTraceElement[] lastStack;
    private static volatile long lastProgressTime = System.currentTimeMillis();
    private static volatile int matchCount;
    private static volatile long lastCpuTime = -1;

    static {
        if (BEAN.isThreadCpuTimeSupported() && !BEAN.isThreadCpuTimeEnabled()) {
            try {
                BEAN.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private LoadingHangDetector() {
    }

    public static void start() {
        EXECUTOR.scheduleAtFixedRate(LoadingHangDetector::checkHang,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public static void stop() {
        EXECUTOR.shutdownNow();
    }

    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        loadComplete = true;
        stop();
    }

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level level && level.isClientSide()) {
            worldJoined = true;
            stop();
        }
    }

    private static void checkHang() {
        if (loadComplete || worldJoined) {
            return;
        }
        Thread mainThread = findMainThread();
        if (mainThread == null) {
            return;
        }
        ThreadInfo info = BEAN.getThreadInfo(mainThread.threadId(), Integer.MAX_VALUE);
        StackTraceElement[] stack = info != null ? info.getStackTrace() : mainThread.getStackTrace();
        if (stack == null || stack.length == 0) {
            return;
        }
        long cpuDeltaMs = cpuDeltaMs(mainThread);
        boolean stackSame = lastStack != null && Arrays.equals(stack, lastStack);
        boolean progress = !stackSame || cpuDeltaMs >= MIN_CPU_DELTA_MS;
        if (progress) {
            lastStack = stack;
            lastProgressTime = System.currentTimeMillis();
            matchCount = 0;
            return;
        }

        matchCount++;
        long elapsed = System.currentTimeMillis() - lastProgressTime;
        if (elapsed < HANG_THRESHOLD_MS || matchCount < REQUIRED_MATCHES) {
            return;
        }

        String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        DebugGuardian.LOGGER.warn(
                "Client loading appears stuck for {} ms; state {} cpu delta {} ms; top frame {} (mod: {})",
                elapsed, info != null ? info.getThreadState() : mainThread.getState(), cpuDeltaMs, stack[0], culprit);
        CrashRiskMonitor.recordSymptom(
                "loading-hang",
                CrashRiskMonitor.Severity.CRITICAL,
                "Client loading hang detected (" + culprit + ")"
        );
        List<ThreadReport> reports = collectThreadReports();
        writeUnifiedReport(timestamp, info, stack, culprit, elapsed, cpuDeltaMs, reports);

        matchCount = 0;
        lastProgressTime = System.currentTimeMillis();
    }

    private static Thread findMainThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if ("Render thread".equals(name) || "main".equals(name)) {
                return t;
            }
        }
        return null;
    }

    private static long cpuDeltaMs(Thread thread) {
        if (!BEAN.isThreadCpuTimeSupported()) {
            return -1;
        }
        long nowCpu = BEAN.getThreadCpuTime(thread.threadId());
        if (nowCpu < 0) {
            return -1;
        }
        long deltaMs = lastCpuTime >= 0 ? (nowCpu - lastCpuTime) / 1_000_000 : -1;
        lastCpuTime = nowCpu;
        return deltaMs;
    }

    private static void writeUnifiedReport(String timestamp,
                                           ThreadInfo info,
                                           StackTraceElement[] stack,
                                           String culprit,
                                           long elapsed,
                                           long cpuDeltaMs,
                                           List<ThreadReport> reports) {
        Path file = DUMP_DIR.resolve("loading-hang-" + timestamp + ".log");
        try {
            Files.createDirectories(DUMP_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                writer.write("Loading hang detected at " + timestamp + " (" + elapsed + " ms, cpu delta "
                        + cpuDeltaMs + " ms, mod: " + culprit + ")");
                writer.newLine();
                writer.newLine();

                writer.write("==== MAIN THREAD ====");
                writer.newLine();
                if (info != null) {
                    writer.write("Thread state: " + info.getThreadState());
                    writer.newLine();
                    if (info.getLockInfo() != null) {
                        writer.write("Waiting on: " + info.getLockInfo());
                        writer.newLine();
                    }
                    if (info.getLockOwnerName() != null) {
                        writer.write("Lock owner: " + info.getLockOwnerName() + " (id " + info.getLockOwnerId() + ")");
                        writer.newLine();
                    }
                }
                for (StackTraceElement element : stack) {
                    writer.write("    at " + element);
                    writer.newLine();
                }
                writer.newLine();

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
            }
            DebugGuardian.LOGGER.warn("Loading hang report written to {}", file);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write loading hang report", e);
        }
    }

    private static boolean isFrameworkClass(StackTraceElement element) {
        String cn = element.getClassName();
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
            DebugGuardian.LOGGER.error("Failed to collect loading hang thread dump", e);
        }
        return reports;
    }
}
