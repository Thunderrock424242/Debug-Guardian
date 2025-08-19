package com.thunder.debugguardian;

import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.monitor.LiveLogMonitor;
import com.thunder.debugguardian.debug.Watchdog;
import com.thunder.debugguardian.debug.monitor.PerformanceMonitor;
import com.thunder.debugguardian.debug.monitor.ThreadUsageMonitor;
import com.thunder.debugguardian.debug.monitor.WorldGenFreezeDetector;
import com.thunder.debugguardian.debug.replay.PostMortemRecorder;
import com.thunder.debugguardian.util.UnusedConfigScanner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

@Mod(DebugGuardian.MOD_ID)

public class DebugGuardian {
    /**
     * The constant LOGGER.
     */
    public static final Logger LOGGER = LogManager.getLogger("debugguardian");


    /**
     * The constant MOD_ID.
     */
    public static final String MOD_ID = "debugguardian";
    private static final Map<CustomPacketPayload.Type<?>, NetworkMessage<?>> MESSAGES = new HashMap<>();

    private record NetworkMessage<T extends CustomPacketPayload>(StreamCodec<? extends FriendlyByteBuf, T> reader,
                                                                 IPayloadHandler<T> handler) {
    }

    /**
     * Create the Debug Guardian mod instance.
     *
     * @param modEventBus the mod event bus
     * @param container   the container
     */
    public DebugGuardian(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("DebugGuardian initialized; starting monitors");
        // Register mod setup and creative tabs
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        // Register global events
        NeoForge.EVENT_BUS.register(this);

        container.registerConfig(ModConfig.Type.COMMON, DebugConfig.SPEC);

        Watchdog.start();


    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LiveLogMonitor.start();
        PerformanceMonitor.init();
        PostMortemRecorder.init();
        WorldGenFreezeDetector.start();
        ThreadUsageMonitor.start();

    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {

    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        UnusedConfigScanner.scanForUnusedConfigs(event.getServer());
    }
}




