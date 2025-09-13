package com.thunder.debugguardian.debug.external;

import java.util.List;

/**
 * Represents information about a single thread extracted from a dump file,
 * including the associated mod, current thread state, and full stack trace.
 */
public record ThreadReport(String thread, String mod, String state, List<String> stack) {}
