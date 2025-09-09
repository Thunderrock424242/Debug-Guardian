package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Registers a shutdown hook that captures thread stacks when the game is
 * forcibly closed. The stacks are analysed to identify potential mod culprits
 * and written to a timestamped log file for later inspection.
 *
 * Optionally launches a helper JVM process when enabled via config to allow
 * deeper debugging or attachment of external tools.
 */
public class ForceCloseDetector {
    private static final Path DUMP_DIR = FMLPaths.GAMEDIR.get().resolve("debugguardian");

    /** Starts the detector and optional helper process based on config. */
    public static void start() {
        DebugConfig cfg = DebugConfig.get();
        if (!cfg.forceCloseEnable) {
            return;
        }

        if (cfg.forceCloseLaunchHelper) {
            launchHelper();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(ForceCloseDetector::dumpStacks,
                "debugguardian-force-close"));
    }

    private static void dumpStacks() {
        try {
            Files.createDirectories(DUMP_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = DUMP_DIR.resolve("force-close-" + ts + ".log");
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread t = e.getKey();
                    StackTraceElement[] stack = e.getValue();
                    String mod = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                    writer.write("Thread: " + t.getName() + " mod: " + mod);
                    writer.newLine();
                    for (StackTraceElement ste : stack) {
                        writer.write("    at " + ste);
                        writer.newLine();
                    }
                    writer.newLine();
                }
            }
            DebugGuardian.LOGGER.warn("Force-close thread dump written to {}", file);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to write force-close dump", e);
        }
    }

    private static void launchHelper() {
        try {
            new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
                    "com.thunder.debugguardian.debug.external.DebugHelper").start();
            DebugGuardian.LOGGER.info("Debug helper process launched");
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to launch debug helper process", e);
        }
    }
}

