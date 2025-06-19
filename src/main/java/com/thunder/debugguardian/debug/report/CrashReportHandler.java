package com.thunder.debugguardian.debug.report;

import com.thunder.debugguardian.config.DebugConfig;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class CrashReportHandler {

    public static String createIssueUrl(CrashInfo info) {
        String repo = DebugConfig.get().reporting.githubRepository;
        String body = new StringBuilder()
                .append("Mods: ")
                .append(info.mods.stream().map(m -> m.id + "@" + m.version).collect(Collectors.joining(", ")))
                .append("\nJava: ")
                .append(info.javaVersion)
                .append("\nArgs: ")
                .append(String.join(" ", info.javaArgs))
                .append("\nFingerprint: ")
                .append(info.fingerprint)
                .append("\nStackTop:\n```")
                .append(info.stackTop)
                .append("```")
                .toString();

        String url = String.format(
                "https://github.com/%s/issues/new?body=%s",
                repo,
                URLEncoder.encode(body, StandardCharsets.UTF_8)
        );
        return url;
    }

    public static Component buildChatLink(CrashInfo info) {
        String url = createIssueUrl(info);
        return new TextComponent("[Report Crash]")
                .setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }
}
