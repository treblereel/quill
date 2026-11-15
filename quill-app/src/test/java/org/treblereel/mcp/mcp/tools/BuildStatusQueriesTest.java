package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.mcp.server.McpToolCatalog;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.server.ResponseBudget;

class BuildStatusQueriesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("/project");

    @Test void defaultSummaryKeepsLateFailureCountsAndReasonsWithoutCompilerPayloads() throws Exception {
        var full = evidence(552);
        var summary = present(full, "summary", null, null, 10, 0);
        assertEquals("build_required", summary.path("status").asText());
        assertEquals("classes_stale", summary.path("reason").asText());
        assertEquals(552, summary.at("/compiled_outputs/source_sets_count").asInt());
        assertEquals(551, summary.at("/compiled_outputs/source_sets_status_counts/matching").asInt());
        assertEquals(1, summary.at("/compiled_outputs/source_sets_status_counts/changed").asInt());
        assertEquals(552, summary.path("java_compiler_settings_count").asInt());
        assertEquals(1, summary.path("repository_stale_modules_count").asInt());
        assertFalse(summary.path("compiled_outputs").has("source_sets"));
        assertFalse(summary.has("java_compiler_settings"));
        assertTrue(summary.path("verification_plan_available").asBoolean());
        assertFalse(summary.has("verification_plan"));
        assertEquals(8, summary.at("/diagnostics_summary/total").asInt());
        assertFalse(summary.has("diagnostics"));
        assertTrue(JSON.writeValueAsBytes(summary).length < 4_000);
        assertEquals("full", summary.at("/details_request/arguments/detail").asText());
        assertEquals(552, full.path("java_compiler_settings").size());
    }

    @Test void continuationPagesEveryRowIncludingModulesBeyondOneHundred() throws Exception {
        var full = evidence(552);
        var rows = new ArrayList<JsonNode>();
        var settings = new ArrayList<JsonNode>();
        int offset = 0;
        while (true) {
            var page = present(full, "full", null, null, 37, offset);
            assertEquals(552, page.at("/compiled_outputs/source_sets_count").asInt());
            page.at("/compiled_outputs/source_sets").forEach(rows::add);
            page.path("java_compiler_settings").forEach(settings::add);
            if (!page.path("has_more").asBoolean()) break;
            var args = page.at("/next_page_request/arguments");
            assertEquals("caseflows", args.path("project").asText());
            assertEquals("full", args.path("detail").asText());
            assertTrue(args.path("offset").asInt() > offset);
            offset = args.path("offset").asInt();
        }
        assertEquals(full.at("/compiled_outputs/source_sets"), JSON.valueToTree(rows));
        assertEquals(full.path("java_compiler_settings"), JSON.valueToTree(settings));
    }

    @Test void exactFiltersDoNotHideTheBroaderReadinessDecision() throws Exception {
        var full = evidence(4);
        var summary = present(full, "summary", "module-2", "main", 10, 0);
        assertEquals(1, summary.at("/compiled_outputs/source_sets_count").asInt());
        assertEquals(1, summary.path("java_compiler_settings_count").asInt());
        assertEquals(full.path("status"), summary.path("status"));
        var detail = present(full, "full", "module-2", "main", 10, 0);
        assertEquals("module-2", detail.at("/compiled_outputs/source_sets/0/module").asText());
        assertEquals("/project/module-2", detail.at("/java_compiler_settings/0/module").asText());
        assertEquals("module-2", detail.at("/details_request/arguments/module").asText());
        assertEquals("main", detail.at("/details_request/arguments/source_set").asText());
        assertEquals(0, present(full, "full", "module-2", "test", 10, 0)
                .at("/compiled_outputs/source_sets").size());
    }

    @Test void largeEvidenceShrinksThePageWithoutTrimmingNestedDataOrSkippingRows() throws Exception {
        var full = evidence(6);
        for (var row : full.path("java_compiler_settings"))
            ((ObjectNode) row).putObject("settings").putArray("compileClasspath").add("x".repeat(70_000));
        var page = present(full, "full", null, null, 10, 0);
        int count = page.path("java_compiler_settings").size();
        assertTrue(count > 0 && count < 6);
        assertFalse(page.has("_response_budget"));
        assertEquals(70_000, page.at("/java_compiler_settings/0/settings/compileClasspath/0").asText().length());
        assertEquals(count, page.at("/next_page_request/arguments/offset").asInt());
        assertEquals(count, page.path("limit").asInt());
        assertEquals(6, page.path("java_compiler_settings_count").asInt());
    }

    @Test void actualToolSchemaExposesFiltersAndPagingAndRejectsInvalidRequests() throws Exception {
        var method = QuillTools.class.getMethod("get_build_status", Optional.class, Optional.class,
                Optional.class, Optional.class, Optional.class, Optional.class);
        var schema = McpToolCatalog.inputSchema(method);
        var properties = (Map<?, ?>) schema.get("properties");
        assertEquals(java.util.Set.of("detail", "module", "source_set", "limit", "offset", "project"), properties.keySet());
        assertEquals(List.of("summary", "full"), ((Map<?, ?>) properties.get("detail")).get("enum"));
        for (var args : List.<Map<String, Object>>of(Map.of("detail", "unknown"), Map.of("offset", 1), Map.of("offset", -1))) {
            var result = McpToolCatalog.invokeMethod(new QuillTools(), method, args);
            assertTrue(result.error(), result.text());
        }
    }

    @Test void availabilityWarningsRequestRealFullDetails() {
        var issue = JSON.createObjectNode().put("project", "caseflows");
        assertEquals("full", ProjectAvailabilityResponses.summary(issue).at("/details_request/arguments/detail").asText());
    }

    @Test void transportBudgetOffersOnlySupportedRetryArguments() throws Exception {
        var root = JSON.createObjectNode().put("limit", 10).put("offset", 20);
        var rows = root.putArray("large_evidence");
        for (int i = 0; i < 8; i++) rows.add("x".repeat(70_000));
        var result = JSON.readTree(ResponseBudget.apply(root.toString(), "get_build_status",
                Map.of("detail", "full", "module", "engine/runtime", "source_set", "test", "offset", 20, "limit", 10)));
        var retry = result.at("/_response_budget/retry_request");
        assertEquals("get_build_status", retry.path("tool").asText());
        assertEquals(5, retry.at("/arguments/limit").asInt());
        assertEquals(20, retry.at("/arguments/offset").asInt());
        assertEquals("engine/runtime", retry.at("/arguments/module").asText());
        assertEquals("test", retry.at("/arguments/source_set").asText());
        assertFalse(result.at("/_response_budget/continuation").asText().contains("depth"));
    }

    private static JsonNode present(ObjectNode full, String detail, String module, String sourceSet, int limit, int offset) throws Exception {
        return JSON.readTree(BuildStatusQueries.present(full.toString(), ROOT, "caseflows", detail, module, sourceSet, limit, offset));
    }

    private static ObjectNode evidence(int count) {
        var root = JSON.createObjectNode().put("status", "build_required").put("reason", "classes_stale")
                .put("recommended_action", "Rebuild the stale module").put("build_was_started", false);
        var compiled = root.putObject("compiled_outputs").put("status", "stale").put("count", count);
        var sets = compiled.putArray("source_sets");
        var directories = compiled.putArray("directories");
        var settings = root.putArray("java_compiler_settings");
        for (int i = 0; i < count; i++) {
            sets.addObject().put("module", "module-" + i).put("source_set", "main")
                    .put("status", i == count - 1 ? "changed" : "matching")
                    .putArray("changed_inputs").add("/project/module-" + i + "/src/main/java/App.java");
            directories.add("module-" + i + "/target/classes");
            settings.addObject().put("module", "/project/module-" + i).put("source_set", "main").put("status", "captured");
        }
        root.putArray("repository_stale_modules").add("module-" + (count - 1));
        root.putObject("verification_plan").putArray("commands").addObject().putArray("argv").add("mvn").add("test-compile");
        root.putObject("diagnostics").put("build_status", "failed").put("total", 8)
                .putArray("problems").addObject().put("message", "broken build");
        return root;
    }
}
