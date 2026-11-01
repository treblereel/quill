package org.treblereel.mcp.mcp;

import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.ReadQueryContext;

/** {@link ReadQueryContext#readWithGraph} for test bodies whose query declares checked exceptions. */
final class TestReads {
    interface Work<T> { T apply(ReadQueryContext reader) throws Exception; }

    private TestReads() {}

    static <T> T read(Jdbi database, Work<T> work) throws Exception {
        try (ReadQueryContext reader = ReadQueryContext.openWithGraph(database)) {
            return work.apply(reader);
        }
    }
}
