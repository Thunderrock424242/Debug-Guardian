package com.thunder.debugguardian.util;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thunder.debugguardian.DebugGuardian.LOGGER;

public class UnusedConfigScanner {

    private static final Path CONFIG_FOLDER = Path.of("config");
    private static final Pattern MODID_PATTERN = Pattern.compile("^([a-z0-9_\\-]+)-(client|server|common)?\\.toml$");

    public static void scanForUnusedConfigs(MinecraftServer server) {
        if (!Files.isDirectory(CONFIG_FOLDER)) return;

        try (var stream = Files.list(CONFIG_FOLDER)) {
            stream.filter(p -> p.toString().endsWith(".toml"))
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        Matcher matcher = MODID_PATTERN.matcher(fileName);
                        if (matcher.matches()) {
                            String modid = matcher.group(1);
                            if (!ModList.get().isLoaded(modid)) {
                                LOGGER.warn("[DebugGuardian] Unused config detected: '{}' (mod '{}' not loaded)", fileName, modid);
                            }
                        }
                    });

        } catch (IOException e) {
            LOGGER.error("[DebugGuardian] Failed to scan config folder: ", e);
        }
    }
}