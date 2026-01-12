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
 * <p>
 * Optionally launches a helper JVM process when enabled via config to allow
 * deeper debugging or attachment of external tools.
 */
public class ForceCloseDetector {
    private static final Path DUMP_DIR = FMLPaths.GAMEDIR.get().resolve("logs").resolve("debugguardian");

    /**
     * Starts the optional helper process and, if enabled, the force-close
     * shutdown hook detector.
     */
    public static void start() {
        DebugConfig cfg = DebugConfig.get();

        if (cfg.forceCloseLaunchHelper) {
            DebugGuardian.LOGGER.info("forceClose.launchHelper enabled; starting helper JVM");
            launchHelper();
        }

        if (!cfg.forceCloseEnable) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(ForceCloseDetector::dumpStacks,
                "debugguardian-force-close"));
    }

    private static void dumpStacks() {
        DebugConfig cfg = DebugConfig.get();
        boolean includeJavaBase = cfg.forceCloseIncludeJavaBase;
        try {
            Files.createDirectories(DUMP_DIR);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = DUMP_DIR.resolve("force-close-" + ts + ".log");
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                writeLoadingSnapshot(writer);
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread t = e.getKey();
                    StackTraceElement[] stack = e.getValue();
                    String mod = ClassLoadingIssueDetector.identifyCulpritMod(stack);
                    writer.write("Thread: " + t.getName() + " mod: " + mod + " state: " + t.getState());
                    writer.newLine();
                    for (StackTraceElement ste : stack) {
                        if (!includeJavaBase && isJavaBaseFrame(ste)) {
                            continue;
                        }
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

    private static void writeLoadingSnapshot(BufferedWriter writer) throws IOException {
        LoadingHangDetector.LoadingHangSnapshot snapshot = LoadingHangDetector.snapshot();
        if (snapshot.loadComplete() || snapshot.worldJoined()) {
            return;
        }
        writer.write("==== FORCE CLOSE DURING LOADING ====");
        writer.newLine();
        long elapsed = System.currentTimeMillis() - snapshot.lastProgressTime();
        writer.write("Elapsed without progress: " + elapsed + " ms");
        writer.newLine();
        writer.write("Match count: " + snapshot.matchCount());
        writer.newLine();
        StackTraceElement[] stack = snapshot.lastStack();
        if (stack == null || stack.length == 0) {
            writer.write("No loading stack captured.");
            writer.newLine();
            writer.newLine();
            return;
        }
        String culprit = ClassLoadingIssueDetector.identifyCulpritMod(stack);
        writer.write("Suspected mod: " + culprit);
        writer.newLine();
        writer.write("Top frame: " + stack[0]);
        writer.newLine();
        for (StackTraceElement element : stack) {
            writer.write("    at " + element);
            writer.newLine();
        }
        writer.newLine();
    }

    private static boolean isJavaBaseFrame(StackTraceElement ste) {
        String module = ste.getModuleName();
        if (module != null && module.equals("java.base")) {
            return true;
        }
        String repr = ste.toString();
        return repr.startsWith("java.base/");
    }

    private static void launchHelper() {
        try {
            new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
                    "com.thunder.debugguardian.debug.external.DebugHelper",
                    DUMP_DIR.toString(),
                    FMLPaths.CONFIGDIR.get().resolve(DebugGuardian.MOD_ID + "-common.toml").toString())
                    .inheritIO()
                    .start();
            DebugGuardian.LOGGER.info("Debug helper process launched");
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to launch debug helper process", e);
        }
    }
}
