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
 * stack traces, writes the report to disk, and then exits.
 */
public class DebugHelper {

    private record ThreadReport(String thread, String mod, List<String> stack) {}

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
        List<String> currentStack = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("Thread: ")) {
                if (currentThread != null) {
                    threads.add(new ThreadReport(currentThread, currentMod, currentStack));
                    currentStack = new ArrayList<>();
                }
                String rest = line.substring("Thread: ".length());
                String[] parts = rest.split(" mod: ", 2);
                currentThread = parts[0].trim();
                currentMod = parts.length > 1 ? parts[1].trim() : "unknown";
            } else if (line.startsWith("    at ")) {
                currentStack.add(line.trim());
            } else if (line.isBlank() && currentThread != null) {
                threads.add(new ThreadReport(currentThread, currentMod, currentStack));
                currentThread = null;
                currentMod = null;
                currentStack = new ArrayList<>();
            }
        }

        if (currentThread != null) {
            threads.add(new ThreadReport(currentThread, currentMod, currentStack));
        }

        return threads;
    }

    private static void writeSummary(Path dir, List<ThreadReport> report, String ts) throws IOException {
        List<String> lines = new ArrayList<>();
        for (ThreadReport tr : report) {
            lines.add(tr.thread() + " - " + tr.mod() + " (" + tr.stack().size() + " frames)");
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

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
