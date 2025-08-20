package com.thunder.debugguardian.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class DebugConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

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
            .defineInRange("performance.tickThresholdMs", 50L, 1L, 10000L);

    public static final ModConfigSpec.DoubleValue PERFORMANCE_MEMORY_RATIO = BUILDER
            .comment("Heap usage ratio (0.0 - 1.0) to trigger memory warning")
            .defineInRange("performance.memoryWarningRatio", 0.8D, 0.0D, 1.0D);

    // Compatibility Scanner Settings
    public static final ModConfigSpec.BooleanValue COMPAT_ENABLE_SCAN = BUILDER
            .comment("Enable scanning for known mod incompatibilities at startup")
            .define("compatibility.enableScan", true);

    // Live Log Monitor Settings
    public static final ModConfigSpec.BooleanValue LOGGING_ENABLE_LIVE = BUILDER
            .comment("Enable real-time in-game log monitoring and notifications")
            .define("logging.enableLiveMonitor", true);

    // Error Tracking Settings
    public static final ModConfigSpec.IntValue LOGGING_ERROR_REPORT_INTERVAL = BUILDER
            .comment("Number of identical errors to skip before logging again")
            .defineInRange("logging.errorReportInterval", 100, 1, 10000);

    public static final ModConfigSpec SPEC = BUILDER.build();


    private static DebugConfig instance;
    public final int postmortemBufferSize;
    public final String reportingGithubRepository;
    public final long performanceTickThresholdMs;
    public final double performanceMemoryWarningRatio;
    public final boolean compatibilityEnableScan;
    public final boolean loggingEnableLiveMonitor;
    public final int loggingErrorReportInterval;

    private DebugConfig() {
        this.postmortemBufferSize = POSTMORTEM_BUFFER_SIZE.get();
        this.reportingGithubRepository = REPORTING_GITHUB_REPO.get();
        this.performanceTickThresholdMs = PERFORMANCE_TICK_THRESHOLD.get();
        this.performanceMemoryWarningRatio = PERFORMANCE_MEMORY_RATIO.get();
        this.compatibilityEnableScan = COMPAT_ENABLE_SCAN.get();
        this.loggingEnableLiveMonitor = LOGGING_ENABLE_LIVE.get();
        this.loggingErrorReportInterval = LOGGING_ERROR_REPORT_INTERVAL.get();
    }

    public static DebugConfig get() {
        if (instance == null) instance = new DebugConfig();
        return instance;
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            instance = new DebugConfig();
        }
    }
}
