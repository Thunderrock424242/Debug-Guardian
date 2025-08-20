package com.thunder.debugguardian.debug.monitor;

import com.thunder.debugguardian.DebugGuardian;

/**
 * Installs a global uncaught exception handler early during startup so
 * crashes during mod loading are recorded to the live log.
 */
public class StartupFailureReporter implements Thread.UncaughtExceptionHandler {
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(new StartupFailureReporter());
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        LiveLogMonitor.captureThrowable(e);
        String culprit = ClassLoadingIssueDetector.identifyCulpritMod(e);
        if (!"Unknown".equals(culprit)) {
            DebugGuardian.LOGGER.error("Startup crash likely caused by mod {}", culprit);
        }
    }
}
