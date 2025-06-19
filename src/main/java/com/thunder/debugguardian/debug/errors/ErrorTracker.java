package com.thunder.debugguardian.debug.errors;

import com.thunder.debugguardian.DebugGuardian;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ErrorTracker extends AbstractFilter {
    private static final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private static final int REPORT_INTERVAL = 1;

    @Override
    public Filter.Result filter(LogEvent event) {
        if (event.getThrown() != null) {
            String fp = ErrorFingerprinter.fingerprint(event.getThrown());
            AtomicInteger c = counts.computeIfAbsent(fp, k -> new AtomicInteger());
            int count = c.incrementAndGet();
            if (count % REPORT_INTERVAL == 1) {
                DebugGuardian.LOGGER.error("[DebugGuardian] Error (" + fp + ") occurred " + count + " time(s)", event.getThrown());
                return Filter.Result.ACCEPT;
            }
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }
}
