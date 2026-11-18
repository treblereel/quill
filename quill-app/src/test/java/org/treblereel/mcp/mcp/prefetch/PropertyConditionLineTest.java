package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-035: a bean method's condition read
 * {@code @ConditionalOnProperty({$defaults:[...],prefix:a.b,value:[enabled]})}; the property name
 * was left for the reader to assemble, and answers named it in one run of three.
 */
class PropertyConditionLineTest {
    private static final String CONDITION =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    @TempDir Path root;

    @Test
    void aPropertyConditionNamesTheFullPropertyAndWhenItMatches() throws Exception {
        Jdbi db =
                conditioned(
                        "{\"$defaults\":[\"havingValue\",\"matchIfMissing\",\"name\"],\"havingValue\":\"\","
                                + "\"matchIfMissing\":false,\"name\":[],\"prefix\":\"shop.registry\",\"value\":[\"enabled\"]}");
        assertEquals(
                List.of("@ConditionalOnProperty(shop.registry.enabled present and not false)"),
                conditions(db));
    }

    @Test
    void aRequiredValueAndAMatchWhenMissingAreStated() throws Exception {
        Jdbi db =
                conditioned(
                        "{\"havingValue\":\"db\",\"matchIfMissing\":true,\"name\":[\"store.kind\",\"store.mode\"],"
                                + "\"prefix\":\"\",\"value\":[]}");
        assertEquals(
                List.of("@ConditionalOnProperty(store.kind, store.mode = db, or missing)"),
                conditions(db));
    }

    private Jdbi conditioned(String values) throws Exception {
        Jdbi db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Registry {} class RegistryConfig { Registry registry() { return new Registry(); } }");
        db.useHandle(
                h ->
                        h.execute(
                                "INSERT INTO class_annotations(class_id, annotation_name, direct, annotation_values) "
                                        + "SELECT id, ?, 1, ? FROM classes WHERE class_name = 'p.RegistryConfig'",
                                CONDITION,
                                values));
        return db;
    }

    private static List<String> conditions(Jdbi db) throws Exception {
        return TestReads.read(
                db,
                reader ->
                        MethodPrefetchRelations.registrationConditions(
                                reader,
                                reader.symbols
                                        .findClassByName("p.RegistryConfig")
                                        .orElseThrow()
                                        .id(),
                                null));
    }
}
