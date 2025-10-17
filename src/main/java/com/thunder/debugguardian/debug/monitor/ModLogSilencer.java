package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.config.DebugConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Installs a Log4j filter that suppresses log events from mods whose
 * logging has been disabled in the Debug Guardian configuration.
 */
public final class ModLogSilencer {
    private static final Filter FILTER = new SilencingFilter();
    private static volatile boolean installed;

    private ModLogSilencer() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        root.addFilter(FILTER);
        context.updateLoggers();
        installed = true;
    }

    private static final class SilencingFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null) {
                return Result.NEUTRAL;
            }
            String modId = identifySourceMod(event);
            if (!DebugConfig.isModLogOutputEnabled(modId)) {
                return Result.DENY;
            }
            return Result.NEUTRAL;
        }

        private String identifySourceMod(LogEvent event) {
            if (event.getThrown() != null) {
                String culprit = ClassLoadingIssueDetector
                        .identifyCulpritMod(event.getThrown());
                if (!"Unknown".equals(culprit)) {
                    return culprit;
                }
            }
            String fromLogger = ClassLoadingIssueDetector
                    .identifyModByLoggerName(event.getLoggerName());
            if (!"Unknown".equals(fromLogger)) {
                return fromLogger;
            }
            return "Unknown";
        }
    }
}

