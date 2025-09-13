package com.thunder.debugguardian.debug.external;

import com.thunder.debugguardian.config.DebugConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Log analyzer that delegates analysis to an external AI service over HTTP.
 * <p>
 * The implementation demonstrates how thread reports could be serialized and
 * sent to an AI endpoint. It reads an API key from the environment variable
 * {@code DEBUG_GUARDIAN_AI_KEY} and falls back to a hard-coded placeholder if
 * the variable is not defined. The sample URL must be replaced with a real
 * service before use.
 */
public class AiLogAnalyzer implements LogAnalyzer {
    // Placeholder API endpoint URL
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;

    /**
     * Creates an analyzer using the {@code logging.aiServiceApiKey} config
     * value, falling back to the {@code DEBUG_GUARDIAN_AI_KEY} environment
     * variable or a placeholder if neither is present.
     */
    public AiLogAnalyzer() {
        this(resolveApiKey());
    }

    /**
     * Creates an analyzer with the provided API key.
     */
    public AiLogAnalyzer(String apiKey) {
        this.apiKey = apiKey;
    }

    private static String resolveApiKey() {
        String key = DebugConfig.get().loggingAiServiceApiKey;
        if (key == null || key.isEmpty()) {
            key = System.getenv().getOrDefault("DEBUG_GUARDIAN_AI_KEY", "REPLACE_WITH_REAL_AI_KEY");
        }
        return key;
    }

    @Override
    public String analyze(List<ThreadReport> threads) {
        try {
            String payload = buildPayload(threads);

            HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String response = readAll(responseStream);
            return response.isEmpty() ? "AI service returned empty response." : response;
        } catch (IOException e) {
            return "Failed to contact AI service: " + e.getMessage();
        }
    }

    private String buildPayload(List<ThreadReport> threads) {
        String threadJson = threads.stream().map(tr -> {
            String stack = tr.stack().stream()
                    .map(frame -> "\"" + escape(frame) + "\"")
                    .collect(Collectors.joining(","));
            return "{\"thread\":\"" + escape(tr.thread()) + "\",\"mod\":\"" +
                    escape(tr.mod()) + "\",\"state\":\"" + escape(tr.state()) +
                    "\",\"stack\":[" + stack + "]}";
        }).collect(Collectors.joining(","));
        return "{\"threads\":[" + threadJson + "]}";
    }

    private static String readAll(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
