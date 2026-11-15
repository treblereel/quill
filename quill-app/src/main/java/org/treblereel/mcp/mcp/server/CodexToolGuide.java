package org.treblereel.mcp.mcp.server;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.treblereel.mcp.mcp.tools.QuillTools;
import org.treblereel.mcp.mcp.tools.Tool;

/**
 * The argument lists of the codex profile's tools and the names of the routed ones, for the
 * AGENTS.md block. Codex defers MCP tool schemas and finds them by filtering ALL_TOOLS (206 of its
 * 547 script cells in the cryostat V41 run); AGENTS.md is the Quill text it always reads.
 */
public final class CodexToolGuide {

    private static final Set<String> ROUTER = Set.of("search_tools", "execute_tool");
    private static final Set<String> PAGING = Set.of("limit", "offset", "module", "source_set", "evidence_detail", "evidence_offset");

    private CodexToolGuide() {
    }

    public static String text() {
        Set<String> listed = McpToolProfile.parse("codex").tools();
        Map<String, Method> tools = new TreeMap<>();
        Arrays.stream(QuillTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class) && method.getAnnotation(Tool.class).readOnly())
                .forEach(method -> tools.putIfAbsent(method.getName(), method));
        StringBuilder text = new StringBuilder("Quill tool arguments (`?` optional; most also take limit?, offset?, module?,\n"
                + "source_set?). Call `tools.mcp__quill__<name>(args)` directly instead of filtering `ALL_TOOLS`.\n");
        tools.forEach((name, method) -> {
            if (!listed.contains(name) || ROUTER.contains(name)) return;
            text.append("- `").append(name).append("({").append(arguments(method)).append("})`\n");
        });
        List<String> routed = tools.keySet().stream().filter(name -> !listed.contains(name) && !ROUTER.contains(name)).toList();
        text.append("\nOther tools run through `mcp__quill__execute_tool({name, arguments})`; `search_tools` with the exact\n"
                + "name returns one's arguments: ").append(String.join(", ", routed)).append(".\n");
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static String arguments(Method method) {
        Map<String, Object> schema = McpToolCatalog.inputSchema(method, true);
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        return String.join(", ", properties.keySet().stream().filter(name -> required.contains(name) || !PAGING.contains(name))
                .map(name -> required.contains(name) ? name : name + "?").toList());
    }
}
