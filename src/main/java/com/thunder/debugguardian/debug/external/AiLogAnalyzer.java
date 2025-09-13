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
 * This implementation calls the OpenAI chat completions endpoint. If no API
 * key is configured via the {@code logging.aiServiceApiKey} config value or the
 * {@code DEBUG_GUARDIAN_AI_KEY} environment variable, the analyzer falls back
 * to {@link BasicLogAnalyzer} to provide a heuristic explanation.
 */
public class AiLogAnalyzer implements LogAnalyzer {
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o";

    private final String apiKey;
    private final BasicLogAnalyzer fallback = new BasicLogAnalyzer();

    /**
     * Creates an analyzer using the {@code logging.aiServiceApiKey} config
     * value, falling back to the {@code DEBUG_GUARDIAN_AI_KEY} environment
     * variable if the config entry is blank.
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
        if (key == null || key.isBlank()) {
            key = System.getenv("DEBUG_GUARDIAN_AI_KEY");
        }
        return (key == null || key.isBlank()) ? null : key;
    }

    @Override
    public String analyze(List<ThreadReport> threads) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallback.analyze(threads);
        }

        try {
            String message = buildMessage(threads);
            String payload = "{" +
                    "\"model\":\"" + MODEL + "\"," +
                    "\"messages\":[{" +
                    "\"role\":\"system\",\"content\":\"You are a Minecraft crash analysis assistant.\"},{" +
                    "\"role\":\"user\",\"content\":\"" + escape(message) + "\"}]" +
                    "}";

            HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            InputStream responseStream = connection.getResponseCode() >= 200 && connection.getResponseCode() < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String response = readAll(responseStream);
            String content = extractContent(response);
            return (content == null || content.isBlank())
                    ? "AI service returned empty response." : content;
        } catch (IOException e) {
            return "Failed to contact AI service: " + e.getMessage();
        }
    }

    /**
     * Build a plain-text message describing the thread dump that will be sent to
     * the AI service.
     */
    private String buildMessage(List<ThreadReport> threads) {
        StringBuilder sb = new StringBuilder();
        for (ThreadReport tr : threads) {
            sb.append("Thread ").append(tr.thread()).append(" [").append(tr.state()).append("] mod: ")
              .append(tr.mod()).append("\n");
            for (String frame : tr.stack()) {
                sb.append("  ").append(frame).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String extractContent(String json) {
        int idx = json.indexOf("\"content\":\"");
        if (idx == -1) return null;
        idx += 11; // length of "content":"
        int end = json.indexOf("\"", idx);
        if (end == -1) return null;
        String content = json.substring(idx, end);
        return content.replace("\\n", "\n");
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

