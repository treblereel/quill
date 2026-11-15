package org.treblereel.mcp.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.diagnostics.QuillLog;

/** Registers the project-local server; optional invocation permissions require separate consent. */
public final class ClaudeSettingsInstaller {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ClaudeSettingsInstaller() {}

    public static boolean install(Path projectRoot) {
        Path file = projectRoot.resolve(".claude/settings.json");
        try {
            ObjectNode root = readPermissionSettings(file);
            if (root == null) return false;
            JsonNode configured = root.get("enabledMcpjsonServers");
            if (configured != null && !configured.isArray()) return false;
            ArrayNode enabled = configured == null
                    ? root.putArray("enabledMcpjsonServers") : (ArrayNode) configured;
            for (JsonNode name : enabled) {
                if ("quill".equals(name.asText())) return true;
            }
            enabled.add("quill");
            writeAtomically(file, root);
            return true;
        } catch (IOException error) {
            QuillLog.warn("could not update " + file + ": "
                    + error.getMessage());
            return false;
        }
    }

    public static boolean uninstall(Path projectRoot) throws IOException {
        boolean permissionsRemoved = removeOwnedPermissions(projectRoot);
        Path file = projectRoot.resolve(".claude/settings.json");
        if (!Files.isRegularFile(file)) return permissionsRemoved;
        ObjectNode root = readRoot(file);
        if (root == null) return permissionsRemoved;
        JsonNode configured = root.get("enabledMcpjsonServers");
        if (!(configured instanceof ArrayNode enabled)) return permissionsRemoved;
        boolean removed = false;
        for (int i = enabled.size() - 1; i >= 0; i--) {
            if ("quill".equals(enabled.get(i).asText())) {
                enabled.remove(i);
                removed = true;
            }
        }
        if (!removed) return permissionsRemoved;
        if (enabled.isEmpty()) root.remove("enabledMcpjsonServers");
        if (root.isEmpty()) {
            Files.delete(file);
            Path directory = file.getParent();
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) Files.delete(directory);
            }
        } else {
            writeAtomically(file, root);
        }
        return true;
    }

    public static boolean areToolsAllowed(Path projectRoot, List<String> rules) {
        Set<String> allowed = new LinkedHashSet<>();
        try {
            for (String name : List.of("settings.json", "settings.local.json")) {
                ObjectNode root = readRoot(projectRoot.resolve(".claude").resolve(name));
                if (root == null) return false;
                JsonNode configured = root.path("permissions").path("allow");
                if (configured.isArray()) configured.forEach(rule -> allowed.add(rule.asText()));
            }
        } catch (IOException ignored) {
            return false;
        }
        return allowed.contains("mcp__quill__*") || allowed.contains("mcp__quill")
                || allowed.containsAll(rules);
    }

    public static boolean allowTools(Path projectRoot, List<String> rules) {
        Path file = projectRoot.resolve(".claude/settings.local.json");
        Path receiptFile = projectRoot.resolve(".claude/quill-permissions.json");
        try {
            if (!rules.stream().allMatch(ClaudeSettingsInstaller::quillRule)) return false;
            ObjectNode root = readPermissionSettings(file);
            ObjectNode receipt = readRoot(receiptFile);
            if (root == null || receipt == null || !validReceipt(receipt)) return false;
            JsonNode configured = root.get("permissions");
            if (configured != null && !(configured instanceof ObjectNode)) return false;
            ObjectNode permissions = configured == null
                    ? root.putObject("permissions") : (ObjectNode) configured;
            JsonNode configuredAllow = permissions.get("allow");
            if (configuredAllow != null && !stringArray(configuredAllow)) return false;
            ArrayNode allowed = configuredAllow == null
                    ? permissions.putArray("allow") : (ArrayNode) configuredAllow;
            Set<String> existing = new LinkedHashSet<>();
            allowed.forEach(rule -> existing.add(rule.asText()));
            Set<String> owned = new LinkedHashSet<>();
            receipt.path("rules").forEach(rule -> owned.add(rule.asText()));
            boolean changed = false;
            if (rules.contains(SERVER_RULE)) {
                // The server rule covers every concrete tool name Quill granted before; drop those.
                for (int i = allowed.size() - 1; i >= 0; i--) {
                    String rule = allowed.get(i).asText();
                    if (concreteQuillRule(rule) && owned.remove(rule)) {
                        allowed.remove(i);
                        existing.remove(rule);
                        changed = true;
                    }
                }
                changed |= owned.removeIf(ClaudeSettingsInstaller::concreteQuillRule);
            }
            for (String rule : rules) {
                if (existing.add(rule)) {
                    allowed.add(rule);
                    owned.add(rule);
                    changed = true;
                }
            }
            if (!changed) return true;
            receipt.put("schema_version", 1);
            ArrayNode recorded = receipt.putArray("rules");
            owned.forEach(recorded::add);
            // Record ownership first: interruption must never leave untracked permission grants.
            writeAtomically(receiptFile, receipt);
            writeAtomically(file, root);
            return true;
        } catch (IOException error) {
            QuillLog.warn("could not update " + file + ": "
                    + error.getMessage());
            return false;
        }
    }

    public static boolean removeOwnedPermissions(Path projectRoot) throws IOException {
        Path receiptFile = projectRoot.resolve(".claude/quill-permissions.json");
        if (!Files.exists(receiptFile)) return false;
        ObjectNode receipt = readRoot(receiptFile);
        if (receipt == null || receipt.isEmpty() || !validReceipt(receipt)) return false;
        Path file = projectRoot.resolve(".claude/settings.local.json");
        ObjectNode root = readRoot(file);
        if (root == null) return false;
        JsonNode configured = root.get("permissions");
        if (configured != null && !(configured instanceof ObjectNode)) return false;
        if (configured instanceof ObjectNode permissions) {
            JsonNode configuredAllow = permissions.get("allow");
            if (configuredAllow != null && !stringArray(configuredAllow)) return false;
            if (configuredAllow instanceof ArrayNode allowed) {
                Set<String> owned = new LinkedHashSet<>();
                receipt.path("rules").forEach(rule -> owned.add(rule.asText()));
                for (int i = allowed.size() - 1; i >= 0; i--) {
                    if (owned.contains(allowed.get(i).asText())) allowed.remove(i);
                }
                if (allowed.isEmpty()) permissions.remove("allow");
            }
            if (permissions.isEmpty()) root.remove("permissions");
        }
        if (Files.exists(file)) {
            if (root.isEmpty()) Files.delete(file);
            else writeAtomically(file, root);
        }
        Files.delete(receiptFile);
        Path directory = receiptFile.getParent();
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) Files.delete(directory);
        }
        return true;
    }

    private static boolean validReceipt(ObjectNode receipt) {
        if (receipt.isEmpty()) return true;
        if (receipt.path("schema_version").asInt() != 1 || !stringArray(receipt.get("rules"))) {
            return false;
        }
        for (JsonNode rule : receipt.path("rules")) {
            if (!quillRule(rule.asText())) return false;
        }
        return true;
    }

    private static boolean stringArray(JsonNode node) {
        if (node == null || !node.isArray()) return false;
        for (JsonNode item : node) if (!item.isTextual()) return false;
        return true;
    }

    private static final String SERVER_RULE = "mcp__quill";

    private static boolean quillRule(String rule) {
        return SERVER_RULE.equals(rule) || concreteQuillRule(rule);
    }

    private static boolean concreteQuillRule(String rule) {
        return rule != null && rule.matches("mcp__quill__[a-z][a-z0-9_]*");
    }

    public static List<String> ownedRules(Path projectRoot) throws IOException {
        Path file = projectRoot.resolve(".claude/quill-permissions.json");
        ObjectNode receipt = readRoot(file);
        if (receipt == null || !validReceipt(receipt)) throw new IOException("Invalid permission receipt");
        java.util.ArrayList<String> rules = new java.util.ArrayList<>();
        receipt.path("rules").forEach(rule -> rules.add(rule.asText()));
        return List.copyOf(rules);
    }

    public static ObjectNode readPermissionSettings(Path file) throws IOException {
        ObjectNode root = readRoot(file);
        if (root == null) throw new IOException("Unsupported settings file");
        JsonNode permissions = root.get("permissions");
        if (permissions != null && !permissions.isObject()) throw new IOException("Invalid permissions object");
        if (permissions != null) {
            for (String kind : List.of("allow", "ask", "deny")) {
                JsonNode configured = permissions.get(kind);
                if (configured != null && !stringArray(configured)) {
                    throw new IOException("Invalid permission rules");
                }
            }
        }
        return root;
    }

    public static boolean isEnabled(Path projectRoot) {
        Path file = projectRoot.resolve(".claude/settings.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            ObjectNode root = readRoot(file);
            if (root == null) return false;
            JsonNode enabled = root.path("enabledMcpjsonServers");
            if (!enabled.isArray()) return false;
            for (JsonNode name : enabled) if ("quill".equals(name.asText())) return true;
        } catch (IOException ignored) {
            // Malformed optional client settings are reported as not enabled.
        }
        return false;
    }

    private static ObjectNode readRoot(Path file) throws IOException {
        if (Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file)) return null;
        if (!Files.exists(file)) return JSON.createObjectNode();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return null;
        JsonNode parsed = JSON.readTree(file.toFile());
        return parsed instanceof ObjectNode object ? object : null;
    }

    static void writeAtomically(Path file, ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".settings.json.", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
