package com.thunder.debugguardian.debug.report;

import com.thunder.debugguardian.config.DebugConfig;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class CrashReportHandler {

    public static String createIssueUrl(CrashInfo info) {
        String repo = DebugConfig.get().reportingGithubRepository;

        // Now info.mods is List<ModInfo>, so .getId() and .getVersion() exist
        String modsList = info.mods.stream()
                .map(mod -> mod.getId() + "@" + mod.getVersion())
                .collect(Collectors.joining(", "));

        String body = new StringBuilder()
                .append("Mods: ").append(modsList)
                .append("\nJava: ").append(info.javaVersion)
                .append("\nArgs: ").append(String.join(" ", info.javaArgs))
                .append("\nFingerprint: ").append(info.fingerprint)
                .append("\nStackTop:\n```")
                .append(info.stackTop.replace("```", "\\`\\`\\`"))  // escape any grave accents
                .append("```")
                .toString();

        return String.format(
                "https://github.com/%s/issues/new?body=%s",
                repo,
                URLEncoder.encode(body, StandardCharsets.UTF_8)
        );
    }

    public static Component buildChatLink(CrashInfo info) {
        String url = createIssueUrl(info);
        return Component.literal("[Report Crash]")
                .withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withUnderlined(true)
                );
    }
}
