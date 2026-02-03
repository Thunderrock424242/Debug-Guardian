package com.thunder.debugguardian.debug.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.debug.replay.GameEvent.CommandPayload;
import com.thunder.debugguardian.debug.replay.GameEvent.EntitySpawnPayload;
import com.thunder.debugguardian.debug.replay.GameEvent.GameEventPayload;
import com.thunder.debugguardian.debug.replay.GameEvent.TickEventPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures recent game events for a post-mortem dump upon a crash.
 */
public class PostMortemRecorder {
    private static PostMortemRecorder instance;
    private Deque<GameEvent> buffer;
    private volatile int capacity;
    private final Gson gson;

    private PostMortemRecorder() {
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
    }

    /**
     * Initialize the recorder after config has loaded.
     */
    public static void init() {
        if (instance == null) {
            instance = new PostMortemRecorder();
            instance.capacity = Math.max(1, DebugConfig.get().postmortemBufferSize);
            instance.buffer = new ConcurrentLinkedDeque<>();
            NeoForge.EVENT_BUS.register(instance);
            registerClientHooks();
        }
    }

    /**
     * Retrieve the singleton, initializing if needed.
     */
    public static PostMortemRecorder get() {
        if (instance == null) init();
        return instance;
    }

    /** Record a single game event onto the buffer. */
    public void record(GameEvent event) {
        buffer.addLast(event);
        trimToCapacity();
    }

    /** Convenience helper to create and record an event. */
    private void record(GameEvent.EventType type, GameEventPayload payload) {
        record(new GameEvent(type, payload));
    }

    public void recordClientTick(Level level) {
        Long gameTime = level != null ? level.getGameTime() : null;
        String dimension = level != null ? level.dimension().location().toString() : "client";
        record(GameEvent.EventType.TICK, new TickEventPayload("client", "POST", gameTime, dimension));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post evt) {
        MinecraftServer server = evt.getServer();
        String dimension = null;
        Long gameTime = null;
        if (server != null) {
            Level overworld = server.overworld();
            if (overworld != null) {
                gameTime = overworld.getGameTime();
                dimension = overworld.dimension().location().toString();
            }
        }
        record(GameEvent.EventType.TICK, new TickEventPayload("server", "POST", gameTime, dimension));
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        if (!level.isClientSide()) {
            ResourceLocation dimensionId = level.dimension().location();
            Component displayName = entity.getName();
            EntitySpawnPayload payload = new EntitySpawnPayload(
                    entity.getEncodeId(),
                    displayName != null ? displayName.getString() : null,
                    entity.getUUID(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    dimensionId.toString(),
                    false
            );
            record(GameEvent.EventType.ENTITY_SPAWN, payload);
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        String commandString = event.getParseResults().getReader().getString();
        Vec3 pos = source.getPosition();
        Level level = source.getLevel();
        double[] position = pos != null ? new double[]{pos.x, pos.y, pos.z} : null;
        String dimension = level != null ? level.dimension().location().toString() : null;
        boolean executesOnServer = level == null || !level.isClientSide();
        CommandPayload payload = new CommandPayload(
                source.getTextName(),
                commandString,
                position,
                dimension,
                executesOnServer
        );
        record(GameEvent.EventType.COMMAND, payload);
    }

    /**
     * Reload recorder settings from the latest configuration.
     */
    public static void reloadFromConfig() {
        if (!DebugConfig.get().postMortemEnable) {
            return;
        }
        PostMortemRecorder recorder = get();
        recorder.capacity = Math.max(1, DebugConfig.get().postmortemBufferSize);
        recorder.trimToCapacity();
    }

    /**
     * Dump the current buffer to a JSON file in the crash directory.
     */
    public void dump(Path crashDir) {
        try {
            Files.createDirectories(crashDir);
            Path out = crashDir.resolve("postmortem.json");
            String json = gson.toJson(buffer);
            Files.writeString(out, json);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to dump post-mortem buffer", e);
        }
    }

    private void trimToCapacity() {
        while (buffer.size() > capacity) {
            buffer.pollFirst();
        }
    }

    private static void registerClientHooks() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            Class<?> clientRegistrar = Class.forName(
                    "com.thunder.debugguardian.debug.replay.client.PostMortemRecorderClient"
            );
            clientRegistrar.getMethod("register").invoke(null);
        } catch (ReflectiveOperationException e) {
            DebugGuardian.LOGGER.error("Failed to register client post-mortem hooks", e);
        }
    }
}
