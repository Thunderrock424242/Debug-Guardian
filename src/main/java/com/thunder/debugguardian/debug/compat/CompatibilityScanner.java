package com.thunder.debugguardian.debug.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class CompatibilityScanner {
    @SubscribeEvent
    public static void onFMLLoad(FMLLoadCompleteEvent evt) {
        if (!DebugConfig.get().compatibilityEnableScan) return;
        try {
            Path json = FMLPaths.CONFIGDIR.get().resolve("compatibility.json");
            JsonObject root = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            root.getAsJsonArray("incompatibilities").forEach(el -> {
                JsonObject obj = el.getAsJsonObject();
                String a = obj.get("modA").getAsString();
                String b = obj.has("modB") ? obj.get("modB").getAsString() : null;
                ModList mods = ModList.get();
                if (mods.isLoaded(a) && (b == null || mods.isLoaded(b))) {
                    String reason = obj.get("reason").getAsString();
                    DebugGuardian.LOGGER.warn("Incompatibility detected: {}{}: {}", a, b != null ? "/" + b : "", reason);
                }
            });
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to load compatibility.json", e);
        }
    }
}