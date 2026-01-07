package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PerformanceSnapshotLogger {
    private static final Path PERFORMANCE_LOG =
            FMLPaths.GAMEDIR.get().resolve("logs/debugguardian_performance.log");
    private static final long LOG_INTERVAL_SECONDS = 60;
    private static final long SYSTEM_SAMPLE_SECONDS = 5;

    private static PerformanceSnapshotLogger instance;

    private final ScheduledExecutorService scheduler;
    private final SampleAccumulator serverSamples = new SampleAccumulator("server");
    private final SampleAccumulator clientSamples = new SampleAccumulator("client");
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final com.sun.management.OperatingSystemMXBean osBean =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);

    private PerformanceSnapshotLogger() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "debugguardian-performance-snapshot");
            t.setDaemon(true);
            return t;
        });
        resetLog();
        scheduler.scheduleAtFixedRate(this::captureSystemSample,
                SYSTEM_SAMPLE_SECONDS, SYSTEM_SAMPLE_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::flushSnapshots,
                LOG_INTERVAL_SECONDS, LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public static void start() {
        if (instance == null) {
            instance = new PerformanceSnapshotLogger();
        }
    }

    public static void stop() {
        if (instance != null) {
            instance.scheduler.shutdownNow();
            instance = null;
        }
    }

    public static void recordServerTick() {
        if (instance != null) {
            instance.serverSamples.recordTick();
        }
    }

    public static void recordClientTick(int fps, double gpuUsage) {
        if (instance != null) {
            instance.clientSamples.recordTick();
            instance.clientSamples.recordFpsSample(fps);
            instance.clientSamples.recordGpuSample(gpuUsage);
        }
    }

    private void captureSystemSample() {
        double cpuLoad = osBean.getProcessCpuLoad();
        double used = memoryMXBean.getHeapMemoryUsage().getUsed();
        double max = memoryMXBean.getHeapMemoryUsage().getMax();
        double memUsage = max > 0 ? used / max : Double.NaN;
        if (serverSamples.hasTickSamples()) {
            serverSamples.recordCpuSample(cpuLoad);
            serverSamples.recordMemorySample(memUsage);
        }
        if (clientSamples.hasTickSamples()) {
            clientSamples.recordCpuSample(cpuLoad);
            clientSamples.recordMemorySample(memUsage);
        }
    }

    private void flushSnapshots() {
        SampleSnapshot serverSnapshot = serverSamples.snapshotAndReset();
        SampleSnapshot clientSnapshot = clientSamples.snapshotAndReset();
        if (serverSnapshot != null) {
            writeSnapshot(serverSnapshot);
        }
        if (clientSnapshot != null) {
            writeSnapshot(clientSnapshot);
        }
    }

    private void writeSnapshot(SampleSnapshot snapshot) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String line = String.format(
                Locale.ROOT,
                "[%s] side=%s avgFps=%s avgCpu=%.2f%% avgGpu=%s avgMem=%.2f%% overloadedTicks=%d avgOverloadMs=%.2f",
                timestamp,
                snapshot.side(),
                snapshot.avgFpsText(),
                snapshot.avgCpu() * 100.0,
                snapshot.avgGpuText(),
                snapshot.avgMem() * 100.0,
                snapshot.overloadedTicks(),
                snapshot.avgOverloadMs()
        );
        write(line);
    }

    private static void resetLog() {
        try {
            Files.createDirectories(PERFORMANCE_LOG.getParent());
            Files.writeString(PERFORMANCE_LOG, "",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Could not reset performance snapshot log", e);
        }
    }

    private static void write(String line) {
        try {
            Files.writeString(PERFORMANCE_LOG, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed writing performance snapshot log", e);
        }
    }

    private static class SampleAccumulator {
        private final String side;
        private Instant lastTick = Instant.now();
        private long tickSamples;
        private long fpsSamples;
        private long fpsTotal;
        private long cpuSamples;
        private double cpuTotal;
        private long memSamples;
        private double memTotal;
        private long gpuSamples;
        private double gpuTotal;
        private long overloadTicks;
        private long overloadTotalMs;

        private SampleAccumulator(String side) {
            this.side = side;
        }

        private void recordTick() {
            Instant now = Instant.now();
            long ms = java.time.Duration.between(lastTick, now).toMillis();
            lastTick = now;
            tickSamples++;
            long threshold = Math.max(1L, DebugConfig.get().performanceTickThresholdMs);
            if (ms > threshold) {
                overloadTicks++;
                overloadTotalMs += (ms - threshold);
            }
        }

        private void recordFpsSample(int fps) {
            if (fps > 0) {
                fpsSamples++;
                fpsTotal += fps;
            }
        }

        private void recordCpuSample(double cpuLoad) {
            if (cpuLoad >= 0) {
                cpuSamples++;
                cpuTotal += cpuLoad;
            }
        }

        private void recordMemorySample(double memUsage) {
            if (!Double.isNaN(memUsage) && memUsage >= 0) {
                memSamples++;
                memTotal += memUsage;
            }
        }

        private void recordGpuSample(double gpuUsage) {
            if (!Double.isNaN(gpuUsage) && gpuUsage >= 0) {
                gpuSamples++;
                gpuTotal += gpuUsage;
            }
        }

        private boolean hasTickSamples() {
            return tickSamples > 0;
        }

        private SampleSnapshot snapshotAndReset() {
            if (tickSamples == 0 && cpuSamples == 0 && fpsSamples == 0 && memSamples == 0 && gpuSamples == 0) {
                return null;
            }
            double avgCpu = cpuSamples > 0 ? cpuTotal / cpuSamples : 0.0;
            double avgMem = memSamples > 0 ? memTotal / memSamples : 0.0;
            double avgGpu = gpuSamples > 0 ? gpuTotal / gpuSamples : Double.NaN;
            long avgFps = fpsSamples > 0 ? Math.round((double) fpsTotal / fpsSamples) : -1;
            double avgOverloadMs = overloadTicks > 0 ? (double) overloadTotalMs / overloadTicks : 0.0;
            SampleSnapshot snapshot = new SampleSnapshot(
                    side,
                    avgFps,
                    avgCpu,
                    avgMem,
                    avgGpu,
                    overloadTicks,
                    avgOverloadMs
            );
            tickSamples = 0;
            fpsSamples = 0;
            fpsTotal = 0;
            cpuSamples = 0;
            cpuTotal = 0;
            memSamples = 0;
            memTotal = 0;
            gpuSamples = 0;
            gpuTotal = 0;
            overloadTicks = 0;
            overloadTotalMs = 0;
            return snapshot;
        }
    }

    private record SampleSnapshot(
            String side,
            long avgFps,
            double avgCpu,
            double avgMem,
            double avgGpu,
            long overloadedTicks,
            double avgOverloadMs
    ) {
        private String avgFpsText() {
            return avgFps > 0 ? Long.toString(avgFps) : "N/A";
        }

        private String avgGpuText() {
            return Double.isNaN(avgGpu) ? "N/A" : String.format(Locale.ROOT, "%.2f%%", avgGpu * 100.0);
        }
    }
}
