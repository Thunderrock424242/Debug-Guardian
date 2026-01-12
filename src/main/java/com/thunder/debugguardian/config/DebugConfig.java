package com.thunder.debugguardian.config;

import com.thunder.debugguardian.debug.Watchdog;
import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.debug.monitor.CrashRiskMonitor;
import com.thunder.debugguardian.debug.monitor.GcPauseMonitor;
import com.thunder.debugguardian.debug.monitor.MemoryLeakMonitor;
import com.thunder.debugguardian.debug.replay.PostMortemRecorder;
import net.neoforged.fml.ModList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class DebugConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final Map<String, ModConfigSpec.BooleanValue> LOGGING_MOD_TOGGLES =
            initModLoggingToggles();

    // Post-Mortem Replay Settings
    public static final ModConfigSpec.IntValue POSTMORTEM_BUFFER_SIZE = BUILDER
            .comment("Number of game events to retain for post-mortem dump")
            .defineInRange("postmortem.bufferSize", 500, 100, 10000);

    // GitHub Reporting Settings
    public static final ModConfigSpec.ConfigValue<String> REPORTING_GITHUB_REPO = BUILDER
            .comment("GitHub repository (owner/Repo) for creating crash reports")
            .define("reporting.githubRepository", "YourOrg/YourRepo");

    // Performance Monitoring Settings
    public static final ModConfigSpec.LongValue PERFORMANCE_TICK_THRESHOLD = BUILDER
            .comment("Tick duration (ms) above which a warning is logged")
            .defineInRange("performance.tickThresholdMs", 100L, 1L, 10000L);

    public static final ModConfigSpec.DoubleValue PERFORMANCE_MEMORY_RATIO = BUILDER
            .comment("Heap usage ratio (0.0 - 1.0) to trigger memory warning")
            .defineInRange("performance.memoryWarningRatio", 0.8D, 0.0D, 1.0D);

    // Memory Leak Monitor Settings
    public static final ModConfigSpec.DoubleValue MEMORY_LEAK_WARN_RATIO = BUILDER
            .comment("Heap usage ratio (0.0 - 1.0) that increments the leak streak")
            .defineInRange("monitoring.memoryLeak.warnRatio", 0.9D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue MEMORY_LEAK_WARN_STREAK = BUILDER
            .comment("Number of consecutive checks over the ratio before warning")
            .defineInRange("monitoring.memoryLeak.warnStreak", 3, 1, 100);

    public static final ModConfigSpec.IntValue MEMORY_LEAK_CHECK_INTERVAL = BUILDER
            .comment("Seconds between heap usage checks for the leak monitor")
            .defineInRange("monitoring.memoryLeak.checkIntervalSeconds", 30, 5, 600);

    // GC Pause Monitor Settings
    public static final ModConfigSpec.LongValue GC_PAUSE_WARN_MS = BUILDER
            .comment("GC pause duration (ms) considered suspicious")
            .defineInRange("monitoring.gc.pauseWarnMs", 2_000L, 100L, 60_000L);

    public static final ModConfigSpec.IntValue GC_PAUSE_CHECK_INTERVAL = BUILDER
            .comment("Seconds between GC pause measurements")
            .defineInRange("monitoring.gc.checkIntervalSeconds", 10, 1, 600);

    // Watchdog Settings
    public static final ModConfigSpec.LongValue WATCHDOG_MEMORY_CAP_MB = BUILDER
            .comment("Heap usage (in MB) that triggers a watchdog warning")
            .defineInRange("monitoring.watchdog.maxMemoryMb", 8_000L, 512L, 65_536L);

    public static final ModConfigSpec.IntValue WATCHDOG_THREAD_CAP = BUILDER
            .comment("Thread count that triggers a watchdog warning")
            .defineInRange("monitoring.watchdog.maxThreads", 300, 32, 10_000);

    public static final ModConfigSpec.IntValue WATCHDOG_CHECK_INTERVAL = BUILDER
            .comment("Seconds between watchdog resource checks")
            .defineInRange("monitoring.watchdog.checkIntervalSeconds", 10, 1, 600);

    // Compatibility Scanner Settings
    public static final ModConfigSpec.BooleanValue COMPAT_ENABLE_SCAN = BUILDER
            .comment("Enable scanning for known mod incompatibilities at startup")
            .define("compatibility.enableScan", true);

    // Live Log Monitor Settings
    public static final ModConfigSpec.BooleanValue LOGGING_ENABLE_LIVE = BUILDER
            .comment("Enable real-time in-game log monitoring and notifications")
            .define("logging.enableLiveMonitor", true);

    // AI Log Analyzer Settings
    public static final ModConfigSpec.ConfigValue<String> LOGGING_AI_SERVICE_API_KEY = BUILDER
            .comment("API key for external AI log analyzer; blank uses DEBUG_GUARDIAN_AI_KEY env var")
            .define("logging.aiServiceApiKey", "");

    // Error Tracking Settings
    public static final ModConfigSpec.IntValue LOGGING_ERROR_REPORT_INTERVAL = BUILDER
            .comment("Number of identical errors to skip before logging again")
            .defineInRange("logging.errorReportInterval", 100, 1, 10000);

    // Force Close Debugging Settings
    public static final ModConfigSpec.BooleanValue FORCE_CLOSE_ENABLE = BUILDER
            .comment("Enable capturing mod stacks when the game is forcibly closed")
            .define("debug.forceClose.enable", true);

    public static final ModConfigSpec.BooleanValue FORCE_CLOSE_LAUNCH_HELPER = BUILDER
            .comment("Launch a helper JVM alongside the game for deeper debugging")
            .define("debug.forceClose.launchHelper", false);

    public static final ModConfigSpec.BooleanValue FORCE_CLOSE_INCLUDE_JAVA_BASE = BUILDER
            .comment("Include java.base module frames in force-close thread dumps")
            .define("debug.forceClose.includeJavaBase", true);

    // Crash Risk Monitor Settings
    public static final ModConfigSpec.BooleanValue CRASH_RISK_ENABLE = BUILDER
            .comment("Enable aggregated crash risk detection")
            .define("debug.crashRisk.enable", true);

    // World Integrity Monitoring Settings
    public static final ModConfigSpec.BooleanValue WORLD_AUTO_SCAN_ON_START = BUILDER
            .comment("Automatically scan the active world for issues when the server starts")
            .define("world.autoScanOnStart", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static final DebugConfig DEFAULTS = new DebugConfig(
            500,
            "YourOrg/YourRepo",
            75L,
            0.8D,
            0.9D,
            3,
            30,
            2_000L,
            10,
            8_000L,
            300,
            10,
            true,
            true,
            "",
            100,
            true,
            false,
            true,
            true,
            true,
            snapshotModToggleValues()
    );

    private static volatile DebugConfig instance = DEFAULTS;

    public final int postmortemBufferSize;
    public final String reportingGithubRepository;
    public final long performanceTickThresholdMs;
    public final double performanceMemoryWarningRatio;
    public final double memoryLeakWarnRatio;
    public final int memoryLeakWarnStreak;
    public final int memoryLeakCheckIntervalSeconds;
    public final long gcPauseWarnMs;
    public final int gcPauseCheckIntervalSeconds;
    public final long watchdogMemoryCapMb;
    public final int watchdogThreadCap;
    public final int watchdogCheckIntervalSeconds;
    public final boolean compatibilityEnableScan;
    public final boolean loggingEnableLiveMonitor;
    public final String loggingAiServiceApiKey;
    public final int loggingErrorReportInterval;
    public final boolean forceCloseEnable;
    public final boolean forceCloseLaunchHelper;
    public final boolean forceCloseIncludeJavaBase;
    public final boolean crashRiskEnable;
    public final boolean worldAutoScanOnStart;
    public final Map<String, Boolean> loggingModToggles;

    private DebugConfig(int postmortemBufferSize,
                        String reportingGithubRepository,
                        long performanceTickThresholdMs,
                        double performanceMemoryWarningRatio,
                        double memoryLeakWarnRatio,
                        int memoryLeakWarnStreak,
                        int memoryLeakCheckIntervalSeconds,
                        long gcPauseWarnMs,
                        int gcPauseCheckIntervalSeconds,
                        long watchdogMemoryCapMb,
                        int watchdogThreadCap,
                        int watchdogCheckIntervalSeconds,
                        boolean compatibilityEnableScan,
                        boolean loggingEnableLiveMonitor,
                        String loggingAiServiceApiKey,
                        int loggingErrorReportInterval,
                        boolean forceCloseEnable,
                        boolean forceCloseLaunchHelper,
                        boolean forceCloseIncludeJavaBase,
                        boolean crashRiskEnable,
                        boolean worldAutoScanOnStart,
                        Map<String, Boolean> loggingModToggles) {
        this.postmortemBufferSize = postmortemBufferSize;
        this.reportingGithubRepository = reportingGithubRepository;
        this.performanceTickThresholdMs = performanceTickThresholdMs;
        this.performanceMemoryWarningRatio = performanceMemoryWarningRatio;
        this.memoryLeakWarnRatio = memoryLeakWarnRatio;
        this.memoryLeakWarnStreak = memoryLeakWarnStreak;
        this.memoryLeakCheckIntervalSeconds = memoryLeakCheckIntervalSeconds;
        this.gcPauseWarnMs = gcPauseWarnMs;
        this.gcPauseCheckIntervalSeconds = gcPauseCheckIntervalSeconds;
        this.watchdogMemoryCapMb = watchdogMemoryCapMb;
        this.watchdogThreadCap = watchdogThreadCap;
        this.watchdogCheckIntervalSeconds = watchdogCheckIntervalSeconds;
        this.compatibilityEnableScan = compatibilityEnableScan;
        this.loggingEnableLiveMonitor = loggingEnableLiveMonitor;
        this.loggingAiServiceApiKey = loggingAiServiceApiKey;
        this.loggingErrorReportInterval = loggingErrorReportInterval;
        this.forceCloseEnable = forceCloseEnable;
        this.forceCloseLaunchHelper = forceCloseLaunchHelper;
        this.forceCloseIncludeJavaBase = forceCloseIncludeJavaBase;
        this.crashRiskEnable = crashRiskEnable;
        this.worldAutoScanOnStart = worldAutoScanOnStart;
        this.loggingModToggles = Collections.unmodifiableMap(new LinkedHashMap<>(loggingModToggles));
    }

    private static DebugConfig fromSpec() {
        return new DebugConfig(
                POSTMORTEM_BUFFER_SIZE.get(),
                REPORTING_GITHUB_REPO.get(),
                PERFORMANCE_TICK_THRESHOLD.get(),
                PERFORMANCE_MEMORY_RATIO.get(),
                MEMORY_LEAK_WARN_RATIO.get(),
                MEMORY_LEAK_WARN_STREAK.get(),
                MEMORY_LEAK_CHECK_INTERVAL.get(),
                GC_PAUSE_WARN_MS.get(),
                GC_PAUSE_CHECK_INTERVAL.get(),
                WATCHDOG_MEMORY_CAP_MB.get(),
                WATCHDOG_THREAD_CAP.get(),
                WATCHDOG_CHECK_INTERVAL.get(),
                COMPAT_ENABLE_SCAN.get(),
                LOGGING_ENABLE_LIVE.get(),
                LOGGING_AI_SERVICE_API_KEY.get(),
                LOGGING_ERROR_REPORT_INTERVAL.get(),
                FORCE_CLOSE_ENABLE.get(),
                FORCE_CLOSE_LAUNCH_HELPER.get(),
                FORCE_CLOSE_INCLUDE_JAVA_BASE.get(),
                CRASH_RISK_ENABLE.get(),
                WORLD_AUTO_SCAN_ON_START.get(),
                snapshotModToggleValues()
        );
    }

    public static DebugConfig get() {
        return instance;
    }

    /**
     * Returns {@code true} if the provided mod id is allowed to emit log output according
     * to the configuration. Unknown or unlisted mods are treated as allowed.
     */
    public static boolean isModLogOutputEnabled(String modId) {
        if (modId == null || modId.isBlank() || "Unknown".equalsIgnoreCase(modId)) {
            return true;
        }
        ModConfigSpec.BooleanValue value = LOGGING_MOD_TOGGLES.get(modId);
        if (value == null) {
            return true;
        }
        try {
            return value.get();
        } catch (IllegalStateException ex) {
            return value.getDefault();
        }
    }

    public static Set<String> getConfiguredModIds() {
        return LOGGING_MOD_TOGGLES.keySet();
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            instance = fromSpec();
            Watchdog.reloadFromConfig();
            MemoryLeakMonitor.reloadFromConfig();
            GcPauseMonitor.reloadFromConfig();
            CrashRiskMonitor.reloadFromConfig();
            PostMortemRecorder.reloadFromConfig();
        }
    }

    private static Map<String, ModConfigSpec.BooleanValue> initModLoggingToggles() {
        Map<String, ModConfigSpec.BooleanValue> toggles = new LinkedHashMap<>();
        BUILDER.comment("Per-mod switches for allowing a mod's log output. Set to false to silence"
                        + " all Log4j events emitted by that mod.")
                .push("logging")
                .push("modToggles");
        try {
            ModList.get().getMods().stream()
                    .sorted((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()))
                    .forEach(mod -> {
                        String modId = mod.getModId();
                        toggles.put(modId, BUILDER
                                .comment("Allow log output for "
                                        + mod.getDisplayName() + " (" + modId + ")")
                                .define(modId, true));
                    });
        } catch (Exception ex) {
            DebugGuardian.LOGGER.warn("Could not enumerate mods for log toggles; defaulting to enabled.", ex);
        }
        BUILDER.pop();
        BUILDER.pop();
        return Collections.unmodifiableMap(toggles);
    }

    private static Map<String, Boolean> snapshotModToggleValues() {
        Map<String, Boolean> snapshot = new LinkedHashMap<>();
        LOGGING_MOD_TOGGLES.forEach((modId, value) -> {
            boolean enabled;
            try {
                enabled = value.get();
            } catch (IllegalStateException ex) {
                enabled = value.getDefault();
            }
            snapshot.put(modId, enabled);
        });
        return snapshot;
    }
}
