package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class PositionSymbolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    @Test
    void resolvesIdentifierAtLiveSourcePosition() throws Exception {
        Path source = temp.resolve("src/main/java/acme/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package acme;
                class OrderService {
                  void submit() { submit(); }
                }
                """);
        Jdbi jdbi = QuillDatabase.create(temp.resolve("index.db"));
        jdbi.useHandle(handle -> {
            int file = handle.createUpdate("""
                    INSERT INTO files(project_path, repository_path, kind, origin, lifecycle)
                    VALUES (:path, :path, 'java', 'source', 'current')
                    """).bind("path", "src/main/java/acme/OrderService.java")
                    .executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one();
            int cls = handle.createUpdate("""
                    INSERT INTO classes(class_name, kind, interfaces, source_file, source_line,
                                        file_id, origin, lifecycle)
                    VALUES ('acme.OrderService', 'CLASS', '[]', :path, 2,
                            :file, 'source', 'current')
                    """).bind("path", "src/main/java/acme/OrderService.java")
                    .bind("file", file).executeAndReturnGeneratedKeys("id")
                    .mapTo(Integer.class).one();
            handle.createUpdate("""
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                                              type_name, parameter_types, modifiers, annotations)
                    VALUES (:class, 'METHOD', 'submit', 'void submit()', '()V',
                            'void', '[]', '', '[]')
                    """).bind("class", cls).execute();
        });

        var result = JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(
                jdbi, temp, "src/main/java/acme/OrderService.java", 3, 9));

        assertEquals("submit", result.path("identifier").asText());
        assertEquals("resolved", result.path("resolution").asText());
        assertEquals("METHOD", result.path("candidates").get(0).path("kind").asText());
        assertEquals("acme.OrderService",
                result.path("enclosing_class").path("class_name").asText());
    }
}
