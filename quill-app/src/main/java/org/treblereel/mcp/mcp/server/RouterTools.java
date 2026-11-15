package org.treblereel.mcp.mcp.server;

import org.treblereel.mcp.mcp.support.ToolResult;
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
import org.treblereel.mcp.mcp.tools.QuillTools;
import org.treblereel.mcp.mcp.tools.Tool;
import org.treblereel.mcp.mcp.tools.ToolArg;
import org.treblereel.mcp.core.Json;

/** Compact MCP entry point that discovers and invokes the full catalog on demand. */
final class RouterTools {

    private static final ObjectMapper JSON = Json.MAPPER;
    private final QuillTools tools;

    RouterTools(QuillTools tools) {
        this.tools = tools;
    }

    java.util.concurrent.CompletableFuture<Void> awaitPrepared(String selector) { return tools.awaitPrepared(selector); }

    java.util.List<String> waitStatus() { return tools.waitStatus(); }

    boolean workspaceMode() {
        return tools.workspaceMode();
    }

    @Tool(readOnly = true, structured = true,
            description = "Start here. Omit arguments for single-project or unknown server mode. Only --workspace servers support view=compact; follow retry_with on mode errors.")
    public String get_overview(
            @ToolArg(description = "Include diagnostic samples and all hub rankings")
                    Optional<Boolean> details,
            @ToolArg(description = "Project to query; omit for all") Optional<String> project,
            @ToolArg(description = "full (default) or compact (workspace map)", allowed = {"full", "compact"}) Optional<String> view,
            @ToolArg(description = "Compact page size (default 20, max 50)") Optional<Integer> limit,
            @ToolArg(description = "Compact offset (default 0)") Optional<Integer> offset) {
        return tools.get_overview(details, project, view, limit, offset);
    }

