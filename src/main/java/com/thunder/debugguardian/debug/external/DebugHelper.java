package com.thunder.debugguardian.debug.external;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
            Path out = dumpDir.resolve("analysis-" + timestamp() + ".json");
            Files.writeString(out, new Gson().toJson(report));
            System.out.println("Analysis written to " + out);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Path waitForDumpFile(Path dir) throws InterruptedException, IOException {
        while (true) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "force-close-*.log")) {
                for (Path p : stream) {
                    return p;
                }
            } catch (NoSuchFileException e) {
                Files.createDirectories(dir);
            }
            Thread.sleep(1000);
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

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
