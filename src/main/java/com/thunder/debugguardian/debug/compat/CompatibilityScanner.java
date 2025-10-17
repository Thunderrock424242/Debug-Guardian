package com.thunder.debugguardian.debug.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.compat.client.ShaderIssueChatNotifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
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
            if (Files.notExists(json)) {
                try (InputStream in = CompatibilityScanner.class.getResourceAsStream("/compatibility.json")) {
                    if (in != null) {
                        Files.copy(in, json);
                    } else {
                        JsonObject tmpl = new JsonObject();
                        tmpl.add("incompatibilities", new JsonArray());
                        Files.writeString(json, tmpl.toString());
                    }
                }
            }

            JsonObject root = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (!root.has("incompatibilities")) {
                return;
            }

            ModList mods = ModList.get();
            AtomicInteger issues = new AtomicInteger();
            StreamSupport.stream(root.getAsJsonArray("incompatibilities").spliterator(), false)
                    .map(JsonElement::getAsJsonObject)
                    .map(obj -> detectIssue(mods, obj))
                    .filter(issue -> issue != null)
                    .forEach(issue -> {
                        issues.incrementAndGet();
                        logIssue(issue);
                    });

            if (issues.get() > 0) {
                DebugGuardian.LOGGER.warn("Detected {} compatibility issue(s); review the log for details.", issues.get());
            }
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to load compatibility.json", e);
        }
    }

    private static DetectedIssue detectIssue(ModList mods, JsonObject obj) {
        List<String> requiredDescriptors = new ArrayList<>();
        if (obj.has("mods")) {
            obj.getAsJsonArray("mods").forEach(el -> requiredDescriptors.add(el.getAsString()));
        }
        if (requiredDescriptors.isEmpty() && obj.has("modA")) {
            requiredDescriptors.add(obj.get("modA").getAsString());
        }
        if (obj.has("modB")) {
            requiredDescriptors.add(obj.get("modB").getAsString());
        }

        if (requiredDescriptors.isEmpty()) {
            return null;
        }

        List<String> matchedRequired = new ArrayList<>();
        for (String descriptor : requiredDescriptors) {
            String matchedId = resolveLoadedModId(mods, descriptor);
            if (matchedId == null) {
                return null;
            }
            matchedRequired.add(describeMod(mods, matchedId));
        }

        List<String> matchedOptional = new ArrayList<>();
        if (obj.has("requiresOneOf")) {
            obj.getAsJsonArray("requiresOneOf").forEach(el -> {
                String candidate = resolveLoadedModId(mods, el.getAsString());
                if (candidate != null) {
                    matchedOptional.add(describeMod(mods, candidate));
                }
            });
            if (matchedOptional.isEmpty()) {
                return null;
            }
        }

        if (obj.has("forbiddenMods")) {
            boolean forbiddenPresent = StreamSupport.stream(obj.getAsJsonArray("forbiddenMods").spliterator(), false)
                    .map(JsonElement::getAsString)
                    .map(descriptor -> resolveLoadedModId(mods, descriptor))
                    .anyMatch(id -> id != null);
            if (forbiddenPresent) {
                return null;
            }
        }

        return new DetectedIssue(obj, List.copyOf(matchedRequired), List.copyOf(matchedOptional));
    }

    private static String resolveLoadedModId(ModList mods, String descriptor) {
        String[] options = descriptor.split("\\|");
        for (String option : options) {
            String trimmed = option.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (mods.isLoaded(trimmed)) {
                return trimmed;
            }

            String alt = trimmed.replace('-', '_');
            if (!alt.equals(trimmed) && mods.isLoaded(alt)) {
                return alt;
            }

            String alt2 = trimmed.replace('_', '-');
            if (!alt2.equals(trimmed) && mods.isLoaded(alt2)) {
                return alt2;
            }

            String alt3 = trimmed.replace("-", "");
            if (!alt3.equals(trimmed) && mods.isLoaded(alt3)) {
                return alt3;
            }
        }
        return null;
    }

    private static String describeMod(ModList mods, String id) {
        return mods.getModContainerById(id)
                .map(container -> container.getModInfo().getDisplayName() + " (" + id + ")")
                .orElse(id);
    }

    private static void logIssue(DetectedIssue issue) {
        JsonObject obj = issue.definition();
        String title = obj.has("title") ? obj.get("title").getAsString() : buildTitle(obj);
        String reason = obj.has("reason") ? obj.get("reason").getAsString() : "Potential incompatibility detected.";
        StringBuilder details = new StringBuilder(reason);
        if (obj.has("symptom")) {
            details.append(" Symptom: ").append(obj.get("symptom").getAsString());
        }
        if (obj.has("workaround")) {
            details.append(" Suggested fix: ").append(obj.get("workaround").getAsString());
        }
        if (obj.has("source")) {
            details.append(" More info: ").append(obj.get("source").getAsString());
        }

        String detailText = details.toString();
        boolean shaderIssue = isShaderIssue(obj, detailText);
        appendDetectedCause(details, issue, shaderIssue);

        detailText = details.toString();

        String severity = obj.has("severity") ? obj.get("severity").getAsString().toUpperCase(Locale.ROOT) : "WARN";
        switch (severity) {
            case "INFO" -> DebugGuardian.LOGGER.info("Compatibility issue [{}]: {}", title, detailText);
            case "ERROR" -> DebugGuardian.LOGGER.error("Compatibility issue [{}]: {}", title, detailText);
            default -> DebugGuardian.LOGGER.warn("Compatibility issue [{}]: {}", title, detailText);
        }

        if (shaderIssue && FMLEnvironment.dist == Dist.CLIENT) {
            ShaderIssueChatNotifier.warn(title, detailText);
        }
    }

    private static void appendDetectedCause(StringBuilder details, DetectedIssue issue, boolean shaderIssue) {
        List<String> required = issue.requiredMods();
        List<String> optional = issue.optionalMods();

        if (!required.isEmpty()) {
            details.append(" Active mods: ").append(String.join(", ", required)).append('.');
        }

        if (!optional.isEmpty()) {
            details.append(" Additional contributing mod(s): ").append(String.join(", ", optional)).append('.');
        }

        if (shaderIssue && (!optional.isEmpty() || required.size() > 1)) {
            List<String> suspects = !optional.isEmpty() ? optional : required.subList(1, required.size());
            if (!suspects.isEmpty()) {
                details.append(" Shader selection screen suppressed by: ")
                        .append(String.join(", ", suspects)).append('.');
            }
        }
    }

    private static String buildTitle(JsonObject obj) {
        if (obj.has("mods")) {
            List<String> mods = new ArrayList<>();
            obj.getAsJsonArray("mods").forEach(el -> mods.add(el.getAsString()));
            return String.join(" + ", mods);
        }
        if (obj.has("modA")) {
            String a = obj.get("modA").getAsString();
            String b = obj.has("modB") ? obj.get("modB").getAsString() : null;
            return b != null ? a + " + " + b : a;
        }
        return "Unknown Mods";
    }

    private static boolean isShaderIssue(JsonObject obj, String detailText) {
        if (obj.has("tags")) {
            boolean tagMatch = StreamSupport.stream(obj.getAsJsonArray("tags").spliterator(), false)
                    .map(JsonElement::getAsString)
                    .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(tag -> tag.equals("shader")
                            || tag.equals("shaders")
                            || tag.equals("shader_screen")
                            || tag.equals("shader-menu"));
            if (tagMatch) {
                return true;
            }
        }

        if (obj.has("symptom") && containsShaderKeyword(obj.get("symptom").getAsString())) {
            return true;
        }
        if (obj.has("reason") && containsShaderKeyword(obj.get("reason").getAsString())) {
            return true;
        }
        return containsShaderKeyword(detailText);
    }

    private static boolean containsShaderKeyword(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("shader");
    }

    private record DetectedIssue(JsonObject definition, List<String> requiredMods, List<String> optionalMods) {}
}
