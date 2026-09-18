package org.treblereel.mcp.mcp;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Selects a smaller MCP catalog for clients that do not need every Quill capability. */
public final class McpToolProfile {

    private static final Set<String> CORE = Set.of(
            "get_overview", "search_classes", "resolve_entities", "get_symbol_details",
            "search_symbols", "find_usages", "find_symbol_usages", "get_dependencies",
            "find_impacted_tests", "assess_change_risk", "get_build_status",
            "get_build_problems");
    private static final Set<String> CODE = union(CORE, Set.of(
            "find_implementations", "get_type_hierarchy", "get_call_hierarchy",
            "find_method_overrides", "find_unused_classes", "find_unused_methods",
            "find_unused_fields", "find_entry_points", "get_module_graph",
            "get_package_graph", "find_architecture_violations", "find_cycles",
            "get_annotated_classes", "find_annotated_symbols", "find_framework_endpoints",
            "inspect_service_descriptors", "find_configuration_references",
            "find_resource_references", "list_external_dependencies"));
    private static final Set<String> DI = Set.of(
            "get_overview", "search_classes", "resolve_entities", "list_beans",
            "list_injection_points", "get_dependencies", "find_usages",
            "get_annotated_classes", "find_annotated_symbols", "find_framework_endpoints",
            "find_configuration_references", "find_resource_references",
            "inspect_service_descriptors");
    private static final Set<String> GIT = Set.of(
            "get_overview", "resolve_entities", "find_git_hotspots", "get_file_history",
            "find_co_changed_files", "get_recent_changes", "assess_change_risk",
            "compare_index", "get_build_status");
    private static final Set<String> NAMES = Set.of("full", "router", "core", "code", "di", "git");

    private final boolean full;
    private final boolean router;
    private final Set<String> tools;

    private McpToolProfile(boolean full, boolean router, Set<String> tools) {
        this.full = full;
        this.router = router;
        this.tools = Set.copyOf(tools);
    }

    public static McpToolProfile full() {
        return new McpToolProfile(true, false, Set.of());
    }

    public static McpToolProfile parse(String value) {
        if (value == null || value.isBlank()) return full();
        Set<String> names = Arrays.stream(value.split(","))
                .map(String::strip)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (names.contains("router")) {
            if (names.size() != 1) {
                throw new IllegalArgumentException(
                        "MCP tool profile 'router' cannot be combined with other profiles");
            }
            return new McpToolProfile(false, true, Set.of());
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String name : names) {
            if (!NAMES.contains(name)) {
                throw new IllegalArgumentException("Unknown MCP tool profile '" + name
                        + "'; expected full, router, core, code, di, git, or a comma-separated union");
            }
            if (name.equals("full")) return full();
            selected.addAll(switch (name) {
                case "core" -> CORE;
                case "code" -> CODE;
                case "di" -> DI;
                case "git" -> GIT;
                default -> throw new IllegalStateException(name);
            });
        }
        return new McpToolProfile(false, false, selected);
    }

    boolean includes(String toolName) {
        return full || tools.contains(toolName);
    }

    boolean router() {
        return router;
    }

    Set<String> tools() {
        return tools;
    }

    private static Set<String> union(Set<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(groups).forEach(result::addAll);
        return Set.copyOf(result);
    }
}
