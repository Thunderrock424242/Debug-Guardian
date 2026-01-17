package com.thunder.debugguardian.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.thunder.debugguardian.DebugGuardian.LOGGER;

public final class ModInstallTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_FOLDER = FMLPaths.CONFIGDIR.get().resolve("debugguardian");
    private static final Path TRACKER_FILE = CONFIG_FOLDER.resolve("mod-install-history.json");

    private ModInstallTracker() {
    }

    public static void recordNewMods() {
        try {
            Files.createDirectories(CONFIG_FOLDER);
        } catch (IOException e) {
            LOGGER.error("[DebugGuardian] Failed to create tracker folder at {}: {}", CONFIG_FOLDER, e.toString());
            return;
        }

        JsonObject root = loadExisting();
        List<String> newlySeen = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Instant nowInstant = Instant.now();
        String now = formatInstant(nowInstant);
        boolean changed = false;
        Set<String> currentMods = new HashSet<>();

        for (IModInfo mod : ModList.get().getMods()) {
            String modId = mod.getModId();
            currentMods.add(modId);
            JsonObject entry = getEntry(root, modId);
            if (!entry.has("addedAt")) {
                entry.addProperty("addedAt", resolveInitialAddedAt(mod, nowInstant));
                newlySeen.add(modId);
            }
            entry.addProperty("version", mod.getVersion().toString());
            entry.addProperty("displayName", mod.getDisplayName());
            entry.addProperty("lastSeen", now);
            entry.addProperty("lastSeenVersion", mod.getVersion().toString());
            entry.remove("removedAt");
            changed = true;
        }

        for (String modId : List.copyOf(root.keySet())) {
            if (currentMods.contains(modId)) {
                continue;
            }
            JsonObject entry = getEntry(root, modId);
            if (!entry.has("removedAt")) {
                entry.addProperty("removedAt", now);
                removed.add(modId);
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        try {
            Files.writeString(TRACKER_FILE, GSON.toJson(root));
            if (!newlySeen.isEmpty()) {
                LOGGER.info("[DebugGuardian] Recorded {} new mod(s) in {}", newlySeen.size(), TRACKER_FILE);
                LOGGER.info("[DebugGuardian] Newly added mods: {}", String.join(", ", newlySeen));
            }
            if (!removed.isEmpty()) {
                LOGGER.info("[DebugGuardian] Recorded {} removed mod(s) in {}", removed.size(), TRACKER_FILE);
                LOGGER.info("[DebugGuardian] Removed mods: {}", String.join(", ", removed));
            }
        } catch (IOException e) {
            LOGGER.error("[DebugGuardian] Failed to write mod install history: {}", e.toString());
        }
    }

    private static JsonObject loadExisting() {
        if (!Files.exists(TRACKER_FILE)) {
            return new JsonObject();
        }

        try {
            String content = Files.readString(TRACKER_FILE);
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[DebugGuardian] Failed to parse mod install history; recreating. {}", e.toString());
            return new JsonObject();
        }
    }

    private static String formatInstant(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(instant);
    }

    private static JsonObject getEntry(JsonObject root, String modId) {
        if (root.has(modId) && root.get(modId).isJsonObject()) {
            return root.getAsJsonObject(modId);
        }
        JsonObject entry = new JsonObject();
        root.add(modId, entry);
        return entry;
    }

    private static String resolveInitialAddedAt(IModInfo mod, Instant fallback) {
        try {
            Path modPath = mod.getOwningFile().getFile().getFilePath();
            if (Files.exists(modPath)) {
                Instant modInstant = Files.getLastModifiedTime(modPath).toInstant();
                return formatInstant(modInstant);
            }
        } catch (Exception e) {
            LOGGER.debug("[DebugGuardian] Failed to read mod file timestamp for {}: {}", mod.getModId(), e.toString());
        }
        return formatInstant(fallback);
    }
}
