package com.thunder.debugguardian.debug.monitor;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Attempts to identify which mod is responsible for a crash or
 * class-loading issue by inspecting the stack trace.
 */
public class ClassLoadingIssueDetector {
    /**
     * Scans the throwable’s stack trace and returns the first modId
     * whose package or code source matches a frame, or "Unknown".
     */
    public static String identifyCulpritMod(Throwable t) {
        if (t == null) return "Unknown";
        return identifyCulpritMod(t.getStackTrace());
    }

    /**
     * Scans the stack trace elements directly and returns the first modId
     * whose package prefix or jar file matches a frame. Falls back to
     * "Unknown" if none match.
     */
    public static String identifyCulpritMod(StackTraceElement[] stack) {
        if (stack == null) return "Unknown";
        for (StackTraceElement ste : stack) {
            String cls = ste.getClassName();
            // first try package prefix match
            for (IModInfo mod : ModList.get().getMods()) {
                if (cls.startsWith(mod.getModId() + ".")) {
                    return mod.getModId();
                }
            }
            // fall back to looking up the jar file
            String modId = findByCodeSource(cls);
            if (modId != null) {
                return modId;
            }
        }
        return "Unknown";
    }

    private static String findByCodeSource(String clsName) {
        try {
            Class<?> cls = Class.forName(clsName);
            CodeSource src = cls.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            Path path = Path.of(src.getLocation().toURI());
            for (IModInfo mod : ModList.get().getMods()) {
                if (path.equals(mod.getOwningFile().getFile().getFilePath())) {
                    return mod.getModId();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
