package com.thunder.debugguardian.debug.external;

import com.thunder.debugguardian.debug.world.WorldInspectionResult;
import com.thunder.debugguardian.debug.world.WorldIntegrityScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Helper application launched by the /worldcheck command. It delegates to the
 * shared {@link WorldIntegrityScanner} to inspect the provided world directory
 * and writes a human readable report for administrators.
 */
public final class WorldCheckHelper {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WorldCheckHelper() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: WorldCheckHelper <world directory> <report file>");
            System.exit(1);
            return;
        }

        Path worldDir = Paths.get(args[0]);
        Path reportFile = Paths.get(args[1]);

        WorldInspectionResult result = WorldIntegrityScanner.scan(worldDir);
        List<String> report = result.buildReportLines(LocalDateTime.now(), FORMAT);

        try {
            Path parent = reportFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(reportFile, report, StandardCharsets.UTF_8);
            System.out.println("World integrity report written to " + reportFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write world integrity report: " + e.getMessage());
            System.exit(3);
            return;
        }

        System.exit(result.exitCode());
    }
}
