package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;

class NarrowEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void defaultCallHierarchyShrinksEvidenceBeforeBudgetWithoutDroppingEdgesOrReasons() throws Exception {
        try (var registry = new ProjectRegistry()) {
            var full = response();
            String tools = full.toString();
            var result = call(tools, "get_call_hierarchy", Map.of("target", "sample.Target", "method", "resume", "limit", 20));
            assertTrue(JSON.writeValueAsBytes(full).length > ResponseBudget.DEFAULT_MAX_BYTES);
            assertTrue(JSON.writeValueAsBytes(result).length < 8_000);
            assertFalse(result.has("_response_budget"));
            assertEquals(full.path("calls"), result.path("calls"));
            for (String field : List.of("showing", "total", "answer_complete", "unused_proven", "_meta"))
                assertEquals(full.path(field), result.path(field));
            var coverage = result.path("coverage");
            assertFalse(coverage.has("indexed_scopes"));
            assertFalse(coverage.has("missing_test_output_modules"));
            assertEquals(556, coverage.path("indexed_scope_count").asInt());
            assertEquals(2, coverage.path("missing_test_output_module_count").asInt());
            assertEquals(200, coverage.at("/compiled_outputs/stale_module_count").asInt());
            for (String field : List.of("incomplete_reasons", "indexed_scope_verified", "snapshot_freshness", "scan_complete", "page_complete"))
                assertEquals(full.path("coverage").path(field), coverage.path(field));
            var warning = result.at("/project_warnings/0");
            for (String field : List.of("status", "reason", "action", "retry_after_ms", "previous_generation_available", "blocking_for_change"))
                assertEquals(full.at("/project_warnings/0").path(field), warning.path(field));
            assertFalse(warning.has("verification_plan"));
            assertFalse(warning.has("diagnostics"));
            assertFalse(warning.has("stale_modules"));
            assertEquals(200, warning.path("stale_modules_count").asInt());
            assertEquals("failed", warning.at("/diagnostics_summary/build_status").asText());
            assertEquals(2, warning.at("/diagnostics_summary/total").asInt());
            assertEquals("get_build_status", warning.at("/details_request/tool").asText());
            var request = coverage.path("details_request");
            assertEquals("get_call_hierarchy", request.path("tool").asText());
            assertEquals("resume", request.at("/arguments/method").asText());
            assertEquals(20, request.at("/arguments/limit").asInt());
            assertEquals("full", request.at("/arguments/evidence_detail").asText());
        }
    }

    @Test void fullEvidencePagesAllScopesAndPreservesWarningsAndQueryWindow() throws Exception {
        try (var registry = new ProjectRegistry()) {
            var original = response(); String tools = original.toString();
            Map<String, Object> args = new LinkedHashMap<>(Map.of("target", "sample.Target", "method", "resume", "offset", 7, "evidence_detail", "full"));
            int rows = 0;
            for (int offset = 0; offset < 600; offset += 50) {
                args.put("evidence_offset", offset);
                var result = call(tools, "get_call_hierarchy", args);
                assertFalse(result.has("_response_budget"));
                assertEquals(original.path("calls"), result.path("calls"));
                assertEquals(original.path("project_warnings"), result.path("project_warnings"));
                var scopes = result.at("/coverage/indexed_scopes"); rows += scopes.size();
                assertEquals(Math.min(50, 556 - offset), scopes.size());
                assertEquals(original.at("/coverage/indexed_scopes/" + offset), scopes.get(0));
                var page = result.at("/coverage/indexed_scopes_page");
                assertEquals(556, page.path("total").asInt());
                assertEquals(offset, page.path("offset").asInt());
                assertEquals("fixture-index", page.path("index_id").asText());
                if (offset < 550) {
                    assertEquals(offset + 50, page.at("/next_page_request/arguments/evidence_offset").asInt());
                    assertEquals(7, page.at("/next_page_request/arguments/offset").asInt());
                } else assertFalse(page.has("next_page_request"));
            }
            assertEquals(556, rows);
            assertEquals(556, original.at("/coverage/indexed_scopes").size());
            args.put("evidence_offset", Integer.MAX_VALUE);
            assertEquals(0, call(tools, "get_call_hierarchy", args).at("/coverage/indexed_scopes").size());
        }
    }

    @Test void theSamePolicyAppliesToUsagesAndWorkspaceWrappers() throws Exception {
        try (var registry = new ProjectRegistry()) {
            var full = response(); String tools = full.toString();
            assertFalse(call(tools, "find_usages", Map.of("target", "sample.Target")).path("coverage").has("indexed_scopes"));
            var workspace = JSON.createObjectNode();
            for (String project : List.of("one", "two")) workspace.withArray("projects").addObject().put("project", project).set("data", full.deepCopy());
            var projected = JSON.readTree(ToolResponseSupport.queryEvidence(workspace.toString(), "find_usages", Map.of("target", "sample.Target"), false, 0));
            assertEquals("one", projected.at("/projects/0/data/coverage/details_request/arguments/project").asText());
            assertEquals("two", projected.at("/projects/1/data/coverage/details_request/arguments/project").asText());
            assertEquals(full.path("calls"), projected.at("/projects/1/data/calls"));
            assertFalse(projected.at("/projects/1/data/project_warnings/0").has("verification_plan"));
            var workspaceUsages = JSON.readTree(ToolResponseSupport.queryEvidence(workspace.toString(), "find_workspace_usages", Map.of("target", "sample.Target"), false, 0));
            assertFalse(workspaceUsages.at("/projects/0/data/coverage/details_request/arguments").has("project"));
        }
    }

    @Test void evidenceArgumentsAreDiscoverableValidatedAndDoNotAffectWorkflowTools() throws Exception {
        try (var registry = new ProjectRegistry()) {
            var tools = new QuillTools(registry);
            var schema = McpToolCatalog.inputSchema(method("get_call_hierarchy"));
            var properties = (Map<?, ?>) schema.get("properties");
            assertTrue(properties.containsKey("evidence_detail")); assertTrue(properties.containsKey("evidence_offset"));
            for (Object invalid : List.of("verbose", true, 1)) {
                var result = McpToolCatalog.invokeMethod(tools, method("get_call_hierarchy"), Map.of("target", "sample.Target", "evidence_detail", invalid));
                assertTrue(result.error()); assertTrue(result.text().contains("evidence_detail"));
            }
            for (Object invalid : List.of(-1, "100", 0.5, Long.MAX_VALUE))
                assertTrue(McpToolCatalog.invokeMethod(tools, method("get_call_hierarchy"), Map.of("target", "sample.Target", "evidence_offset", invalid)).error());
            assertTrue(McpToolCatalog.invokeMethod(tools, method("get_call_hierarchy"), Map.of("target", "sample.Target", "evidence_offset", 1)).error());
            for (String name : List.of("change_session", "get_build_status", "get_build_problems", "get_file_problems"))
                assertFalse(((Map<?, ?>) McpToolCatalog.inputSchema(method(name)).get("properties")).containsKey("evidence_detail"));
        }
    }

    @Test void warningScopeIsDecidedBeforeSummaryRemovesModuleLists() {
        for (String staleModule : List.of("foreign-module", "own-module")) {
            var root = JSON.createObjectNode().put("detail", "summary");
            root.putObject("verification").putObject("build").putObject("verification_scope")
                    .put("kind", "modules_with_prerequisites").putArray("modules").add("own-module");
            var issue = new ProjectRegistry.ProjectIssue("fixture", java.nio.file.Path.of("/fixture"),
                    "build_required", "maven", "Stale classes", "Compile classes", false,
                    "classes_stale", List.of(staleModule));
            ProjectAvailabilityResponses.append(root, "project_warnings", List.of(issue));
            var warning = root.at("/project_warnings/0");
            assertFalse(warning.has("stale_modules"));
            assertEquals(1, warning.path("stale_modules_count").asInt());
            if (staleModule.equals("foreign-module")) {
                assertEquals("repository_outside_change", warning.path("scope").asText());
                assertFalse(warning.path("blocking_for_change").asBoolean(true));
                assertTrue(warning.path("recommended_action").asText().startsWith("Advisory"));
            } else assertFalse(warning.has("blocking_for_change"));
        }
    }

    @Test void unavailableProjectsKeepTheirFailureAndReadinessWithoutInlinePlans() throws Exception {
        var full = (ObjectNode) response().at("/project_warnings/0").deepCopy();
        full.put("error_code", "BUILD_REQUIRED").put("decision_owner", "mcp_client");
        var result = JSON.readTree(ToolResponseSupport.queryEvidence(full.toString(), "get_call_hierarchy", Map.of("target", "sample.Target"), false, 0));
        assertEquals(full.path("error_code"), result.path("error_code"));
        assertEquals(full.path("reason"), result.path("reason"));
        assertFalse(result.has("verification_plan"));
        assertFalse(result.has("diagnostics"));
        assertEquals(2, result.at("/diagnostics_summary/total").asInt());
        assertEquals("get_build_status", result.at("/details_request/tool").asText());
    }

    @Test void missingCoverageDoesNotBecomeZeroOrComplete() throws Exception {
        var original = JSON.createObjectNode().put("answer_complete", false);
        original.putObject("coverage").putNull("missing_test_output_modules").putArray("incomplete_reasons").add("test_output_coverage_unknown");
        var result = JSON.readTree(ToolResponseSupport.queryEvidence(original.toString(), "find_usages", Map.of("target", "sample.Target"), false, 0));
        assertTrue(result.at("/coverage/missing_test_output_module_count").isNull());
        assertFalse(result.path("answer_complete").asBoolean());
        assertFalse(result.path("coverage").has("indexed_scope_count"));
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

    @Test void catalogAndRouterApplyDefaultAndFullEvidenceToRealBytecodeQueries() throws Exception {
        JavaEvidenceFixture.index(root, """
                package p;
                class Target { void resume() { Helper.first(); Helper.second(); } }
                class Helper { static void first() {} static void second() {} }
                """);
        var quill = java.nio.file.Files.createDirectories(root.resolve(".quill"));
        java.nio.file.Files.copy(root.resolve("index.db"), quill.resolve("fixture.db"));
        java.nio.file.Files.writeString(quill.resolve("refs.json"), "{\"@worktree\":\"fixture\"}");
        try (var registry = new ProjectRegistry()) {
            registry.register(root);
            var tools = new QuillTools(registry);
            Map<String, Object> args = new LinkedHashMap<>(Map.of("target", "p.Target", "method", "resume", "direction", "outbound", "limit", 20));
            var result = McpToolCatalog.invokeMethod(tools, method("get_call_hierarchy"), args);
            assertFalse(result.error(), result::text);
            var summary = JSON.readTree(result.text());
            assertEquals(2, summary.path("calls").size());
            assertFalse(summary.path("coverage").has("indexed_scopes"));
            args.put("evidence_detail", "full");
            var detail = McpToolCatalog.invokeMethod(tools, method("get_call_hierarchy"), args);
            assertFalse(detail.error(), detail::text);
            var full = JSON.readTree(detail.text());
            assertEquals(summary.path("calls").size(), full.path("calls").size());
            assertTrue(full.path("calls").toString().contains("symbol_id"), "Full detail keeps what the summary omits");
            assertFalse(summary.path("calls").toString().contains("symbol_id"));
            assertTrue(full.path("coverage").path("indexed_scopes").isArray());
            assertEquals(full.path("answer_complete"), summary.path("answer_complete"));
            assertEquals(full.path("coverage").path("incomplete_reasons"), summary.path("coverage").path("incomplete_reasons"));
            var router = new RouterTools(tools);
            args.remove("evidence_detail");
            var routed = JSON.readTree(router.execute_tool("get_call_hierarchy", args));
            assertEquals(summary.path("calls"), routed.path("calls"));
            assertFalse(routed.path("coverage").has("indexed_scopes"));
            args.put("evidence_detail", "full");
            assertTrue(JSON.readTree(router.execute_tool("get_call_hierarchy", args)).path("coverage").has("indexed_scopes"));
        }
    }

    private static Method method(String name) {
        return Arrays.stream(QuillTools.class.getDeclaredMethods()).filter(m -> m.getName().equals(name) && m.isAnnotationPresent(Tool.class)).findFirst().orElseThrow();
    }
    private static JsonNode call(String json, String name, Map<String, Object> args) throws Exception {
        boolean full = "full".equals(args.get("evidence_detail"));
        int offset = (Integer) args.getOrDefault("evidence_offset", 0);
        return JSON.readTree(ResponseBudget.apply(ToolResponseSupport.queryEvidence(json, name, args, full, offset)));
    }
    private static ObjectNode response() {
        var root = JSON.createObjectNode().put("showing", 2).put("total", 2).put("answer_complete", false).put("unused_proven", false);
        root.putArray("calls").addObject().put("class", "sample.First"); root.withArray("calls").addObject().put("class", "sample.Second");
        root.putObject("_meta").put("index_id", "fixture-index").put("structure_stale", true);
        var coverage = root.putObject("coverage").put("index_id", "fixture-index").put("indexed_scope_count", 556)
                .put("scope_inventory_truncated", true).put("indexed_scope_verified", false).put("snapshot_freshness", "stale")
                .put("scan_complete", true).put("page_complete", true);
        coverage.putArray("incomplete_reasons").add("stale_index").add("scope_inventory_truncated");
        coverage.putArray("missing_test_output_modules").add("missing-a").add("missing-b");
        var scopes = coverage.putArray("indexed_scopes");
        for (int i = 0; i < 556; i++) scopes.addObject().put("module", "long-project-path/".repeat(30) + i).put("source_set", "main").put("class_count", i);
        var modules = coverage.putObject("compiled_outputs").put("state", "stale").putArray("stale_modules");
        for (int i = 0; i < 200; i++) modules.add("module-" + i);
        var warning = root.putArray("project_warnings").addObject().put("project", "fixture").put("status", "build_required")
                .put("reason", "last_build_failed").put("action", "fix_build").put("retry_after_ms", 30000)
                .put("previous_generation_available", true).put("blocking_for_change", true);
        warning.set("stale_modules", modules.deepCopy());
        warning.putObject("verification_plan").putArray("commands").addObject().putArray("argv").add("mvn").add("test-compile");
        var diagnostics = warning.putObject("diagnostics").put("build_status", "failed").put("total", 2);
        diagnostics.putArray("problems").addObject().put("message", "Some compiler diagnostic").put("file", "sample/Target.java");
        return root;
    }
}
