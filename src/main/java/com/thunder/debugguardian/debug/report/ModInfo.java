package com.thunder.debugguardian.debug.report;

/** Simple data holder for a mod’s ID & version */
public class ModInfo {
    private final String id;
    private final String version;

    public ModInfo(String id, String version) {
        this.id = id;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }
}