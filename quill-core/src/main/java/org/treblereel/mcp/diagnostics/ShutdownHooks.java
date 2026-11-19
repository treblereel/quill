package org.treblereel.mcp.diagnostics;

/** Registers cleanup from static initializers, which can run for the first time during shutdown. */
public final class ShutdownHooks {

    private ShutdownHooks() {}

    /** Adds the hook unless the JVM is already exiting, when the explicit close path is what runs. */
    public static void add(Runnable hook, String name) {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(hook, name));
        } catch (IllegalStateException shutdownInProgress) {
            // Failing here would break the class for good and hide the real exit status.
        }
    }
}
