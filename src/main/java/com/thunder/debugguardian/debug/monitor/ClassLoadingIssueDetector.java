package com.thunder.debugguardian.debug.monitor;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

public class ClassLoadingIssueDetector {
    /**
     * Scans the throwable’s stack trace and returns the first modId
     * whose package prefix matches a frame, or "Unknown".
     */
    public static String identifyCulpritMod(Throwable t) {
        if (t == null) return "Unknown";
        for (StackTraceElement ste : t.getStackTrace()) {
            String cls = ste.getClassName();
            for (IModInfo mod : ModList.get().getMods()) {
                if (cls.startsWith(mod.getModId() + ".")) {
                    return mod.getModId();
                }
            }
        }
        return "Unknown";
    }
}