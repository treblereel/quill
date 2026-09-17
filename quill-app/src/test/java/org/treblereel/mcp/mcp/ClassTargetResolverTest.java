package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.ClassRecord;

class ClassTargetResolverTest {

    @TempDir Path tempDir;

    @Test
    void resolvesSingleExactShortNameAndIgnoresHistoricalMatches() {
        Jdbi jdbi = database(List.of(
                current("io.crysknife.client.BeanManager"),
                historical("legacy.BeanManager")));

        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, "BeanManager");

        assertTrue(lookup.found());
        assertEquals("io.crysknife.client.BeanManager", lookup.cls().className());
    }

    @Test
    void reportsAmbiguousTargetWhenMultipleCurrentClassesHaveSameShortName() {
        Jdbi jdbi = database(List.of(
                current("first.BeanManager"),
                current("second.BeanManager")));

        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, "BeanManager");

        assertFalse(lookup.found());
        assertEquals("Ambiguous class name", lookup.error());
        assertEquals(List.of("first.BeanManager", "second.BeanManager"),
                lookup.candidates().stream().map(ClassRecord::className).toList());
    }

    @Test
    void exactFqcnWinsBeforeShortNameResolution() {
        Jdbi jdbi = database(List.of(
                current("first.BeanManager"),
                current("second.BeanManager")));

        ClassTargetResolver.Lookup lookup =
                ClassTargetResolver.resolve(jdbi, "second.BeanManager");

        assertTrue(lookup.found());
        assertEquals("second.BeanManager", lookup.cls().className());
    }

    private Jdbi database(List<ClassRecord> classes) {
        Jdbi jdbi = QuillDatabase.create(tempDir.resolve("index-" + System.nanoTime() + ".db"));
        IndexWriter.write(jdbi, classes, List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-16T00:00:00Z"));
        return jdbi;
    }

    private static ClassRecord current(String name) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                "src/main/java/" + name.replace('.', '/') + ".java", 1, false, 10);
    }

    private static ClassRecord historical(String name) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                "src/main/java/" + name.replace('.', '/') + ".java", 1, false, 10,
                null, "source", "historical");
    }
}
