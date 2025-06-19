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

@EventBusSubscriber(modid = MOD_ID)
public class PerformanceMonitor {
    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    private final Deque<Long> tickTimes = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final long tickThreshold = DebugConfig.get().performance.tickThresholdMs;
    private final double memoryWarnRatio = DebugConfig.get().performance.memoryWarningRatio;

    private PerformanceMonitor() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        scheduler.scheduleAtFixedRate(() -> {
            double used = mem.getHeapMemoryUsage().getUsed();
            double max = mem.getHeapMemoryUsage().getMax();
            if (used / max > memoryWarnRatio) {
                DebugGuardian.LOGGER.warn("Memory usage high: " + (used/max*100) + "%");
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public static PerformanceMonitor get() {
        return INSTANCE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
         {
            PerformanceMonitor.get().recordTick();
        }
    }

    private Instant last = Instant.now();

    private void recordTick() {
        Instant now = Instant.now();
        long ms = Duration.between(last, now).toMillis();
        last = now;
        tickTimes.addLast(ms);
        if (tickTimes.size() > 100) tickTimes.removeFirst();
        if (ms > tickThreshold) {
            DebugGuardian.LOGGER.warn("Slow tick detected: " + ms + "ms");
        }
    }
}