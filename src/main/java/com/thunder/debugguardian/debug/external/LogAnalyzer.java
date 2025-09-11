package com.thunder.debugguardian.debug.external;

import java.util.List;

/**
 * Analyzes thread reports and produces a human-readable explanation of a crash
 * or hang. Implementations may rely on simple heuristics or delegate to an
 * external AI service such as {@link AiLogAnalyzer}, which reads its API key
 * from the {@code DEBUG_GUARDIAN_AI_KEY} environment variable.
 */
public interface LogAnalyzer {
    /**
     * Generate an explanation for the given thread reports.
     *
     * @param threads parsed thread information
     * @return explanation of the possible cause
     */
    String analyze(List<ThreadReport> threads);
}
