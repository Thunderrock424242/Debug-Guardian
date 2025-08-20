package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Tracks world-generation progress and warns if no chunks are
 * generated for an extended period, which may indicate a stall
 * at "0%" during world creation.
 */
@EventBusSubscriber(modid = MOD_ID)
public class WorldGenProgressMonitor {
    private static final long WARN_MS = 15_000; // 15 seconds
    private static long lastChunkTime = -1;
    private static boolean warned = false;

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load evt) {
        lastChunkTime = System.currentTimeMillis();
        warned = false;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load evt) {
        lastChunkTime = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        if (lastChunkTime < 0 || warned) return;
        long elapsed = System.currentTimeMillis() - lastChunkTime;
        if (elapsed > WARN_MS) {
            DebugGuardian.LOGGER.warn("No chunks generated for {} ms; world gen may be stuck", elapsed);
            warned = true;
        }
    }
}
