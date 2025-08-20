package com.thunder.debugguardian.debug.errors;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;
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
            String fp = ErrorFingerprinter.fingerprint(event.getThrown());
            AtomicInteger c = counts.computeIfAbsent(fp, k -> new AtomicInteger());
            int count = c.incrementAndGet();
            int interval = DebugConfig.get().loggingErrorReportInterval;
            if (count % interval == 1) {
                DebugGuardian.LOGGER.error("[DebugGuardian] Error (" + fp + ") occurred " + count + " time(s)", event.getThrown());
                return Filter.Result.ACCEPT;
            }
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }
}
