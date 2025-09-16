package com.thunder.debugguardian.debug.external;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Helper application launched by the /worldcheck command. It performs a series
 * of heuristic inspections of the world save folder to look for signs of
 * corruption or structural issues and produces a human readable report.
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

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Files.isDirectory(worldDir)) {
            errors.add("World directory does not exist or is not a directory: " + worldDir.toAbsolutePath());
        } else {
            inspectWorld(worldDir, warnings, errors);
        }

        String status = errors.isEmpty() ? (warnings.isEmpty() ? "OK" : "WARNINGS") : "ERRORS";
        List<String> report = new ArrayList<>();
        report.add("Debug Guardian World Integrity Report");
        report.add("Generated: " + FORMAT.format(LocalDateTime.now()));
        report.add("World: " + worldDir.toAbsolutePath());
        report.add("Status: " + status);
        report.add("Summary: " + errors.size() + " critical, " + warnings.size() + " warnings");
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

        if (!errors.isEmpty()) {
            System.exit(2);
        } else if (!warnings.isEmpty()) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    private static void inspectWorld(Path worldDir, List<String> warnings, List<String> errors) {
        Path levelDat = worldDir.resolve("level.dat");
        Path sessionLock = worldDir.resolve("session.lock");
        Path levelDatOld = worldDir.resolve("level.dat_old");

        checkFile(levelDat, "level.dat", true, warnings, errors);
        validateCompressedNbt(levelDat, "level.dat", true, warnings, errors);

        checkFile(sessionLock, "session.lock", false, warnings, errors);

        checkFile(levelDatOld, "level.dat_old", false, warnings, errors);
        validateCompressedNbt(levelDatOld, "level.dat_old", false, warnings, errors);

        checkRegionDirectory(worldDir.resolve("region"), "Overworld", warnings, errors);
        checkRegionDirectory(worldDir.resolve("DIM-1").resolve("region"), "The Nether", warnings, errors);
        checkRegionDirectory(worldDir.resolve("DIM1").resolve("region"), "The End", warnings, errors);

        inspectCustomDimensions(worldDir.resolve("dimensions"), warnings, errors);

        checkPlayerData(worldDir.resolve("playerdata"), warnings, errors);
        checkPoiDirectory(worldDir.resolve("poi"), "Overworld", warnings);
        checkPoiDirectory(worldDir.resolve("DIM-1").resolve("poi"), "The Nether", warnings);
        checkPoiDirectory(worldDir.resolve("DIM1").resolve("poi"), "The End", warnings);
    }

    private static void checkFile(Path file, String description, boolean critical, List<String> warnings, List<String> errors) {
        if (Files.notExists(file)) {
            String message = description + " is missing (" + file.toAbsolutePath() + ")";
            if (critical) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
            return;
        }

        try {
            long size = Files.size(file);
            if (size == 0L) {
                String message = description + " is empty (" + file.toAbsolutePath() + ")";
                if (critical) {
                    errors.add(message);
                } else {
                    warnings.add(message);
                }
            }
        } catch (IOException e) {
            String message = "Failed to read " + description + ": " + e.getMessage();
            if (critical) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        }
    }

    private static void checkRegionDirectory(Path dir, String label, List<String> warnings, List<String> errors) {
        if (Files.notExists(dir)) {
            warnings.add(label + " has no region directory (" + dir.toAbsolutePath() + "); no chunks may have been generated yet.");
            return;
        }

        if (!Files.isDirectory(dir)) {
            errors.add(label + " region path is not a directory: " + dir.toAbsolutePath());
            return;
        }

        int regionFiles = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.endsWith(".mca")) {
                    regionFiles++;
                    inspectRegionFile(entry, label, warnings, errors);
                } else if (name.endsWith(".mcc") || name.endsWith(".tmp")) {
                    warnings.add(label + " region contains stray file " + name + " (" + entry.toAbsolutePath() + ")");
                }
            }
        } catch (IOException e) {
            errors.add("Failed to inspect region directory for " + label + ": " + e.getMessage());
            return;
        }

        if (regionFiles == 0) {
            warnings.add(label + " region directory contains no .mca files (" + dir.toAbsolutePath() + ")");
        }
    }

    private static void inspectRegionFile(Path file, String label, List<String> warnings, List<String> errors) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            warnings.add("Failed to read region file " + file.getFileName() + " for " + label + ": " + e.getMessage());
            return;
        }

        if (size == 0L) {
            errors.add(label + " region file " + file.getFileName() + " is empty");
            return;
        } else if (size < 16 * 1024L) {
            warnings.add(label + " region file " + file.getFileName() + " is unusually small (" + size + " bytes)");
        }

        if (size % 4096L != 0L) {
            warnings.add(label + " region file " + file.getFileName() + " size is not aligned to 4KiB (" + size + " bytes)");
        }

        validateRegionHeader(file, label, size, warnings, errors);
    }

    private static void inspectCustomDimensions(Path dimensionsDir, List<String> warnings, List<String> errors) {
        if (Files.notExists(dimensionsDir)) {
            return;
        }

        if (!Files.isDirectory(dimensionsDir)) {
            warnings.add("Dimensions path is not a directory: " + dimensionsDir.toAbsolutePath());
            return;
        }

        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(dimensionsDir)) {
            for (Path namespace : namespaces) {
                if (!Files.isDirectory(namespace)) {
                    continue;
                }
                try (DirectoryStream<Path> dims = Files.newDirectoryStream(namespace)) {
                    for (Path dim : dims) {
                        if (!Files.isDirectory(dim)) {
                            continue;
                        }
                        String label = "Dimension " + dimensionsDir.relativize(dim).toString().replace('\\', '/');
                        checkRegionDirectory(dim.resolve("region"), label, warnings, errors);
                        checkPoiDirectory(dim.resolve("poi"), label, warnings);
                    }
                }
            }
        } catch (IOException e) {
            warnings.add("Failed to inspect custom dimensions: " + e.getMessage());
        }
    }

    private static void checkPlayerData(Path dir, List<String> warnings, List<String> errors) {
        if (Files.notExists(dir)) {
            return;
        }

        if (!Files.isDirectory(dir)) {
            warnings.add("Player data path is not a directory: " + dir.toAbsolutePath());
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.dat")) {
            for (Path entry : stream) {
                try {
                    long size = Files.size(entry);
                    if (size == 0L) {
                        warnings.add("Player data file " + entry.getFileName() + " is empty");
                    }
                } catch (IOException e) {
                    warnings.add("Failed to read player data file " + entry.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warnings.add("Failed to inspect player data directory: " + e.getMessage());
        }
    }

    private static void checkPoiDirectory(Path dir, String label, List<String> warnings) {
        if (Files.notExists(dir)) {
            return;
        }

        if (!Files.isDirectory(dir)) {
            warnings.add(label + " POI path is not a directory: " + dir.toAbsolutePath());
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.poi")) {
            for (Path entry : stream) {
                try {
                    long size = Files.size(entry);
                    if (size == 0L) {
                        warnings.add(label + " POI file " + entry.getFileName() + " is empty");
                    } else if (size % 4096L != 0L) {
                        warnings.add(label + " POI file " + entry.getFileName() + " size is not aligned to 4KiB (" + size + " bytes)");
                    }
                } catch (IOException e) {
                    warnings.add("Failed to read POI file " + entry.getFileName() + " for " + label + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warnings.add("Failed to inspect POI directory for " + label + ": " + e.getMessage());
        }
    }

    private static void validateCompressedNbt(Path file, String description, boolean critical, List<String> warnings, List<String> errors) {
        if (Files.notExists(file)) {
            return;
        }

        try (InputStream input = Files.newInputStream(file); GZIPInputStream gzip = new GZIPInputStream(input)) {
            byte[] buffer = new byte[8192];
            boolean hasData = false;
            while (gzip.read(buffer) != -1) {
                hasData = true;
            }
            if (!hasData) {
                String message = description + " decompressed to zero bytes, file may be truncated";
                if (critical) {
                    errors.add(message);
                } else {
                    warnings.add(message);
                }
            }
        } catch (ZipException e) {
            String message = description + " is not a valid compressed NBT file: " + e.getMessage();
            if (critical) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        } catch (IOException e) {
            String message = "Failed to read " + description + " for integrity check: " + e.getMessage();
            if (critical) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        }
    }

    private static void validateRegionHeader(Path file, String label, long size, List<String> warnings, List<String> errors) {
        if (size < 8192L) {
            errors.add(label + " region file " + file.getFileName() + " is too small to contain a valid header (" + size + " bytes)");
            return;
        }

        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            int read = channel.read(header);
            if (read < 4096) {
                errors.add(label + " region file " + file.getFileName() + " header is truncated (" + read + " bytes read)");
                return;
            }

            header.flip();
            long totalSectors = size / 4096L;
            boolean hasChunkEntries = false;
            boolean invalidOffsets = false;
            boolean invalidSizes = false;
            boolean zeroLengthChunks = false;

            while (header.remaining() >= 4) {
                int entry = header.getInt();
                int offset = (entry >>> 8) & 0xFFFFFF;
                int sectors = entry & 0xFF;

                if (offset == 0 && sectors == 0) {
                    continue;
                }

                if (sectors == 0) {
                    zeroLengthChunks = true;
                    continue;
                }

                hasChunkEntries = true;

                if (offset < 2) {
                    invalidOffsets = true;
                }

                long chunkEnd = (long) offset + sectors;
                if (chunkEnd > totalSectors) {
                    invalidSizes = true;
                }
            }

            if (!hasChunkEntries) {
                warnings.add(label + " region file " + file.getFileName() + " header lists no chunks; file may be empty or truncated");
            }
            if (invalidOffsets) {
                errors.add(label + " region file " + file.getFileName() + " has chunk entries with invalid sector offsets");
            }
            if (invalidSizes) {
                errors.add(label + " region file " + file.getFileName() + " has chunk entries that extend beyond the file size");
            }
            if (zeroLengthChunks) {
                warnings.add(label + " region file " + file.getFileName() + " has chunk entries with zero length; file may be mid-write or corrupted");
            }
        } catch (IOException e) {
            warnings.add("Failed to read region header for " + label + " file " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
