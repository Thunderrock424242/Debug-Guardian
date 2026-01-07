package com.thunder.debugguardian.debug.monitor;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class PerformanceSnapshotEvents {
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PerformanceSnapshotLogger.recordServerTick();
    }
}
