package com.thunder.debugguardian.debug.report;

import java.util.List;

/**
 * Holds all relevant data for a crash report.
 */
public class CrashInfo {
    /** List of mods present when the crash occurred */
    public final List<ModInfo> mods;
    public final String javaVersion;
    public final String[] javaArgs;
    public final String fingerprint;
    public final String stackTop;

    public CrashInfo(List<ModInfo> mods,
                     String javaVersion,
                     String[] javaArgs,
                     String fingerprint,
                     String stackTop) {
        this.mods = mods;
        this.javaVersion = javaVersion;
        this.javaArgs = javaArgs;
        this.fingerprint = fingerprint;
        this.stackTop = stackTop;
    }
}
