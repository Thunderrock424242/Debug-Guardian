package com.thunder.debugguardian.debug.replay;

import com.google.gson.Gson;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.thunder.debugguardian.DebugGuardian;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
    private int capacity;

    private PostMortemRecorder() {
        // no-op; actual init in init()
    }

    /**
     * Initialize the recorder after config has loaded.
     */
    public static void init() {
        if (instance == null) {
            instance = new PostMortemRecorder();
            instance.capacity = DebugConfig.get().postmortemBufferSize;
            instance.buffer = new ConcurrentLinkedDeque<>();
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
        if (buffer.size() > capacity) buffer.removeFirst();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
        get().record(new GameEvent(GameEvent.EventType.TICK, "ClientPost"));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        get().record(new GameEvent(GameEvent.EventType.TICK, "ServerPost"));
    }

    /**
     * Dump the current buffer to a JSON file in the crash directory.
     */
    public void dump(Path crashDir) {
        try {
            Files.createDirectories(crashDir);
            Path out = crashDir.resolve("postmortem.json");
            String json = new Gson().toJson(buffer);
            Files.writeString(out, json);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to dump post-mortem buffer", e);
        }
    }
}