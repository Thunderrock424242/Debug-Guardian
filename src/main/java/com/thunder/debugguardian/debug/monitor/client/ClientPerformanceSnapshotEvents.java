package com.thunder.debugguardian.debug.monitor.client;

import com.thunder.debugguardian.debug.monitor.PerformanceSnapshotLogger;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
public class ClientPerformanceSnapshotEvents {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        int fps = minecraft.getFps();
        double gpuUsage = readGpuUtilization(minecraft);
        PerformanceSnapshotLogger.recordClientTick(fps, gpuUsage);
    }

    private static double readGpuUtilization(Minecraft minecraft) {
        try {
            Method method = Minecraft.class.getMethod("getGpuUtilization");
            Object value = method.invoke(minecraft);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof java.util.Optional<?> optional && optional.isPresent()) {
                Object nested = optional.get();
                if (nested instanceof Number number) {
                    return number.doubleValue();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // GPU utilization is not exposed in every build; ignore if missing.
        }
        return Double.NaN;
    }
}
