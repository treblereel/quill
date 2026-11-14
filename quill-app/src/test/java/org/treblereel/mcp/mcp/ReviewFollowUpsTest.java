package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewFollowUpsTest {

    static final class RouterLike {
        @Tool(readOnly = true, description = "Echo the routed tool name and argument count.")
        public String execute_tool(
                @ToolArg(description = "Tool") String name,
                @ToolArg(description = "Arguments") Map<String, Object> arguments) {
            return name + ":" + arguments.size();
        }

        @Tool(readOnly = true, description = "Echo the search query.")
        public String search_tools(@ToolArg(description = "Words") String query) {
            return "query:" + query;
        }
    }

    @Test
    void executeToolAcceptsRecordedArgumentSpellings() throws Exception {
        var method = RouterLike.class.getMethod("execute_tool", String.class, Map.class);
        var invocation = McpToolCatalog.invokeMethod(new RouterLike(), method,
                Map.of("tool_name", "get_overview", "args", Map.of("details", true)));

        assertFalse(invocation.error(), invocation.text());
        assertEquals("get_overview:1", invocation.text());
    }

    @Test
    void canonicalArgumentWinsOverAlias() throws Exception {
        var method = RouterLike.class.getMethod("execute_tool", String.class, Map.class);
        var invocation = McpToolCatalog.invokeMethod(new RouterLike(), method,
                Map.of("name", "list_beans", "tool_name", "ignored", "arguments", Map.of()));

        assertEquals("list_beans:0", invocation.text());
    }

    /** V62-V65: Codex sent search_tools {"name": "find_method_overrides"} four times and got Unknown argument: name. */
    @Test
    void searchToolsTakesAToolNameAsItsQuery() throws Exception {
        var method = RouterLike.class.getMethod("search_tools", String.class);
        assertEquals("query:analyze_execution_order",
                McpToolCatalog.invokeMethod(new RouterLike(), method, Map.of("name", "analyze_execution_order")).text());
    }

    @Test
    void unknownArgumentListsAcceptedArguments() throws Exception {
        var method = RouterLike.class.getMethod("execute_tool", String.class, Map.class);
        var invocation = McpToolCatalog.invokeMethod(new RouterLike(), method,
                Map.of("name", "x", "arguments", Map.of(), "surprise", 1));

        assertTrue(invocation.error());
        assertEquals("Unknown argument: surprise. Accepted arguments: arguments, name", invocation.text());
    }

    @Test
    void auxiliaryBuildProjectsExcludeAggregatorsAndCollapseSubprojects() {
        List<String> modules = List.of(".", "app", "tests/basic", "tests/multi/common");
        List<String> descriptors = List.of("pom.xml", "app/pom.xml", "tests/pom.xml",
                "tests/basic/pom.xml", "tests/multi/pom.xml", "tests/multi/common/pom.xml",
                "tools/generator/pom.xml", "tests/gradle/build.gradle.kts",
                "tests/gradle/sub/build.gradle.kts", "app/src/test/resources/fixture/pom.xml",
                "app/target/it/pom.xml");

        assertEquals(List.of("tests/gradle", "tools/generator"),
                ProjectOverviewQueries.auxiliaryBuildProjects(modules, descriptors));
    }

    @Test
    void nextStepIsDroppedWhenTheProfileDoesNotExposeTheTool() {
        String response = "{\"error_code\":\"CLASS_NOT_FOUND\",\"next_step\":"
                + "{\"tool\":\"get_external_symbol_details\",\"arguments\":{\"class_name\":\"a.B\"}}}";

        assertFalse(McpToolCatalog.withoutUnavailableNextStep(response, McpToolProfile.parse("core"))
                .contains("next_step"));
        assertEquals(response,
                McpToolCatalog.withoutUnavailableNextStep(response, McpToolProfile.full()));
    }

    @Test
    void unindexedAnnotationIsAnEmptyResultNotAnError() {
        var response = AnnotatedSymbolQueries.unindexedAnnotation("jakarta.interceptor.Interceptor", "symbols");

        assertFalse(response.has("error"));
        assertFalse(response.has("error_code"));
        assertFalse(response.path("annotation_indexed").asBoolean(true));
        assertEquals(0, response.path("total").asInt(-1));
        assertTrue(response.path("symbols").isArray());
    }
}
