package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;

/** Static local evidence, not an attestation of a running Claude session's effective permissions. */
final class ClaudePermissionStatus {
    static final String LIMITATION = "Local configuration only: user/managed settings, hooks, "
            + "workspace trust, connection approval and live client activation were not checked.";

    record Report(String project_root, boolean server_configured, boolean server_enabled,
            int tool_count, List<String> allowed_tools, List<String> missing_tools,
            List<String> denied_tools, List<String> ask_tools, List<String> owned_rules,
            List<String> invalid_files, String limitation) {
        boolean valid() { return invalid_files.isEmpty(); }
        boolean unrestrictedLocally() {
            return valid() && missing_tools.isEmpty() && denied_tools.isEmpty() && ask_tools.isEmpty();
        }
    }

    private ClaudePermissionStatus() {}

    static Report inspect(Path root) {
        root = root.toAbsolutePath().normalize();
        List<String> tools = ReadOnlyToolNames.all();
        List<String> allows = new ArrayList<>();
        List<String> denies = new ArrayList<>();
        List<String> asks = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String file : List.of("settings.json", "settings.local.json")) {
            try {
                var settings = ClaudeSettingsInstaller.readPermissionSettings(root.resolve(".claude").resolve(file));
                collect(settings.path("permissions").path("allow"), allows);
                collect(settings.path("permissions").path("deny"), denies);
                collect(settings.path("permissions").path("ask"), asks);
            } catch (Exception error) {
                invalid.add(".claude/" + file);
            }
        }
        List<String> owned = List.of();
        try { owned = ClaudeSettingsInstaller.ownedRules(root); }
        catch (Exception error) { invalid.add(".claude/quill-permissions.json"); }
        boolean configured = false;
        Path mcp = root.resolve(".mcp.json");
        if (Files.exists(mcp)) {
            try {
                var configuration = new ObjectMapper().readTree(mcp.toFile());
                if (configuration == null || !configuration.isObject()) throw new IllegalArgumentException("Invalid MCP configuration");
                var server = configuration.path("mcpServers").path("quill");
                configured = server.isObject() && server.path("command").isTextual()
                        && !server.path("command").asText().isBlank();
            } catch (Exception error) { invalid.add(".mcp.json"); }
        }
        var allowed = tools.stream().filter(tool -> matchesAny(allows, tool, true)).toList();
        return new Report(root.toString(), configured, ClaudeSettingsInstaller.isEnabled(root), tools.size(),
                allowed, tools.stream().filter(tool -> !allowed.contains(tool)).toList(),
                tools.stream().filter(tool -> matchesAny(denies, tool, false)).toList(),
                tools.stream().filter(tool -> matchesAny(asks, tool, false)).toList(),
                owned, List.copyOf(invalid), LIMITATION);
    }

    private static void collect(com.fasterxml.jackson.databind.JsonNode array, List<String> target) {
        if (array.isArray()) array.forEach(rule -> target.add(rule.asText()));
    }

    private static boolean matchesAny(List<String> rules, String tool, boolean allow) {
        for (String rule : rules) {
            if (rule.equals("mcp__quill")) return true;
            // Claude ignores settings-file MCP parameter rules and unanchored allow globs.
            if (rule.contains("(") || (allow && !rule.startsWith("mcp__quill__"))) continue;
            String regex = java.util.Arrays.stream(rule.split("\\*", -1))
                    .map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));
            if (tool.matches(regex)) return true;
        }
        return false;
    }
}
