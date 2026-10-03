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

/** Owns the Quill entry in a Claude-compatible project-local .mcp.json. */
final class McpJsonInstaller {

    enum Result {
        ADDED,
        REPLACED,
        REMOVED,
        NOT_PRESENT,
        UNCHANGED,
        UNSUPPORTED
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpJsonInstaller() {}

    static Result installProject(Path projectRoot, String binary) throws IOException {
        Path normalized = projectRoot.toAbsolutePath().normalize();
        Path file = normalized.resolve(".mcp.json");
        ObjectNode root = readRoot(file);
        if (root == null) return Result.UNSUPPORTED;
        JsonNode existingServers = root.get("mcpServers");
        if (existingServers != null && !existingServers.isObject()) return Result.UNSUPPORTED;
        ObjectNode servers = existingServers == null
                ? root.putObject("mcpServers") : (ObjectNode) existingServers;

        ObjectNode desired = projectServer(normalized, binary);
        JsonNode previous = servers.get("quill");
        if (desired.equals(previous)) return Result.UNCHANGED;
        servers.set("quill", desired);
        writeAtomically(file, root);
        return previous == null ? Result.ADDED : Result.REPLACED;
    }

    static Result uninstallProject(Path projectRoot) throws IOException {
        Path file = projectRoot.toAbsolutePath().normalize().resolve(".mcp.json");
        if (!Files.isRegularFile(file)) return Result.NOT_PRESENT;
        ObjectNode root = readRoot(file);
        if (root == null) return Result.UNSUPPORTED;
        JsonNode serversNode = root.get("mcpServers");
        if (!(serversNode instanceof ObjectNode servers) || !servers.has("quill")) {
            return Result.NOT_PRESENT;
        }
        servers.remove("quill");
        if (servers.isEmpty()) root.remove("mcpServers");
        if (root.isEmpty()) {
            Files.delete(file);
        } else {
            writeAtomically(file, root);
        }
        return Result.REMOVED;
    }

    static Result installWorkspace(Path configRoot, Path workspaceRoot, String binary)
            throws IOException {
        Path file = configRoot.toAbsolutePath().normalize().resolve(".mcp.json");
        ObjectNode root = readRoot(file);
        if (root == null) return Result.UNSUPPORTED;
        JsonNode existingServers = root.get("mcpServers");
        if (existingServers != null && !existingServers.isObject()) return Result.UNSUPPORTED;
        ObjectNode servers = existingServers == null
                ? root.putObject("mcpServers") : (ObjectNode) existingServers;

        ObjectNode desired = workspaceServer(workspaceRoot, binary);
        JsonNode previous = servers.get("quill");
        if (desired.equals(previous)) return Result.UNCHANGED;
        servers.set("quill", desired);
        writeAtomically(file, root);
        return previous == null ? Result.ADDED : Result.REPLACED;
    }

    static Result uninstallWorkspace(Path configRoot, Path workspaceRoot) throws IOException {
        Path file = configRoot.toAbsolutePath().normalize().resolve(".mcp.json");
        if (!Files.isRegularFile(file)) return Result.NOT_PRESENT;
        ObjectNode root = readRoot(file);
        if (root == null) return Result.UNSUPPORTED;
        JsonNode serversNode = root.get("mcpServers");
        if (!(serversNode instanceof ObjectNode servers)) return Result.NOT_PRESENT;
        JsonNode quill = servers.get("quill");
        if (!targetsWorkspace(quill, workspaceRoot)) return Result.UNCHANGED;

        servers.remove("quill");
        if (servers.isEmpty()) root.remove("mcpServers");
        if (root.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            writeAtomically(file, root);
        }
        return Result.REMOVED;
    }

    private static ObjectNode readRoot(Path file) throws IOException {
        if (!Files.exists(file)) return JSON.createObjectNode();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return null;
        JsonNode parsed = JSON.readTree(file.toFile());
        return parsed instanceof ObjectNode object ? object : null;
    }

    private static ObjectNode workspaceServer(Path workspaceRoot, String binary) {
        ObjectNode server = JSON.createObjectNode();
        server.put("type", "stdio");
        server.put("command", binary == null ? "quill" : binary);
        server.put("alwaysLoad", true);
        ArrayNode args = server.putArray("args");
        args.add("--mcp");
        args.add("--workspace");
        args.add(workspaceRoot.toAbsolutePath().normalize().toString());
        return server;
    }

    private static ObjectNode projectServer(Path projectRoot, String binary) {
        ObjectNode server = JSON.createObjectNode();
        server.put("type", "stdio");
        server.put("command", binary == null ? "quill" : binary);
        server.put("alwaysLoad", true);
        server.putArray("args").add("--mcp");
        server.put("cwd", projectRoot.toString());
        return server;
    }

    private static boolean targetsWorkspace(JsonNode server, Path workspaceRoot) {
        if (server == null || !server.isObject() || !server.path("args").isArray()) return false;
        String expected = workspaceRoot.toAbsolutePath().normalize().toString();
        for (int i = 0; i + 1 < server.path("args").size(); i++) {
            if ("--workspace".equals(server.path("args").get(i).asText())
                    && expected.equals(server.path("args").get(i + 1).asText())) return true;
        }
        return false;
    }

    private static void writeAtomically(Path file, ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".mcp.json.", ".tmp");
        try {
            if (Files.isRegularFile(file)) {
                Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
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
