package com.thunder.debugguardian.debug.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thunder.debugguardian.config.DebugConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private static final int TIMEOUT_MS = 5000;

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

        String message = buildMessage(threads);
        try {
            String response = CompletableFuture.supplyAsync(() -> requestAi(message))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (response == null || response.isBlank()) {
                return fallback.analyze(threads);
            }
            return response;
        } catch (Exception e) {
            return "Failed to contact AI service: " + e.getMessage();
        }
    }

    private String requestAi(String message) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", MODEL);
            JsonArray msgs = new JsonArray();
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", "You are a Minecraft crash analysis assistant.");
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", message);
            msgs.add(sys);
            msgs.add(user);
            payload.add("messages", msgs);

            HttpURLConnection connection = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            try (InputStream responseStream = connection.getResponseCode() >= 200 && connection.getResponseCode() < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                 InputStreamReader reader = new InputStreamReader(responseStream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (msg != null && msg.has("content")) {
                        return msg.get("content").getAsString();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
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
}
