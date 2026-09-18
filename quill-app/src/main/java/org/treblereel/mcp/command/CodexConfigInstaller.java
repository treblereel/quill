package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class CodexConfigInstaller {

    enum Result {
        SKIPPED,
        NOT_PRESENT,
        ADDED,
        REPLACED,
        REMOVED,
        ALREADY_CONFIGURED,
        UNSUPPORTED,
        FAILED
    }

    private CodexConfigInstaller() {}

    static Result installIfPresent(Path projectRoot, String binary) {
        Path config = projectRoot.resolve(".codex/config.toml");
        if (!Files.exists(config)) return Result.NOT_PRESENT;
        if (!Files.isRegularFile(config) || Files.isSymbolicLink(config)) {
            warn(config, "is not a regular file; leaving it unchanged");
            return Result.UNSUPPORTED;
        }

        try {
            String content = Files.readString(config, StandardCharsets.UTF_8);
            if (definesQuillServer(content)) return Result.ALREADY_CONFIGURED;
            if (definesInlineMcpServers(content)) {
                warn(config, "uses an inline mcp_servers table; add the Quill entry manually");
                return Result.UNSUPPORTED;
            }

            String updated = appendBlock(content, projectRoot, binary);
            writeAtomically(config, updated);
            System.err.println("[quill] Added the Quill MCP server to " + config + ".");
            return Result.ADDED;
        } catch (IOException e) {
            warn(config, e.getMessage());
            return Result.FAILED;
        }
    }

    static Result installWorkspaceIfPresent(
            Path projectRoot, Path workspaceRoot, String binary) {
        Path config = projectRoot.resolve(".codex/config.toml");
        if (!Files.exists(config)) return Result.NOT_PRESENT;
        if (!Files.isRegularFile(config) || Files.isSymbolicLink(config)) {
            warn(config, "is not a regular file; leaving it unchanged");
            return Result.UNSUPPORTED;
        }
        try {
            String content = Files.readString(config, StandardCharsets.UTF_8);
            if (definesInlineMcpServers(content)) {
                warn(config, "uses an inline mcp_servers table; add the Quill entry manually");
                return Result.UNSUPPORTED;
            }
            boolean replaced = definesQuillServer(content);
            String cleaned = removeQuillServer(content);
            String updated = appendWorkspaceBlock(cleaned, projectRoot, workspaceRoot, binary);
            writeAtomically(config, updated);
            return replaced ? Result.REPLACED : Result.ADDED;
        } catch (IOException e) {
            warn(config, e.getMessage());
            return Result.FAILED;
        }
    }

    static Result uninstallWorkspaceIfPresent(Path projectRoot, Path workspaceRoot) {
        Path config = projectRoot.resolve(".codex/config.toml");
        if (!Files.isRegularFile(config)) return Result.NOT_PRESENT;
        try {
            String content = Files.readString(config, StandardCharsets.UTF_8);
            if (!targetsWorkspace(content, workspaceRoot)) return Result.ALREADY_CONFIGURED;
            writeAtomically(config, removeQuillServer(content));
            return Result.REMOVED;
        } catch (IOException e) {
            warn(config, e.getMessage());
            return Result.FAILED;
        }
    }

    static boolean definesQuillServer(String content) {
        boolean inRootMcpTable = false;
        boolean beforeFirstTable = true;
        for (String line : content.split("\\R", -1)) {
            String withoutComment = stripComment(line).trim();
            if (withoutComment.isEmpty()) continue;

            if (withoutComment.startsWith("[")) {
                beforeFirstTable = false;
                String table = tableName(withoutComment);
                if (table == null) {
                    inRootMcpTable = false;
                    continue;
                }
                if (table.equals("mcp_servers.quill")
                        || table.startsWith("mcp_servers.quill.")) {
                    return true;
                }
                inRootMcpTable = table.equals("mcp_servers");
                continue;
            }

            String key = assignmentKey(withoutComment);
            if (key == null) continue;
            if (inRootMcpTable && key.equals("quill")) return true;
            if (beforeFirstTable && (key.equals("mcp_servers.quill")
                    || key.startsWith("mcp_servers.quill."))) {
                return true;
            }
        }
        return false;
    }

    private static boolean definesInlineMcpServers(String content) {
        boolean beforeFirstTable = true;
        for (String line : content.split("\\R", -1)) {
            String withoutComment = stripComment(line).trim();
            if (withoutComment.isEmpty()) continue;
            if (withoutComment.startsWith("[")) {
                beforeFirstTable = false;
                continue;
            }
            if (beforeFirstTable && "mcp_servers".equals(assignmentKey(withoutComment))) {
                return true;
            }
        }
        return false;
    }

    private static String tableName(String line) {
        int opening = line.startsWith("[[") ? 2 : 1;
        int closing = line.indexOf(']', opening);
        if (closing < 0) return null;
        return normalizePath(line.substring(opening, closing));
    }

    private static String assignmentKey(String line) {
        int equals = indexOfUnquoted(line, '=');
        if (equals < 0) return null;
        return normalizePath(line.substring(0, equals));
    }

    private static String normalizePath(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle && !isEscaped(value, i)) {
                inDouble = !inDouble;
            } else if (!Character.isWhitespace(ch)) {
                normalized.append(ch);
            }
        }
        return normalized.toString();
    }

    private static String stripComment(String line) {
        int comment = indexOfUnquoted(line, '#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static int indexOfUnquoted(String value, char wanted) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (ch == '"' && !inSingle && !isEscaped(value, i)) {
                inDouble = !inDouble;
            } else if (ch == wanted && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String value, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && value.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 != 0;
    }

    private static String appendBlock(
            String content, Path projectRoot, String binary) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String command = binary != null ? binary : "quill";

        StringBuilder block = new StringBuilder();
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) {
            block.append(newline);
        }
        if (!content.isEmpty()) block.append(newline);
        block.append("# Added by Quill.")
                .append(newline)
                .append("[mcp_servers.quill]").append(newline)
                .append("command = ").append(tomlString(command)).append(newline)
                .append("args = [\"--mcp\"]").append(newline)
                .append("cwd = ").append(tomlString(projectRoot.toAbsolutePath().normalize().toString()))
                .append(newline);
        return content + block;
    }

    private static String appendWorkspaceBlock(String content, Path projectRoot,
            Path workspaceRoot, String binary) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String command = binary != null ? binary : "quill";
        StringBuilder block = new StringBuilder();
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) {
            block.append(newline);
        }
        if (!content.isBlank()) block.append(newline);
        block.append(workspaceMarker(workspaceRoot)).append(newline)
                .append("[mcp_servers.quill]").append(newline)
                .append("command = ").append(tomlString(command)).append(newline)
                .append("args = [\"--mcp\", \"--workspace\", ")
                .append(tomlString(workspaceRoot.toAbsolutePath().normalize().toString()))
                .append(']').append(newline)
                .append("cwd = ")
                .append(tomlString(projectRoot.toAbsolutePath().normalize().toString()))
                .append(newline);
        return content + block;
    }

    private static boolean targetsWorkspace(String content, Path workspaceRoot) {
        String marker = workspaceMarker(workspaceRoot);
        return content.lines().map(String::trim).anyMatch(marker::equals);
    }

    private static String workspaceMarker(Path workspaceRoot) {
        String normalized = workspaceRoot.toAbsolutePath().normalize().toString();
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                normalized.getBytes(StandardCharsets.UTF_8));
        return "# Added by Quill for workspace " + encoded + ".";
    }

    private static String removeQuillServer(String content) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\R", -1);
        StringBuilder result = new StringBuilder(content.length());
        boolean skipTable = false;
        boolean inRootMcpTable = false;
        boolean beforeFirstTable = true;
        for (String line : lines) {
            if (line.trim().startsWith("# Added by Quill for workspace ")) continue;
            String stripped = stripComment(line).trim();
            if (stripped.startsWith("[")) {
                beforeFirstTable = false;
                String table = tableName(stripped);
                skipTable = table != null && (table.equals("mcp_servers.quill")
                        || table.startsWith("mcp_servers.quill."));
                inRootMcpTable = "mcp_servers".equals(table);
                if (skipTable) continue;
            } else if (skipTable) {
                continue;
            }
            String key = assignmentKey(stripped);
            if (inRootMcpTable && "quill".equals(key)) continue;
            if (beforeFirstTable && key != null
                    && (key.equals("mcp_servers.quill")
                    || key.startsWith("mcp_servers.quill."))) continue;
            if (result.length() > 0) result.append(newline);
            result.append(line);
        }
        String cleaned = result.toString();
        while (cleaned.endsWith(newline + newline + newline)) {
            cleaned = cleaned.substring(0, cleaned.length() - newline.length());
        }
        return cleaned;
    }

    private static String tomlString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\b' -> escaped.append("\\b");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\f' -> escaped.append("\\f");
                case '\r' -> escaped.append("\\r");
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        escaped.append(String.format("\\u%04X", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void writeAtomically(Path config, String content) throws IOException {
        Path temp = Files.createTempFile(config.getParent(), ".config.toml.", ".tmp");
        try {
            Files.copy(config, temp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void warn(Path config, String message) {
        System.err.println("[quill] Warning: could not update " + config + ": " + message);
    }
}