    @Tool(readOnly = true, structured = true,
            description = "Search Quill's hidden tool catalog by name, purpose, or argument; inspect selected schemas, then invoke execute_tool with matching arguments.")
    public String search_tools(
            @ToolArg(description = "Words to match against tool names, descriptions, and arguments")
                    String query,
            @ToolArg(description = "Maximum matches; default 5, max 30") Optional<Integer> limit) {
        try (DebugTrace.Trace trace = DebugTrace.start("router_search")) {
            String guessed = query.strip().toLowerCase();
            if (COMMAND_SCOPES.contains(guessed)) {
                ObjectNode result = JSON.createObjectNode().put("query", query).put("total", 0).put("showing", 0);
                result.putObject("guidance").put("note", commandScopeNote(guessed));
                result.putArray("tools");
                return result.toString();
            }
            if (NOT_TOOLS.contains(guessed)) {
                ObjectNode result = JSON.createObjectNode().put("query", query).put("total", 0).put("showing", 0);
                result.putObject("guidance").put("note", "Quill has no reindex tool: it refreshes the index itself "
                        + "after source changes and builds. Run the build, then query change_session again.");
                result.putArray("tools");
                return result.toString();
            }
            String normalizedQuery = ALIASES.getOrDefault(guessed, query);
            String intent = normalizedQuery.strip().toLowerCase().replace('_', ' ').replace('-', ' ');
            if (intent.contains("dependency") && intent.contains("source")) intent += " bytecode";
            String[] terms = Arrays.stream(intent.split("\\s+"))
                    .filter(term -> !term.isBlank()).distinct().toArray(String[]::new);
            int pageSize = Math.max(1, Math.min(30, limit.orElse(5)));
            List<Method> catalog = Arrays.stream(QuillTools.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Tool.class))
                    .filter(method -> method.getAnnotation(Tool.class).readOnly()).toList();
            List<Candidate> matches = catalog.stream()
                    .map(method -> new Candidate(method, score(method, terms) + (method.getName().equals(normalizedQuery.strip()) ? 1000 : 0)))
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
            // When to prefer source search or Quill is in the server instructions; repeating it in
            // every search answer was a third of each response.
            if (!matches.isEmpty()) guidance.put("recommended_tool", matches.getFirst().method().getName());
            if (ALIAS_ARGUMENTS.containsKey(guessed)) guidance.set("suggested_arguments", JSON.valueToTree(ALIAS_ARGUMENTS.get(guessed)));
            ArrayNode listed = result.putArray("tools");
            matches.stream().limit(pageSize).forEach(candidate -> {
                Method method = candidate.method();
                ObjectNode item = listed.addObject();
                item.put("name", method.getName());
                item.put("description", method.getAnnotation(Tool.class).description());
                Map<String, Object> schema = McpToolCatalog.inputSchema(method);
                item.set("input_schema", JSON.valueToTree(schema));
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
                String alias = ALIASES.get(name == null ? "" : name.strip().toLowerCase());
                if (alias == null && name != null && COMMAND_SCOPES.contains(name.strip().toLowerCase())) {
                    return JSON.createObjectNode().put("error", "Unknown tool: " + name)
                            .put("note", commandScopeNote(name.strip().toLowerCase())).toString();
                }
                if (alias == null) return error("Unknown tool: " + name);
                ObjectNode result = JSON.createObjectNode().put("error", "Unknown tool: " + name).put("did_you_mean", alias);
                if (ALIAS_ARGUMENTS.containsKey(name.strip().toLowerCase()))
                    result.set("suggested_arguments", JSON.valueToTree(ALIAS_ARGUMENTS.get(name.strip().toLowerCase())));
                return result.toString();
            }
            trace.event("tool_selected", Map.of("tool", name, "argument_names",
                    arguments == null ? java.util.List.of()
                            : arguments.keySet().stream().sorted().toList()));
            ToolResult invocation =
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

    /** Scopes of recommended build commands; V23 CQ-049 Claude called execute_tool("quick_compile"). */
    private static final java.util.Set<String> COMMAND_SCOPES = java.util.Set.of("quick_compile", "focused", "module_fallback");

    private static String commandScopeNote(String scope) {
        return scope + " is the scope of a command in change_session's verification_plan.commands, not a tool:"
                + " run that command's argv from verification_plan.working_directory in your shell. Quill never runs builds.";
    }

    /** Names agents guessed in recorded runs; each search costs a model turn, so map them to the tool that answers. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("resolve_injection", "list_injection_points"),
            Map.entry("get_injection_points", "list_injection_points"),
            Map.entry("get_bean_dependencies", "list_injection_points"),
            Map.entry("get_injection_graph", "list_injection_points"),
            Map.entry("find_references", "find_usages"),
            Map.entry("list_observers", "find_entry_points"),
            Map.entry("event_flow", "find_entry_points"),
            Map.entry("analyze_lifecycle", "trace_state_lifecycle"),
            Map.entry("get_lifecycle", "trace_state_lifecycle"),
            Map.entry("get_bean_lifecycle", "trace_state_lifecycle"),
            Map.entry("get_transaction_boundaries", "get_context"),
            Map.entry("get_configuration", "find_configuration_references"),
            // V41 Codex guesses.
            Map.entry("get_di_graph", "get_dependencies"),
            Map.entry("find_cdi_cycles", "find_cycles"),
            Map.entry("resolve_configuration", "find_configuration_references"),
            Map.entry("get_bean_removal_analysis", "list_beans"));
    private static final Map<String, Map<String, String>> ALIAS_ARGUMENTS = Map.of(
            "list_observers", Map.of("kind", "observer"),
            "event_flow", Map.of("kind", "observer"),
            "get_transaction_boundaries", Map.of("targets", "[\"Class#method(ParamType)\"]: interceptors and calls of the method"));
    private static final java.util.Set<String> NOT_TOOLS = java.util.Set.of(
            "reindex", "refresh_index", "index_project", "rebuild_index", "update_index");

    private static int score(Method method, String[] terms) {
        String searchable = (method.getName() + " "
                + method.getAnnotation(Tool.class).description() + " "
                + McpToolCatalog.inputSchema(method)).toLowerCase();
        String name = method.getName().toLowerCase();
        int score = 0;
        int matched = 0;
        for (String term : terms) {
            String normalized = normalizeTerm(term);
            if (normalized.length() < 3) continue;
            if (name.contains(normalized)) { score += 3; matched++; }
            else if (searchable.contains(normalized)) { score++; matched++; }
        }
        return score + matched * matched;
    }

    private static String normalizeTerm(String term) {
        return switch (term) {
            case "inspect", "inspection" -> "details";
            case "annotated", "annotations" -> "annotation";
            case "references" -> "usages";
            case "endpoints" -> "endpoint";
            case "implementations", "implementors" -> "implementation";
            case "affected", "impacted" -> "impact";
            case "errors" -> "error";
            case "problems" -> "problem";
            case "modules" -> "module";
            case "observers" -> "observer";
            case "events" -> "event";
            case "consumers" -> "consumer";
            case "transactional" -> "transaction";
            default -> term;
        };
    }

    private static String recommendedChannel(String[] terms) {
        if (Arrays.stream(terms).map(RouterTools::normalizeTerm)
                .anyMatch(SetLikeTerms.BUILD::contains)) {
            return "build";
        }
        if (Arrays.stream(terms).anyMatch(SetLikeTerms.SOURCE::contains)
                && Arrays.stream(terms).map(RouterTools::normalizeTerm)
                        .noneMatch(SetLikeTerms.QUILL::contains)) {
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
                "plan", "change", "verify", "verification", "observer", "event");
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
