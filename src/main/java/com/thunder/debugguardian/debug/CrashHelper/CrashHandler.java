package com.thunder.debugguardian.debug.CrashHelper;


import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;

public class CrashHandler {
    private static boolean crashed = false;

    public static void register() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (crashed) {
                logCrashDetails();
            }
        }));
    }

    public static void markCrashed() {
        crashed = true;
    }

    public static boolean isCrashed() {
        return crashed;
    }

    public static void logCrashDetails() {
        try {
            File logFile = new File(FMLPaths.GAMEDIR.get().toFile(), "logs/enhanced_crash_report.log");
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                writer.println("========== Enhanced Crash Report ==========");
                writer.println("Timestamp: " + Instant.now());
                writer.println("Mod Causing Crash: " + getModCausingCrash());
                writer.println("Stack Trace:");
                for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                    writer.println("    at " + element.toString());
                }
                writer.println("===========================================");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void logNormalExit() {
        System.out.println("[Enhanced Crash Debugger] Game exited normally.");
    }

    public static String getModCausingCrash() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();

            // Get list of all mods
            List<IModInfo> mods = ModList.get().getMods();
            for (IModInfo mod : mods) {
                if (mod.getOwningFile().getFile().getFileName().toString().contains(className)) {
                    return mod.getModId() + " (" + mod.getVersion() + ")";
                }
            }
        }
        return "Unknown (Vanilla or Unknown Mod)";
    }
}