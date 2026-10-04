package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.treblereel.mcp.diagnostics.DebugTrace;

/** Compact MCP entry point that discovers and invokes the full catalog on demand. */
final class RouterTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final QuillTools tools;

    RouterTools(QuillTools tools) {
        this.tools = tools;
    }

    @Tool(readOnly = true, structured = true,
            description = "Summarize indexed projects before choosing a specialized tool.")
    public String get_overview(
            @ToolArg(description = "Include diagnostic samples and all hub rankings")
                    Optional<Boolean> details,
            @ToolArg(description = "Project to query; omit for all") Optional<String> project) {
        return tools.get_overview(details, project);
    }

    @Tool(readOnly = true, structured = true,
            description = "Search Quill's hidden tool catalog by name, purpose, or argument.")
    public String search_tools(
            @ToolArg(description = "Words to match against tool names, descriptions, and arguments")
                    String query,
            @ToolArg(description = "Maximum matches; default 10, max 30") Optional<Integer> limit) {
        try (DebugTrace.Trace trace = DebugTrace.start("router_search")) {
            String[] terms = Arrays.stream(query.strip().toLowerCase().split("\\s+"))
                    .filter(term -> !term.isBlank()).toArray(String[]::new);
            int pageSize = Math.max(1, Math.min(30, limit.orElse(10)));
            List<Method> catalog = Arrays.stream(QuillTools.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .filter(method -> method.getAnnotation(Tool.class).readOnly()).toList();
            List<Candidate> matches = catalog.stream()
                    .map(method -> new Candidate(method, score(method, terms)))
                    .filter(candidate -> candidate.score() > 0)
                    .sorted(Comparator.comparingInt(Candidate::score).reversed()
                            .thenComparing(candidate -> candidate.method().getName()))
                    .toList();
            String channel = recommendedChannel(terms);
            trace.event("catalog_searched", Map.of(
                    "query", query, "hidden_tool_count", catalog.size(),
                    "match_count", matches.size(), "recommended_channel", channel,
                    "recommended_tool", matches.isEmpty()
                            ? "" : matches.getFirst().method().getName()));
            ObjectNode result = JSON.createObjectNode();
            result.put("query", query);
            result.put("total", matches.size());
            result.put("showing", Math.min(pageSize, matches.size()));
            ObjectNode guidance = result.putObject("guidance");
            guidance.put("recommended_channel", channel);
            if (!matches.isEmpty()) {
                guidance.put("recommended_tool", matches.getFirst().method().getName());
                guidance.put("selection_reason", "highest keyword match in Quill's tool catalog");
            }
            guidance.put("use_source_search_for",
                    "exact literals, known paths, and confirming one concrete source occurrence");
            guidance.put("use_quill_for",
                    "semantic symbols, dependency/call graphs, DI, generated code, history, and risk");
            guidance.put("fallback_when",
                    "use source or build evidence when Quill reports stale, partial, unknown, or unsupported results");
            guidance.put("response_budget",
                    "prefer the smallest useful limit and continue with offset only when needed");
            ArrayNode listed = result.putArray("tools");
            matches.stream().limit(pageSize).forEach(candidate -> {
                Method method = candidate.method();
                ObjectNode item = listed.addObject();
                item.put("name", method.getName());
                item.put("description", method.getAnnotation(Tool.class).description());
                Map<String, Object> schema = McpToolCatalog.inputSchema(method);
                item.set("input_schema", JSON.valueToTree(schema));
                item.set("required_arguments", JSON.valueToTree(schema.get("required")));
                item.put("match_score", candidate.score());
            });
            return result.toString();
        }
    }

    @Tool(readOnly = true, structured = true,
            description = "Invoke one hidden Quill tool using its name and JSON arguments.")
    public String execute_tool(
            @ToolArg(description = "Exact tool name returned by search_tools") String name,
            @ToolArg(description = "Arguments matching the tool's input_schema")
                    Map<String, Object> arguments) {
        try (DebugTrace.Trace trace = DebugTrace.start("router_execute")) {
            Method method = Arrays.stream(QuillTools.class.getDeclaredMethods())
                    .filter(candidate -> candidate.isAnnotationPresent(Tool.class))
                    .filter(candidate -> candidate.getAnnotation(Tool.class).readOnly())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElse(null);
            if (method == null) {
                trace.event("tool_rejected", Map.of("tool", name, "reason", "unknown_tool"));
                return error("Unknown tool: " + name);
            }
            trace.event("tool_selected", Map.of("tool", name, "argument_names",
                    arguments == null ? java.util.List.of()
                            : arguments.keySet().stream().sorted().toList()));
            McpToolCatalog.Invocation invocation =
                    McpToolCatalog.invokeMethod(tools, method, arguments);
            trace.event("tool_completed", Map.of("tool", name, "is_error", invocation.error()));
            if (!invocation.error()) return invocation.text();
            try {
                if (invocation.text() != null) {
                    JsonNode payload = JSON.readTree(invocation.text());
                    if (payload.has("error_code") || payload.has("error")) {
                        return invocation.text();
                    }
                }
            } catch (Exception ignored) {
                // Convert catalog validation and invocation failures to structured errors.
            }
            return error(invocation.text());
        }
    }

    private static int score(Method method, String[] terms) {
        String searchable = (method.getName() + " "
                + method.getAnnotation(Tool.class).description() + " "
                + McpToolCatalog.inputSchema(method)).toLowerCase();
        String name = method.getName().toLowerCase();
        int score = 0;
        for (String term : terms) {
            String normalized = normalizeTerm(term);
            if (normalized.length() < 3) continue;
            if (name.contains(normalized)) score += 3;
            else if (searchable.contains(normalized)) score++;
        }
        return score;
    }

    private static String normalizeTerm(String term) {
        return switch (term) {
            case "annotated", "annotations" -> "annotation";
            case "endpoints" -> "endpoint";
            case "implementations", "implementors" -> "implementation";
            case "affected", "impacted" -> "impact";
            case "errors" -> "error";
            case "problems" -> "problem";
            case "modules" -> "module";
            default -> term;
        };
    }

    private static String recommendedChannel(String[] terms) {
        if (Arrays.stream(terms).map(RouterTools::normalizeTerm)
                .anyMatch(SetLikeTerms.BUILD::contains)) {
            return "build";
        }
        if (Arrays.stream(terms).anyMatch(SetLikeTerms.SOURCE::contains)) {
            return "source_search";
        }
        if (Arrays.stream(terms).map(RouterTools::normalizeTerm)
                .anyMatch(SetLikeTerms.QUILL::contains)) {
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
                "annotation", "endpoint", "impact", "symbol", "module", "context",
                "plan", "change", "verify", "verification");
        private static final java.util.Set<String> BUILD = java.util.Set.of(
                "build", "compile", "compiler", "maven", "gradle", "failure", "error",
                "problem");

        private SetLikeTerms() {}
    }

    private record Candidate(Method method, int score) {}

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }
}
