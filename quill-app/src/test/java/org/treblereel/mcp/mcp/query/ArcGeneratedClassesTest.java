package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.mcp.TestReads;

/** V41 CQ-020/CQ-048: get_symbol_details(SsarClientCache_Bean) and get_external_symbol_details(PermissionMapper_ClientProxy) were plain misses. */
class ArcGeneratedClassesTest {
    @TempDir Path root;
    Jdbi db;

    @BeforeEach void setup() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(new ClassRecord(0, "x.Mapper", "CLASS", "java.lang.Object", List.of(), "Mapper.java", 1, true, 10)))
                .beans(List.of(new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default", "@Any"), List.of(), false, false,
                        null, null, null, null, List.of("x.Mapper"))))
                .metadata(Map.of("index_id", "arc", "project_root", root.toString())).snapshot());
    }

    private JsonNode read(java.util.function.Function<org.treblereel.mcp.db.ReadQueryContext, String> query) throws Exception {
        String json = TestReads.read(db, query::apply);
        return new ObjectMapper().readTree(json);
    }

    @Test void aClientProxyIsExplainedThroughItsBean() throws Exception {
        JsonNode external = read(reader -> new ExternalSymbolQueries().details(reader, root, "x.Mapper_ClientProxy", 10, 0, "bytecode"));
        assertFalse(external.has("error_code"), external.toString());
        assertEquals("x.Mapper", external.path("generated_for").asText(), external.toString());
        assertEquals("client_proxy", external.path("generated_role").asText());
        assertEquals("@ApplicationScoped", external.path("bean").path("scope").asText(), external.toString());
        assertEquals("get_symbol_details", external.path("next_step").path("tool").asText());
    }

    @Test void aGeneratedBeanClassIsExplainedBySymbolDetails() throws Exception {
        JsonNode symbol = read(reader -> new SymbolToolQueries().getSymbolDetails(reader, "x.Mapper_Bean", true, null, 50, 0));
        assertFalse(symbol.has("error_code"), symbol.toString());
        assertEquals("injectable_bean", symbol.path("generated_role").asText(), symbol.toString());
        assertTrue(symbol.path("generated_by").asText().contains("ArC"));
    }

    @Test void anUnrelatedSuffixStaysAMiss() throws Exception {
        assertTrue(read(reader -> new SymbolToolQueries().getSymbolDetails(reader, "x.Other_Bean", true, null, 50, 0)).has("error_code"));
    }
}
