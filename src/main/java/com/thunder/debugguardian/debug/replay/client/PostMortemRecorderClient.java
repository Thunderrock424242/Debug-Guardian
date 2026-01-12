package com.thunder.debugguardian.debug.replay.client;

import com.thunder.debugguardian.debug.replay.PostMortemRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class PostMortemRecorderClient {
    private PostMortemRecorderClient() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.register(new PostMortemRecorderClient());
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post evt) {
        Level level = Minecraft.getInstance().level;
        PostMortemRecorder.get().recordClientTick(level);
    }
}
