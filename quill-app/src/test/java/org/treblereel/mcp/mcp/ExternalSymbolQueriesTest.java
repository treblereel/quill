package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ExternalSymbolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;
    private Jdbi jdbi;

    @BeforeEach
    void createIndex() {
        jdbi = QuillDatabase.create(temp.resolve("index-" + System.nanoTime() + ".db"));
        jdbi.useHandle(handle -> {
            int dependency = handle.createUpdate("""
                    INSERT INTO classes(class_name, kind, superclass, interfaces, source_line,
                                        origin, lifecycle)
                    VALUES ('com.fasterxml.jackson.databind.ObjectMapper', 'CLASS',
                            'java.lang.Object', '[]', 0, 'dependency', 'current')
                    """).executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one();
            handle.createUpdate("""
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                                              type_name, parameter_types, modifiers, annotations)
                    VALUES (:id, 'METHOD', 'readTree',
                            'readTree(java.lang.String):com.fasterxml.jackson.databind.JsonNode',
                            '(Ljava/lang/String;)Lcom/fasterxml/jackson/databind/JsonNode;',
                            'com.fasterxml.jackson.databind.JsonNode',
                            '["java.lang.String"]', 'public', '[]')
                    """).bind("id", dependency).execute();
            handle.createUpdate("""
                    INSERT INTO classes(class_name, kind, interfaces, source_line, origin, lifecycle)
                    VALUES ('acme.ObjectMapper', 'CLASS', '[]', 1, 'source', 'current')
                    """).execute();
        });
    }

    @Test
    void searchesOnlyDependencySymbols() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .search(jdbi, "ObjectMapper", "class", "com.fasterxml*", 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals("com.fasterxml.jackson.databind.ObjectMapper",
                result.path("symbols").get(0).path("declaring_class").asText());
        assertEquals("com.fasterxml.jackson",
                result.path("symbols").get(0).path("library_package").asText());
    }

    @Test
    void returnsPagedExternalClassMembers() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .details(jdbi, "ObjectMapper", 1, 0));

        assertEquals("com.fasterxml.jackson.databind.ObjectMapper",
                result.path("class_name").asText());
        assertEquals("readTree", result.path("members").get(0).path("name").asText());
        assertFalse(result.path("source_available").asBoolean());
    }
}
