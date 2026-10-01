package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void errorsUseStableMachineReadableEnvelope() throws Exception {
        Path source = temp.resolve("Empty.kt");
        Files.writeString(source, "val value = 1\n");
        Jdbi jdbi = QuillDatabase.create(temp.resolve("error-index.db"));

        var result = JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(
                jdbi, temp, "Empty.kt", 2, 1));

        assertFalse(result.has("error"));
        assertEquals("INVALID_POSITION", result.path("error_code").asText());
        assertEquals("Line is outside the source file", result.path("message").asText());
    }

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

    @Test
    void prefersMatchingConstructorInNewExpression() throws Exception {
        Path source = temp.resolve("src/main/java/acme/Factory.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package acme;
                class Factory {
                  Object create() { return new BeanProcessorTask("x", 1); }
                }
                """);
        Jdbi jdbi = QuillDatabase.create(temp.resolve("constructor.db"));
        jdbi.useHandle(handle -> {
            int task = handle.createUpdate("""
                    INSERT INTO classes(class_name, kind, interfaces, source_file, source_line,
                                        origin, lifecycle)
                    VALUES ('acme.BeanProcessorTask', 'CLASS', '[]',
                            'src/main/java/acme/BeanProcessorTask.java', 1, 'source', 'current')
                    """).executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one();
            handle.createUpdate("""
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                                              type_name, parameter_types, modifiers, annotations)
                    VALUES (:id, 'CONSTRUCTOR', 'BeanProcessorTask',
                            'BeanProcessorTask(java.lang.String,int)',
                            '(Ljava/lang/String;I)V', 'acme.BeanProcessorTask',
                            '["java.lang.String","int"]', 'public', '[]')
                    """).bind("id", task).execute();
        });

        var result = JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(
                jdbi, temp, "src/main/java/acme/Factory.java", 3, 34));

        assertEquals("constructor_call", result.path("context").asText());
        assertEquals(2, result.path("argument_count").asInt());
        assertEquals("resolved", result.path("resolution").asText());
        assertEquals("CONSTRUCTOR", result.path("selected").path("kind").asText());
        assertEquals(2, result.path("selected").path("parameter_count").asInt());
    }

    @Test
    void resolvesKotlinSourceNameWhenJvmNameDiffers() throws Exception {
        Path source = temp.resolve("src/main/kotlin/acme/Orders.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package acme
                fun submitOrder() = Unit
                """);
        Jdbi jdbi = QuillDatabase.create(temp.resolve("kotlin-position.db"));
        jdbi.useHandle(handle -> {
            int file = handle.createUpdate("""
                    INSERT INTO files(project_path, repository_path, kind, origin, lifecycle)
                    VALUES (:path, :path, 'kotlin', 'source', 'current')
                    """).bind("path", "src/main/kotlin/acme/Orders.kt")
                    .executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one();
            int cls = handle.createUpdate("""
                    INSERT INTO classes(class_name, kind, interfaces, source_file, source_line,
                                        file_id, origin, lifecycle)
                    VALUES ('acme.OrdersKt', 'CLASS', '[]', :path, 1,
                            :file, 'source', 'current')
                    """).bind("path", "src/main/kotlin/acme/Orders.kt")
                    .bind("file", file).executeAndReturnGeneratedKeys("id")
                    .mapTo(Integer.class).one();
            handle.createUpdate("""
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                                              type_name, parameter_types, modifiers, annotations)
                    VALUES (:class, 'METHOD', 'submitOrderJvm', 'void submitOrderJvm()', '()V',
                            'void', '[]', 'public static', '[]')
                    """).bind("class", cls).execute();
            handle.createUpdate("""
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model)
                    VALUES (:class, 'FUNCTION', 'submitOrder', 'submitOrderJvm', '()V',
                            'kotlin_metadata')
                    """).bind("class", cls).execute();
        });

        var result = JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(
                jdbi, temp, "src/main/kotlin/acme/Orders.kt", 2, 7));

        assertEquals("submitOrder", result.path("identifier").asText());
        assertEquals("resolved", result.path("resolution").asText());
        assertEquals("submitOrderJvm", result.path("selected").path("member_name").asText());
        assertEquals("kotlin", result.path("selected").path("language").asText());
        assertEquals("submitOrder", result.path("selected").path("source_name").asText());
        assertEquals("submitOrderJvm", result.path("selected").path("jvm_name").asText());
        assertEquals("()V", result.path("selected").path("jvm_descriptor").asText());
        assertTrue(result.path("selected").path("symbol_id").asText()
                .startsWith("quill:symbol:v2:"));
        assertEquals("src/main/kotlin/acme/Orders.kt",
                result.path("selected").path("location").path("path").asText());
        assertEquals("submitOrder", result.path("selected").path("kotlin_name").asText());
    }
}
