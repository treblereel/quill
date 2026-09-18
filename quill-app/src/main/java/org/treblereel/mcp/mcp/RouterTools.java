package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/** Compact MCP entry point that discovers and invokes the full catalog on demand. */
final class RouterTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final QuillTools tools;

    RouterTools(QuillTools tools) {
        this.tools = tools;
    }

    @Tool(structured = true,
            description = "Summarize indexed projects before choosing a specialized tool.")
    public String get_overview(
            @ToolArg(description = "Include diagnostic samples and all hub rankings")
                    Optional<Boolean> details,
            @ToolArg(description = "Project to query; omit for all") Optional<String> project) {
        return tools.get_overview(details, project);
    }

    @Tool(structured = true,
            description = "Search Quill's hidden tool catalog by name, purpose, or argument.")
    public String search_tools(
            @ToolArg(description = "Words to match against tool names, descriptions, and arguments")
                    String query,
            @ToolArg(description = "Maximum matches; default 10, max 30") Optional<Integer> limit) {
        String[] terms = Arrays.stream(query.strip().toLowerCase().split("\\s+"))
                .filter(term -> !term.isBlank()).toArray(String[]::new);
        int pageSize = Math.max(1, Math.min(30, limit.orElse(10)));
        var matches = Arrays.stream(QuillTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> matches(method, terms))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        ObjectNode result = JSON.createObjectNode();
        result.put("query", query);
        result.put("total", matches.size());
        result.put("showing", Math.min(pageSize, matches.size()));
        ObjectNode guidance = result.putObject("guidance");
        guidance.put("recommended_channel", recommendedChannel(terms));
        guidance.put("use_source_search_for",
                "exact literals, known paths, and confirming one concrete source occurrence");
        guidance.put("use_quill_for",
                "semantic symbols, dependency/call graphs, DI, generated code, history, and risk");
        ArrayNode listed = result.putArray("tools");
        matches.stream().limit(pageSize).forEach(method -> {
            ObjectNode item = listed.addObject();
            item.put("name", method.getName());
            item.put("description", method.getAnnotation(Tool.class).description());
            item.set("input_schema", JSON.valueToTree(McpToolCatalog.inputSchema(method)));
        });
        return result.toString();
    }

    @Tool(structured = true,
            description = "Invoke one hidden Quill tool using its name and JSON arguments.")
    public String execute_tool(
            @ToolArg(description = "Exact tool name returned by search_tools") String name,
            @ToolArg(description = "Arguments matching the tool's input_schema")
                    Map<String, Object> arguments) {
        Method method = Arrays.stream(QuillTools.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(Tool.class))
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElse(null);
        if (method == null) return error("Unknown tool: " + name);
        McpToolCatalog.Invocation invocation =
                McpToolCatalog.invokeMethod(tools, method, arguments);
        if (!invocation.error()) return invocation.text();
        try {
            if (invocation.text() != null && JSON.readTree(invocation.text()).has("error")) {
                return invocation.text();
            }
        } catch (Exception ignored) {
            // Convert catalog validation and invocation failures to structured errors.
        }
        return error(invocation.text());
    }

    private static boolean matches(Method method, String[] terms) {
        String searchable = (method.getName() + " "
                + method.getAnnotation(Tool.class).description() + " "
                + McpToolCatalog.inputSchema(method)).toLowerCase();
        return Arrays.stream(terms).allMatch(searchable::contains);
    }

    private static String recommendedChannel(String[] terms) {
        if (Arrays.stream(terms).anyMatch(SetLikeTerms.SOURCE::contains)) {
            return "source_search";
        }
        if (Arrays.stream(terms).anyMatch(SetLikeTerms.QUILL::contains)) {
            return "quill";
        }
        return "quill_if_semantic";
    }

    private static final class SetLikeTerms {
        private static final java.util.Set<String> SOURCE = java.util.Set.of(
                "literal", "text", "string", "known", "path", "grep", "rg");
        private static final java.util.Set<String> QUILL = java.util.Set.of(
                "dependency", "dependencies", "graph", "call", "injection", "bean",
                "history", "risk", "generated", "override", "implementation",
                "implementations");

        private SetLikeTerms() {}
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }
}
