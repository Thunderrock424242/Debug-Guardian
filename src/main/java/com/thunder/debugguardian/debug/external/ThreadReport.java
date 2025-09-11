package com.thunder.debugguardian.debug.external;

import java.util.List;

/**
 * Represents information about a single thread extracted from a dump file.
 */
public record ThreadReport(String thread, String mod, List<String> stack) {}
