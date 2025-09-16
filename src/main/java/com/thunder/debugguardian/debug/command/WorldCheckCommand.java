package com.thunder.debugguardian.debug.command;

import com.mojang.brigadier.Command;
import com.thunder.debugguardian.DebugGuardian;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Registers the /worldcheck command which launches an external helper process
 * to analyse the current world directory for signs of corruption.
 */
@EventBusSubscriber(modid = MOD_ID)
public final class WorldCheckCommand {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private WorldCheckCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("worldcheck")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> execute(ctx.getSource()))
        );
    }

    private static int execute(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        Path reportDir = FMLPaths.GAMEDIR.get().resolve("debugguardian").resolve("worldchecks");
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path reportFile = reportDir.resolve("worldcheck-" + timestamp + ".txt");

        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to create world check report directory", e);
            source.sendFailure(Component.literal("Failed to prepare world check report directory: " + e.getMessage()));
            return 0;
        }

        Component startMessage = Component.literal("Starting world integrity scan; report will be saved to " + reportFile.toAbsolutePath());
        source.sendSuccess(() -> startMessage, false);

        Thread worker = new Thread(() -> runHelper(server, source, worldDir, reportFile), "debugguardian-worldcheck");
        worker.setDaemon(true);
        worker.start();
        return Command.SINGLE_SUCCESS;
    }

    private static void runHelper(MinecraftServer server, CommandSourceStack source, Path worldDir, Path reportFile) {
        Process process;
        try {
            process = new ProcessBuilder(
                    "java",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "com.thunder.debugguardian.debug.external.WorldCheckHelper",
                    worldDir.toAbsolutePath().toString(),
                    reportFile.toAbsolutePath().toString()
            )
                    .inheritIO()
                    .start();
        } catch (IOException e) {
            DebugGuardian.LOGGER.error("Failed to launch world check helper", e);
            server.execute(() -> source.sendFailure(Component.literal("Failed to launch world check helper: " + e.getMessage())));
            return;
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.execute(() -> source.sendFailure(Component.literal("World check was interrupted.")));
            return;
        }

        int finalExitCode = exitCode;
        server.execute(() -> reportResult(source, reportFile, finalExitCode));
    }

    private static void reportResult(CommandSourceStack source, Path reportFile, int exitCode) {
        String defaultStatus = switch (exitCode) {
            case 0 -> "No issues detected";
            case 1 -> "Warnings detected";
            default -> "Errors detected";
        };

        if (Files.exists(reportFile)) {
            String summary = defaultStatus;
            try {
                List<String> lines = Files.readAllLines(reportFile);
                String statusText = null;
                String summaryCounts = null;
                String firstDetail = null;
                for (String line : lines) {
                    if (statusText == null && line.startsWith("Status:")) {
                        statusText = line.substring("Status:".length()).trim();
                    } else if (summaryCounts == null && line.startsWith("Summary:")) {
                        summaryCounts = line.substring("Summary:".length()).trim();
                    } else if (firstDetail == null && line.startsWith(" - ")) {
                        firstDetail = line.substring(3).trim();
                    }

                    if (statusText != null && summaryCounts != null && firstDetail != null) {
                        break;
                    }
                }

                StringBuilder builder = new StringBuilder(summary);
                if (statusText != null && !statusText.isEmpty()) {
                    builder.append(" (Status: ").append(statusText).append(")");
                }
                if (summaryCounts != null && !summaryCounts.isEmpty()) {
                    builder.append(" (").append(summaryCounts).append(")");
                }
                if (firstDetail != null && !firstDetail.isEmpty()) {
                    builder.append(" (").append(firstDetail).append(")");
                }
                summary = builder.toString();
            } catch (IOException e) {
                DebugGuardian.LOGGER.warn("Failed to read world check report", e);
            }

            final Component message = Component.literal("World check completed: " + summary + ". Report saved to " + reportFile.toAbsolutePath());
            if (exitCode >= 2) {
                source.sendFailure(message);
            } else {
                source.sendSuccess(() -> message, false);
            }
        } else {
            final Component message = Component.literal("World check finished: " + defaultStatus + ", but no report file was generated.");
            if (exitCode >= 2) {
                source.sendFailure(message);
            } else {
                source.sendSuccess(() -> message, false);
            }
        }
    }
}
