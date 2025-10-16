package com.thunder.debugguardian.util;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thunder.debugguardian.DebugGuardian.LOGGER;

public class UnusedConfigScanner {

    private static final Path CONFIG_FOLDER = FMLPaths.CONFIGDIR.get();
    private static final Pattern MODID_PATTERN = Pattern.compile("^([a-z0-9_\\-]+)-(client|server|common)?\\.toml$");

    public static void scanForUnusedConfigs(MinecraftServer server) {
        if (!Files.isDirectory(CONFIG_FOLDER)) return;

        try (var stream = Files.list(CONFIG_FOLDER)) {
            stream.filter(p -> p.toString().endsWith(".toml"))
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        Matcher matcher = MODID_PATTERN.matcher(fileName);
                        if (!matcher.matches()) return;

                        String modid = matcher.group(1);
                        if (!ModList.get().isLoaded(modid)) {
                            LOGGER.warn("[DebugGuardian] Unused config detected: '{}' (mod '{}' not loaded)", fileName, modid);
                            return;
                        }

                        Optional<IModInfo> modInfo = ModList.get().getMods().stream()
                                .filter(info -> info.getModId().equals(modid))
                                .findFirst();

                        if (modInfo.isEmpty()) {
                            return;
                        }

                        try {
                            Path modPath = modInfo.get().getOwningFile().getFile().getFilePath();
                            if (!Files.exists(modPath)) return;

                            FileTime configTime = Files.getLastModifiedTime(file);
                            FileTime modTime = Files.getLastModifiedTime(modPath);
                            if (modTime.compareTo(configTime) > 0) {
                                LOGGER.warn("[DebugGuardian] Outdated config detected: '{}' (mod '{}' updated on {} but config last modified on {})",
                                        fileName,
                                        modid,
                                        formatFileTime(modTime),
                                        formatFileTime(configTime));
                            }
                        } catch (IOException e) {
                            LOGGER.debug("[DebugGuardian] Unable to check config freshness for '{}': {}", fileName, e.toString());
                        }
                    });

        } catch (IOException e) {
            LOGGER.error("[DebugGuardian] Failed to scan config folder: ", e);
        }
    }

    private static String formatFileTime(FileTime time) {
        Instant instant = time.toInstant();
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(instant);
    }
}