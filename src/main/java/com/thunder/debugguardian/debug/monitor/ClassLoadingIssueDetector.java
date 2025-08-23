package com.thunder.debugguardian.debug.monitor;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
        String mod = identifyCulpritMod(t.getStackTrace());
        if (!"Unknown".equals(mod)) {
            return mod;
        }
        if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException) {
            String missing = t.getMessage();
            if (missing != null) {
                String deep = scanForMissingClass(missing.replace('.', '/'));
                if (deep != null) {
                    return deep;
                }
            }
        }
        return "Unknown";
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

    /**
     * Returns the first stack trace element that appears to originate from a mod
     * or, if none match, the first non-JDK/MC frame. Falls back to the top
     * frame when all elements come from the JDK or Minecraft core.
     */
    public static StackTraceElement findCulpritFrame(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        for (StackTraceElement ste : stack) {
            if (!"Unknown".equals(identifyCulpritMod(new StackTraceElement[]{ste}))) {
                return ste;
            }
        }
        for (StackTraceElement ste : stack) {
            String cls = ste.getClassName();
            if (!cls.startsWith("java.") &&
                !cls.startsWith("jdk.") &&
                !cls.startsWith("sun.") &&
                !cls.startsWith("net.minecraft.") &&
                !cls.startsWith("com.mojang.") &&
                !cls.startsWith("net.neoforged.")) {
                return ste;
            }
        }
        return stack[0];
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

    /**
     * As a last resort, search each mod jar for references to the missing class
     * name. This is a heuristic and may produce false positives but can help
     * pinpoint the culprit when the stack trace is inconclusive.
     */
    private static String scanForMissingClass(String missingInternal) {
        for (IModInfo mod : ModList.get().getMods()) {
            try (JarFile jar = new JarFile(mod.getOwningFile().getFile().getFilePath().toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        if (content.contains(missingInternal)) {
                            return mod.getModId();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
