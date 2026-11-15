package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.mcp.TestReads;

/** V41 CQ-010/CQ-025: list_beans for all application beans was 45 KB, half of it the bean class, Object and @Default @Any on every bean. */
class BeanListCompactionTest {
    @TempDir Path root;

    @Test void everyBeansOwnClassObjectAndDefaultQualifiersAreStatedOnce() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Service", "CLASS", "java.lang.Object", List.of("x.Api"), "Service.java", 1, true, 10, null, "source", "current", ".", "main"),
                        new ClassRecord(0, "x.Producers", "CLASS", "java.lang.Object", List.of(), "Producers.java", 1, true, 10),
                        new ClassRecord(0, "x.Named", "CLASS", "java.lang.Object", List.of(), "Named.java", 1, true, 10),
                        new ClassRecord(0, "x.Typed", "CLASS", "java.lang.Object", List.of("x.Api"), "Typed.java", 1, true, 10)))
                .beans(List.of(
                        new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default", "@Any"), List.of(), false, false,
                                null, null, null, null, List.of("x.Service", "x.Api", "java.lang.Object")),
                        new BeanRecord(0, 2, "PRODUCER_METHOD", "@Dependent", List.of("@Default", "@Any"), List.of(), false, false,
                                null, null, 2, "clock", List.of("java.time.Clock", "java.lang.Object")),
                        new BeanRecord(0, 3, "CLASS", "@Singleton", List.of("@Named", "@Any"), List.of(), false, false,
                                null, null, null, null, List.of("x.Named", "java.lang.Object")),
                        new BeanRecord(0, 4, "CLASS", "@ApplicationScoped", List.of("@Default", "@Any"), List.of(), false, false,
                                null, null, null, null, List.of("x.Api", "java.lang.Object"))))
                .metadata(Map.of("index_id", "beans")).snapshot());

        String json = TestReads.read(db, reader -> new StructureToolQueries()
                .getBeans(reader, null, null, null, null, null, null, null, "application", 50, 0));
        JsonNode result = new ObjectMapper().readTree(json);
        Map<String, JsonNode> beans = new java.util.HashMap<>();
        result.path("beans").forEach(bean -> beans.put(bean.path("class").asText(), bean));

        assertEquals("[\"x.Api\"]", beans.get("x.Service").path("bean_types").toString(), result.toString());
        assertFalse(beans.get("x.Service").has("qualifiers"), result.toString());
        assertEquals("java.time.Clock", beans.get("x.Producers").path("produced_type").asText());
        assertFalse(beans.get("x.Producers").has("bean_types"), result.toString());
        assertEquals("[\"@Named\",\"@Any\"]", beans.get("x.Named").path("qualifiers").toString());
        // @Typed can leave the class out of its bean types; that is stated rather than implied.
        assertEquals(false, beans.get("x.Typed").path("class_is_bean_type").asBoolean(true), result.toString());
        assertEquals("[\"x.Api\"]", beans.get("x.Typed").path("bean_types").toString());
        assertEquals("[\"@Default\",\"@Any\"]", result.path("bean_defaults").path("qualifiers").toString(), result.toString());
        assertEquals("main", result.path("bean_defaults").path("source_set").asText(), result.toString());
        assertFalse(beans.get("x.Service").has("source_set"), result.toString());
        // The retention reasons alone: none listed means removal is not ruled out.
        assertEquals("[]", beans.get("x.Service").path("arc_retention").toString(), result.toString());
    }
}
