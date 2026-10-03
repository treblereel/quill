package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Enables Quill from project-local .mcp.json without an interactive Claude approval step. */
final class ClaudeSettingsInstaller {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClaudeSettingsInstaller() {}

    static boolean install(Path projectRoot) {
        Path file = projectRoot.resolve(".claude/settings.json");
        try {
            ObjectNode root = readRoot(file);
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
            System.err.println("[quill] Warning: could not update " + file + ": "
                    + error.getMessage());
            return false;
        }
    }

    static boolean uninstall(Path projectRoot) throws IOException {
        Path file = projectRoot.resolve(".claude/settings.json");
        if (!Files.isRegularFile(file)) return false;
        ObjectNode root = readRoot(file);
        if (root == null) return false;
        JsonNode configured = root.get("enabledMcpjsonServers");
        if (!(configured instanceof ArrayNode enabled)) return false;
        boolean removed = false;
        for (int i = enabled.size() - 1; i >= 0; i--) {
            if ("quill".equals(enabled.get(i).asText())) {
                enabled.remove(i);
                removed = true;
            }
        }
        if (!removed) return false;
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

    static boolean isEnabled(Path projectRoot) {
        Path file = projectRoot.resolve(".claude/settings.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonNode enabled = JSON.readTree(file.toFile()).path("enabledMcpjsonServers");
            if (!enabled.isArray()) return false;
            for (JsonNode name : enabled) if ("quill".equals(name.asText())) return true;
        } catch (IOException ignored) {
            // Malformed optional client settings are reported as not enabled.
        }
        return false;
    }

    private static ObjectNode readRoot(Path file) throws IOException {
        if (!Files.exists(file)) return JSON.createObjectNode();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return null;
        JsonNode parsed = JSON.readTree(file.toFile());
        return parsed instanceof ObjectNode object ? object : null;
    }

    private static void writeAtomically(Path file, ObjectNode root) throws IOException {
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
