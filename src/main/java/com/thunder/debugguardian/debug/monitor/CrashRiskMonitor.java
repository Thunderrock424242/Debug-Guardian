package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;
import com.thunder.debugguardian.config.DebugConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates signals from various monitors to detect situations that commonly
 * precede a crash. Individual monitors may call {@link #recordSymptom(String,
 * Severity, String)} when they notice suspicious behaviour. The monitor keeps
 * the strongest symptoms alive for a short period and periodically evaluates
 * whether the combined score indicates an elevated crash risk.
 */
public final class CrashRiskMonitor {
    private static final long SYMPTOM_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long ALERT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final double ALERT_THRESHOLD = 5.0;

    private static final Map<String, Symptom> SYMPTOMS = new ConcurrentHashMap<>();

    private static ScheduledExecutorService executor;
    private static volatile long lastAlert;

    private CrashRiskMonitor() {
    }

    /**
     * Starts the periodic evaluator if the feature is enabled via config.
     */
    public static void start() {
        if (!DebugConfig.get().crashRiskEnable) {
            return;
        }
        synchronized (CrashRiskMonitor.class) {
            if (executor != null && !executor.isShutdown()) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "debugguardian-crash-risk");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(CrashRiskMonitor::evaluate, 30, 30, TimeUnit.SECONDS);
        }
    }

    /**
     * Stops the evaluator and clears any recorded symptoms.
     */
    public static void stop() {
        synchronized (CrashRiskMonitor.class) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        SYMPTOMS.clear();
    }

    /**
     * Records a suspicious signal. Each key represents a single signal source;
     * new data refreshes the timestamp and increases the occurrence count so
     * recurring problems are considered more severe.
     */
    public static void recordSymptom(String key, Severity severity, String description) {
        if (!DebugConfig.get().crashRiskEnable) {
            return;
        }
        long now = System.currentTimeMillis();
        SYMPTOMS.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Symptom(severity, description, now);
            }
            existing.refresh(severity, description, now);
            return existing;
        });
    }

    private static void evaluate() {
        long now = System.currentTimeMillis();
        double totalScore = 0.0;
        List<SymptomSnapshot> active = new ArrayList<>();

        Iterator<Map.Entry<String, Symptom>> it = SYMPTOMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Symptom> entry = it.next();
            Symptom symptom = entry.getValue();
            long age = now - symptom.timestamp;
            if (age > SYMPTOM_TTL_MS) {
                it.remove();
                continue;
            }
            double weight = symptom.weight() * decay(age);
            if (weight <= 0.0) {
                continue;
            }
            totalScore += weight;
            active.add(new SymptomSnapshot(entry.getKey(), symptom, weight));
        }

        if (active.isEmpty()) {
            return;
        }

        if (totalScore >= ALERT_THRESHOLD && now - lastAlert > ALERT_INTERVAL_MS) {
            active.sort(Comparator.comparingDouble((SymptomSnapshot s) -> s.weight).reversed());
            StringBuilder builder = new StringBuilder();
            builder.append("Potential crash risk detected (score ")
                    .append(String.format(Locale.ROOT, "%.2f", totalScore))
                    .append(") at ")
                    .append(DateTimeFormatter.ISO_LOCAL_TIME.format(LocalDateTime.now()))
                    .append(". Signals: ");
            int limit = Math.min(3, active.size());
            for (int i = 0; i < limit; i++) {
                SymptomSnapshot snapshot = active.get(i);
                builder.append('[')
                        .append(snapshot.symptom.description)
                        .append(" — ")
                        .append(snapshot.symptom.severity)
                        .append(" x")
                        .append(snapshot.symptom.count)
                        .append(']');
                if (i < limit - 1) {
                    builder.append(", ");
                }
            }
            DebugGuardian.LOGGER.warn(builder.toString());
            lastAlert = now;
        }
    }

    private static double decay(long ageMs) {
        double ratio = 1.0 - (double) ageMs / SYMPTOM_TTL_MS;
        return Math.max(0.0, ratio);
    }

    private static final class Symptom {
        private Severity severity;
        private String description;
        private long timestamp;
        private int count;

        private Symptom(Severity severity, String description, long timestamp) {
            this.severity = severity;
            this.description = description;
            this.timestamp = timestamp;
            this.count = 1;
        }

        private void refresh(Severity newSeverity, String newDescription, long newTimestamp) {
            if (newSeverity.weight >= this.severity.weight) {
                this.severity = newSeverity;
                this.description = newDescription;
            }
            this.timestamp = newTimestamp;
            this.count++;
        }

        private double weight() {
            return severity.weight * Math.log10(1 + count);
        }
    }

    private record SymptomSnapshot(String key, Symptom symptom, double weight) {
    }

    /**
     * Discrete severity levels used by the crash risk aggregator.
     */
    public enum Severity {
        LOW(1.0),
        MEDIUM(2.0),
        HIGH(3.0),
        CRITICAL(4.0);

        private final double weight;

        Severity(double weight) {
            this.weight = weight;
        }
    }
}
