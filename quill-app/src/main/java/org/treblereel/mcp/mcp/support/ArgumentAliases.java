package org.treblereel.mcp.mcp.support;

import java.util.Locale;

/** Spellings agents send for enumerated arguments; recorded agents repeated a rejected call unchanged. */
public final class ArgumentAliases {

    private ArgumentAliases() {
    }

    /** inbound, outbound or both; anything else comes back lower-cased for the caller to reject. */
    public static String graphDirection(String direction) {
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
    public static String origin(String origin) {
        if (origin == null) return null;
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "source", "project", "app", "local" -> "application";
            case "library", "libraries", "external", "jar", "dependencies" -> "dependency";
            default -> value;
        };
    }

    /** index or declared; V62-V65 Codex asked for the detailed view as full or details. */
    public static String configurationView(String view) {
        if (view == null) return null;
        String value = view.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "full", "detail", "details", "detailed", "value", "values", "source", "sources", "declarations" -> "declared";
            case "summary", "references", "refs", "usages" -> "index";
            default -> value;
        };
    }
}
