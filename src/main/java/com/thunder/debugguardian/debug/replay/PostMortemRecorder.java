package com.thunder.debugguardian.debug.replay;

import com.google.gson.Gson;
import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class PostMortemRecorder {
    private static final int DEFAULT_SIZE = 500;
    private final Deque<GameEvent> buffer;
    private final int capacity;
    private static final PostMortemRecorder INSTANCE = new PostMortemRecorder();

    private PostMortemRecorder() {
        this.capacity = DebugConfig.get().postmortemBufferSize;
        this.buffer = new ConcurrentLinkedDeque<>();
    }

    public static PostMortemRecorder get() {
        return INSTANCE;
    }

    public void record(GameEvent event) {
        buffer.addLast(event);
        if (buffer.size() > capacity) buffer.removeFirst();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
        get().record(new GameEvent(GameEvent.EventType.TICK,"ServerPost"));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post evt) {
        get().record(new GameEvent(GameEvent.EventType.TICK,"ServerPost"));
    }

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