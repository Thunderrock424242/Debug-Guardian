package com.thunder.debugguardian.config;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class DebugGuardianConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** URL to open when reporting an issue */
    public static final ModConfigSpec.ConfigValue<String> REPORT_LINK = BUILDER
            .comment("URL to open when reporting an issue")
            .define("reportLink", "https://github.com/YourModpack/issues");

    /** The built config specification (register this with ModContainer) */
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Runtime copy of the configured report link */
    public static String reportLink;

    @SubscribeEvent
    public static void onLoad(final ModConfigEvent event) {
        // Only handle our own spec
        if (event.getConfig().getSpec() == SPEC) {
            reportLink = REPORT_LINK.get();
        }
    }
}
