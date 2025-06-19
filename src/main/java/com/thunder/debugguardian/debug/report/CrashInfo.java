package com.thunder.debugguardian.debug.report;

import net.neoforged.fml.loading.moddiscovery.ModInfo;

import java.util.List;

public class CrashInfo {
    public final List<ModInfo> mods;
    public final String javaVersion;
    public final String[] javaArgs;
    public final String fingerprint;
    public final String stackTop;

    public CrashInfo(List<ModInfo> mods, String javaVersion, String[] javaArgs, String fingerprint, String stackTop) {
        this.mods = mods;
        this.javaVersion = javaVersion;
        this.javaArgs = javaArgs;
        this.fingerprint = fingerprint;
        this.stackTop = stackTop;
    }
}