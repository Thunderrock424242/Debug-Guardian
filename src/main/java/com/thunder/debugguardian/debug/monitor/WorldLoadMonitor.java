package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Watches dimension load/unload events and reports unusually slow loads.
 */
@EventBusSubscriber(modid = MOD_ID)
public class WorldLoadMonitor {
    private static final long WARN_MS = 5_000; // 5 seconds
    private static final Map<ResourceKey<Level>, Long> unloadTimes = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload evt) {
        Level level = (Level) evt.getLevel();
        unloadTimes.put(level.dimension(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load evt) {
        Level level = (Level) evt.getLevel();
        ResourceKey<Level> key = level.dimension();
        Long start = unloadTimes.remove(key);
        if (start != null) {
            long dur = System.currentTimeMillis() - start;
            if (dur > WARN_MS) {
                DebugGuardian.LOGGER.warn("Dimension {} took {} ms to load", key.location(), dur);
            }
        }
    }
}
