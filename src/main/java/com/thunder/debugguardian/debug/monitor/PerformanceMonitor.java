package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Monitors tick durations and memory usage after config has loaded.
 */
@EventBusSubscriber(modid = MOD_ID)
public class PerformanceMonitor {
    private static PerformanceMonitor instance;
    private final Deque<Long> tickTimes = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler;
    private Instant last = Instant.now();
    // tracks consecutive slow ticks
    private int slowTickCount = 0;
    private static final int SLOW_TICK_WARN_INTERVAL = 100;

    private PerformanceMonitor() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "debugguardian-performance-monitor");
            t.setDaemon(true);
            return t;
        });
        // Schedule memory checks
        scheduler.scheduleAtFixedRate(this::checkMemory, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Initialize the PerformanceMonitor once config is ready
     */
    public static void init() {
        if (instance == null) {
            instance = new PerformanceMonitor();
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.scheduler.shutdownNow();
            instance = null;
        }
    }

    /**
     * Retrieve the singleton (after init)
     */
    public static PerformanceMonitor get() {
        return instance;
    }

    private void checkMemory() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        double used = mem.getHeapMemoryUsage().getUsed();
        double max = mem.getHeapMemoryUsage().getMax();
        if (used / max > DebugConfig.get().performanceMemoryWarningRatio) {
            DebugGuardian.LOGGER.warn("Memory usage high: {}%", used / max * 100);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
        if (instance != null) {
            instance.recordTick();
        }
    }

    /**
     * Records tick durations and warns on concerning ticks.
     */
    private void recordTick() {
        // Measure tick duration
        Instant now = Instant.now();
        long ms = Duration.between(last, now).toMillis();
        last = now;

        // Maintain a rolling window of the last 100 ticks
        tickTimes.addLast(ms);
        if (tickTimes.size() > 100) {
            tickTimes.removeFirst();
        }

        long threshold = Math.max(1L, DebugConfig.get().performanceTickThresholdMs);
        if (ms > threshold) {
            slowTickCount++;
            if (slowTickCount == 1) {
                DebugGuardian.LOGGER.debug(
                        "Concerning slow tick detected: {} ms (threshold {} ms)", ms, threshold
                );
                CrashRiskMonitor.recordSymptom(
                        "performance-slowtick",
                        CrashRiskMonitor.Severity.MEDIUM,
                        "Tick duration spiked to " + ms + " ms (threshold " + threshold + " ms)"
                );
            } else if (slowTickCount % SLOW_TICK_WARN_INTERVAL == 0) {
                DebugGuardian.LOGGER.debug(
                        "Still slow for {} consecutive ticks; last tick {} ms (threshold {} ms)",
                        slowTickCount, ms, threshold
                );
                CrashRiskMonitor.recordSymptom(
                        "performance-slowtick",
                        CrashRiskMonitor.Severity.HIGH,
                        slowTickCount + " consecutive slow ticks (" + ms + " ms, threshold " + threshold + " ms)"
                );
            }
        } else if (slowTickCount > 0) {
            DebugGuardian.LOGGER.debug(
                    "Recovered after {} slow ticks; last tick {} ms", slowTickCount, ms
            );
            slowTickCount = 0;
        }
    }
}

