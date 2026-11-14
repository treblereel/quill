package org.treblereel.mcp.db;

/**
 * One source for how many reads can reach a single index generation at once, so the MCP request
 * limit and the generation's reader pool cannot drift apart.
 */
public final class ReadConcurrency {
    public static final int DEFAULT_REQUESTS = 4;
    /** The Java and Kotlin semantic warmups each hold a reader alongside requests. */
    private static final int BACKGROUND_READERS = 2;

    private ReadConcurrency() {}

    /** Tool requests the server runs at once ({@code QUILL_MCP_MAX_CONCURRENCY}). */
    public static int requests() {
        return positive(System.getenv("QUILL_MCP_MAX_CONCURRENCY"), DEFAULT_REQUESTS);
    }

    /**
     * Readers a generation may lend at once: every request may hold its own handle and a second one
     * for {@link ReadQueryContext#independentRead}, all aimed at the same project. Readers open lazily,
     * so the bound costs nothing until it is reached.
     */
    public static int readersPerGeneration() {
        return readersFor(requests());
    }

    static int readersFor(int requests) {
        return 2 * requests + BACKGROUND_READERS;
    }

    static int positive(String configured, int fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(configured.strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static long positiveLong(String configured, long fallback) {
        if (configured == null || configured.isBlank()) return fallback;
        try {
            long value = Long.parseLong(configured.strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
