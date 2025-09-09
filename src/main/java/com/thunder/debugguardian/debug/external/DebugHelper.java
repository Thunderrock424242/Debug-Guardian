package com.thunder.debugguardian.debug.external;

/**
 * Simple helper application launched in a separate JVM when debug mode is
 * enabled. It currently acts as a placeholder where advanced diagnostic logic
 * could be implemented.
 */
public class DebugHelper {
    public static void main(String[] args) throws Exception {
        System.out.println("Debug helper process running");
        // Keep the process alive so tooling can attach if desired.
        Thread.sleep(Long.MAX_VALUE);
    }
}
