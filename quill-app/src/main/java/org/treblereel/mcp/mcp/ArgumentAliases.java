package org.treblereel.mcp.mcp;

import java.util.Locale;

/** Spellings agents send for enumerated arguments; recorded agents repeated a rejected call unchanged. */
final class ArgumentAliases {

    private ArgumentAliases() {
    }

    /** inbound, outbound or both; anything else comes back lower-cased for the caller to reject. */
    static String graphDirection(String direction) {
        if (direction == null) return null;
        String value = direction.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "incoming", "in", "dependents", "callers", "upstream" -> "inbound";
            case "outgoing", "out", "dependencies", "callees", "downstream" -> "outbound";
            case "all" -> "both";
            default -> value;
        };
    }

    /** all, application or dependency; anything else comes back lower-cased for the caller to reject. */
    static String origin(String origin) {
        if (origin == null) return null;
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "source", "project", "app", "local" -> "application";
            case "library", "libraries", "external", "jar", "dependencies" -> "dependency";
            default -> value;
        };
    }
}
