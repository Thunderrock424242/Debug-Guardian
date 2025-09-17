package com.thunder.debugguardian.debug.world;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable record describing the outcome of a world integrity scan.
 */
public record WorldInspectionResult(Path worldDir, List<String> errors, List<String> warnings) {
    private static final DateTimeFormatter REPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public WorldInspectionResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public String status() {
        if (!errors.isEmpty()) {
            return "ERRORS";
        }
        if (!warnings.isEmpty()) {
            return "WARNINGS";
        }
        return "OK";
    }

    public String summaryCounts() {
        return errors.size() + " critical, " + warnings.size() + " warnings";
    }

    public int exitCode() {
        if (!errors.isEmpty()) {
            return 2;
        }
        if (!warnings.isEmpty()) {
            return 1;
        }
        return 0;
    }

    public List<String> buildReportLines() {
        return buildReportLines(LocalDateTime.now(), REPORT_TIME_FORMAT);
    }

    public List<String> buildReportLines(LocalDateTime timestamp, DateTimeFormatter formatter) {
        List<String> report = new ArrayList<>();
        report.add("Debug Guardian World Integrity Report");
        report.add("Generated: " + formatter.format(timestamp));
        report.add("World: " + worldDir.toAbsolutePath());
        report.add("Status: " + status());
        report.add("Summary: " + summaryCounts());
        report.add("");

        if (!errors.isEmpty()) {
            report.add("Critical issues:");
            errors.forEach(e -> report.add(" - " + e));
            report.add("");
        }

        if (!warnings.isEmpty()) {
            report.add("Warnings:");
            warnings.forEach(w -> report.add(" - " + w));
            report.add("");
        }

        if (errors.isEmpty() && warnings.isEmpty()) {
            report.add("No problems were detected.");
            report.add("");
        }

        return report;
    }
}
