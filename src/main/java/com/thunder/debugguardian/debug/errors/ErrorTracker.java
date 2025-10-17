package com.thunder.debugguardian.debug.errors;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
import com.thunder.debugguardian.debug.monitor.ClassLoadingIssueDetector;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ErrorTracker extends AbstractFilter {
    private static final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public Filter.Result filter(LogEvent event) {
        if (event.getThrown() != null) {
            String modId = ClassLoadingIssueDetector.identifyCulpritMod(event.getThrown());
            if (!DebugConfig.isModLogOutputEnabled(modId)) {
                // Skip aggregation when the mod's log output is muted in the configuration.
                return Filter.Result.NEUTRAL;
            }
            String fp = ErrorFingerprinter.fingerprint(event.getThrown());
            AtomicInteger c = counts.computeIfAbsent(fp, k -> new AtomicInteger());
            int count = c.incrementAndGet();
            int interval = DebugConfig.get().loggingErrorReportInterval;
            if (count % interval == 1) {
                DebugGuardian.LOGGER.error("[DebugGuardian] Error ({}) occurred {} time(s)", fp, count, event.getThrown());
                return Filter.Result.ACCEPT;
            }
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }
}
