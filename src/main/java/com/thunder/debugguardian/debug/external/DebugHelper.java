package com.thunder.debugguardian.debug.external;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helper application launched in a separate JVM when debug mode is enabled.
 * <p>
 * It now watches the dump directory for a force-close thread log, parses it
 * into a structured report listing potential culprit mods along with full
 * stack traces, writes the report to disk, and performs a basic analysis
 * highlighting likely causes. This analysis can later be replaced with an
 * AI-powered implementation.
 */
public class DebugHelper {

    // ThreadReport is defined as a top-level record to allow external analyzers
    // to operate on the parsed data.

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("No dump directory provided to DebugHelper");
            return;
        }

        Path dumpDir = Paths.get(args[0]);
        System.out.println("Debug helper watching " + dumpDir);

        try {
            Path dumpFile = waitForDumpFile(dumpDir);
            List<ThreadReport> report = parseDump(dumpFile);
            String ts = timestamp();
            Path out = dumpDir.resolve("analysis-" + ts + ".json");
            Files.writeString(out, new Gson().toJson(report));
            System.out.println("Analysis written to " + out);
            writeSummary(dumpDir, report, ts);
            writeSuspects(dumpDir, report, ts);
            writeExplanation(dumpDir, report, ts);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Path waitForDumpFile(Path dir) throws InterruptedException, IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "force-close-*.log")) {
            for (Path p : stream) {
                return p;
            }
        }

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);

            while (true) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        Path name = (Path) event.context();
                        if (name.toString().startsWith("force-close-") && name.toString().endsWith(".log")) {
                            return dir.resolve(name);
                        }
                    }
                }
                key.reset();
            }
        }
    }

    private static List<ThreadReport> parseDump(Path file) throws IOException {
        List<ThreadReport> threads = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        String currentThread = null;
        String currentMod = null;
        String currentState = null;
        List<String> currentStack = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("Thread: ")) {
                if (currentThread != null) {
                    threads.add(new ThreadReport(currentThread, currentMod, currentState, currentStack));
                    currentStack = new ArrayList<>();
                }
                String rest = line.substring("Thread: ".length());
                currentThread = rest.trim();
                currentMod = "unknown";
                currentState = "unknown";

                int modIdx = rest.indexOf(" mod: ");
                int stateIdx = rest.indexOf(" state: ");

                if (modIdx >= 0 && (stateIdx == -1 || modIdx < stateIdx)) {
                    currentThread = rest.substring(0, modIdx).trim();
                    String afterMod = rest.substring(modIdx + " mod: ".length());
                    int stateAfterMod = afterMod.indexOf(" state: ");
                    if (stateAfterMod >= 0) {
                        currentMod = afterMod.substring(0, stateAfterMod).trim();
                        currentState = afterMod.substring(stateAfterMod + " state: ".length()).trim();
                    } else {
                        currentMod = afterMod.trim();
                    }
                } else if (stateIdx >= 0) {
                    currentThread = rest.substring(0, stateIdx).trim();
                    String afterState = rest.substring(stateIdx + " state: ".length());
                    int modAfterState = afterState.indexOf(" mod: ");
                    if (modAfterState >= 0) {
                        currentState = afterState.substring(0, modAfterState).trim();
                        currentMod = afterState.substring(modAfterState + " mod: ".length()).trim();
                    } else {
                        currentState = afterState.trim();
                    }
                }
            } else if (line.startsWith("    at ")) {
                currentStack.add(line.trim());
            } else if (line.isBlank() && currentThread != null) {
                threads.add(new ThreadReport(currentThread, currentMod, currentState, currentStack));
                currentThread = null;
                currentMod = null;
                currentState = null;
                currentStack = new ArrayList<>();
            }
        }

        if (currentThread != null) {
            threads.add(new ThreadReport(currentThread, currentMod, currentState, currentStack));
        }

        return threads;
    }

    private static void writeSummary(Path dir, List<ThreadReport> report, String ts) throws IOException {
        List<String> lines = new ArrayList<>();
        for (ThreadReport tr : report) {
            lines.add(tr.thread() + " - " + tr.mod() + " [" + tr.state() + "] (" + tr.stack().size() + " frames)");
        }
        Path summary = dir.resolve("summary-" + ts + ".txt");
        Files.write(summary, lines);
        System.out.println("Summary written to " + summary);
    }

    private static void writeSuspects(Path dir, List<ThreadReport> report, String ts) throws IOException {
        Map<String, Long> counts = report.stream()
                .collect(Collectors.groupingBy(ThreadReport::mod, Collectors.counting()));
        List<String> lines = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + " - " + e.getValue() + " thread(s)")
                .toList();
        Path suspects = dir.resolve("suspects-" + ts + ".txt");
        Files.write(suspects, lines);
        System.out.println("Suspects written to " + suspects);
    }

    private static void writeExplanation(Path dir, List<ThreadReport> report, String ts) throws IOException {
        String apiKey = System.getenv("DEBUG_GUARDIAN_AI_KEY");
        LogAnalyzer analyzer = (apiKey != null && !apiKey.isBlank())
                ? new AiLogAnalyzer(apiKey)
                : new BasicLogAnalyzer();
        String explanation = analyzer.analyze(report);
        Path file = dir.resolve("explanation-" + ts + ".txt");
        Files.writeString(file, explanation);
        System.out.println("Explanation written to " + file);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
