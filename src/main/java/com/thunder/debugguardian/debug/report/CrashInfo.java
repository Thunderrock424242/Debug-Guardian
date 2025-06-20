package com.thunder.debugguardian.debug.report;

import java.util.List;

/**
 * Holds all relevant data for a crash report.
 *
 * @param mods List of mods present when the crash occurred
 */
public record CrashInfo(List<ModInfo> mods, String javaVersion, String[] javaArgs, String fingerprint,
                        String stackTop) {
}
