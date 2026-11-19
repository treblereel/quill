package org.treblereel.mcp.diagnostics;

import java.util.Locale;

/**
 * Quill's diagnostic log. Stdout carries the MCP protocol, so every line goes to the current
 * {@link System#err}, looked up per call so a replaced stream receives it, and keeps the
 * {@code [quill]} prefix scripts and tests read.
 *
 * <p>The level comes from {@code QUILL_LOG_LEVEL} (debug, info, warn or off; info by default);
 * {@code QUILL_DEBUG} or {@code --debug} also turn debug on. Command output meant for the user
 * of a CLI command is not logging and is printed by the command itself.
 */
public final class QuillLog {

    public enum Level { DEBUG, INFO, WARN, OFF }

    private static final StackWalker CALLER = StackWalker.getInstance();
    private static volatile Level threshold = initialLevel();

    private QuillLog() {}

    /** Progress and outcomes an operator expects to see. */
    public static void info(String message) {
        if (enabled(Level.INFO)) System.err.println("[quill] " + message);
    }

    /** A degraded result the operator should know about; the message follows "Warning: ". */
    public static void warn(String message) {
        if (enabled(Level.WARN)) System.err.println("[quill] Warning: " + message);
    }

    /** Detail useful only while investigating. */
    public static void debug(String message) {
        if (enabled(Level.DEBUG)) System.err.println("[quill] debug: " + message);
    }

    /**
     * Records an exception a best-effort path caught and replaced with a fallback answer, so a
     * result that degraded to unknown or unavailable can be traced at debug level. The catch
     * site is found from the stack, so callers pass only the exception.
     */
    public static void swallowed(Throwable failure) {
        if (!enabled(Level.DEBUG)) return;
        String site = CALLER.walk(frames -> frames.skip(1).findFirst()
                .map(frame -> simpleName(frame.getClassName()) + "." + frame.getMethodName()
                        + ":" + frame.getLineNumber())
                .orElse("unknown"));
        String message = failure.getMessage();
        System.err.println("[quill] debug: " + site + " fell back after "
                + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message.strip()));
    }

    public static boolean enabled(Level level) {
        Level current = threshold;
        return current != Level.OFF && level.ordinal() >= current.ordinal();
    }

    /** Turns debug output on for this process, as {@code --debug} does. */
    public static void enableDebug() {
        threshold = Level.DEBUG;
    }

    static void setLevel(Level level) {
        threshold = level;
    }

    static Level parse(String level, String debug) {
        if (debug != null && (debug.equals("1") || debug.equalsIgnoreCase("true")
                || debug.equalsIgnoreCase("yes"))) return Level.DEBUG;
        if (level == null || level.isBlank()) return Level.INFO;
        try {
            return Level.valueOf(level.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Level.INFO;
        }
    }

    private static Level initialLevel() {
        return parse(System.getenv("QUILL_LOG_LEVEL"), System.getenv("QUILL_DEBUG"));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
