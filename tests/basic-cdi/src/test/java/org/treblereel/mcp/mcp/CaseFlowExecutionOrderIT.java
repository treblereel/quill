package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class CaseFlowExecutionOrderIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static Jdbi jdbi;

    @BeforeAll
    static void indexFixture() {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        jdbi = QuillDatabase.open(ProjectIndexStore.findDbForHead(PROJECT_ROOT));
    }

    @AfterAll
    static void cleanup() throws Exception {
        Path quill = PROJECT_ROOT.resolve(".quill");
        if (!Files.exists(quill)) return;
        try (var walk = Files.walk(quill)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void provesDurabilityBeforeDispatchAndReportsAsyncRecovery() throws Exception {
        QuillToolQueries queries = new QuillToolQueries();
        JsonNode order = JSON.readTree(queries.analyzeExecutionOrder(jdbi,
                "CaseFlowEngine", "execute", null));

        assertEquals("invocation_order_proven_completion_unknown",
                order.path("ordering_analysis").path("runtime_order_status").asText());
        assertTrue(order.path("control_flow").path("dominance_proven").asBoolean());
        assertEquals(1, order.path("async_semantics").path("phases")
                .path("scheduling").asInt());
        assertTrue(order.path("bytecode_sequence").valueStream().anyMatch(event ->
                event.path("external").asBoolean()
                        && event.path("callee_class").asText().contains("Executor")));

        JsonNode lifecycle = JSON.readTree(
                queries.traceStateLifecycle(jdbi, "CaseFlowState", 50));
        assertTrue(lifecycle.path("inferred_roles").valueStream().anyMatch(role ->
                role.asText().equals("durable_execution_state_candidate")));
        assertTrue(lifecycle.path("inferred_roles").valueStream().anyMatch(role ->
                role.asText().equals("recovery_state_candidate")));
    }
}
