package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public final class GitHookInstaller {

    private static final String MARKER_START = "# --- joker-start ---";
    private static final String MARKER_END = "# --- joker-end ---";

    private static final String HOOK_BLOCK = """
            %s
            if command -v joker >/dev/null 2>&1; then
                joker update --project "$(git rev-parse --show-toplevel)" >/dev/null 2>&1 &
            elif [ -f "$(git rev-parse --show-toplevel)/.joker/joker.jar" ]; then
                java -jar "$(git rev-parse --show-toplevel)/.joker/joker.jar" update >/dev/null 2>&1 &
            fi
            %s
            """.formatted(MARKER_START, MARKER_END);

    private GitHookInstaller() {}

    public static void install(Path projectRoot) {
        Path hooksDir = projectRoot.resolve(".git/hooks");
        if (!Files.isDirectory(hooksDir)) return;

        installHook(hooksDir.resolve("post-commit"));
        installHook(hooksDir.resolve("post-merge"));
    }

    public static void uninstall(Path projectRoot) {
        Path hooksDir = projectRoot.resolve(".git/hooks");
        if (!Files.isDirectory(hooksDir)) return;

        removeJokerBlock(hooksDir.resolve("post-commit"));
        removeJokerBlock(hooksDir.resolve("post-merge"));
    }

    private static void installHook(Path hookFile) {
        try {
            if (Files.exists(hookFile)) {
                String content = Files.readString(hookFile);
                if (content.contains(MARKER_START)) {
                    content = replaceBlock(content);
                } else {
                    String separator = content.endsWith("\n") ? "" : "\n";
                    content = content + separator + HOOK_BLOCK;
                }
                Files.writeString(hookFile, content);
            } else {
                Files.writeString(hookFile, "#!/bin/sh\n" + HOOK_BLOCK);
            }
            makeExecutable(hookFile);
        } catch (IOException e) {
            System.err.println("Warning: could not install git hook " + hookFile.getFileName() + ": " + e.getMessage());
        }
    }

    private static void removeJokerBlock(Path hookFile) {
        if (!Files.exists(hookFile)) return;
        try {
            String content = Files.readString(hookFile);
            if (!content.contains(MARKER_START)) return;

            int start = content.indexOf(MARKER_START);
            int end = content.indexOf(MARKER_END);
            if (end < 0) return;
            end = content.indexOf('\n', end);
            if (end < 0) end = content.length();
            else end++;

            String before = content.substring(0, start);
            String after = content.substring(end);
            String result = (before + after).strip();

            if (result.equals("#!/bin/sh") || result.isEmpty()) {
                Files.delete(hookFile);
            } else {
                Files.writeString(hookFile, result + "\n");
            }
        } catch (IOException e) {
            System.err.println("Warning: could not remove joker hook from " + hookFile.getFileName() + ": " + e.getMessage());
        }
    }

    private static String replaceBlock(String content) {
        int start = content.indexOf(MARKER_START);
        int end = content.indexOf(MARKER_END);
        if (end < 0) return content + HOOK_BLOCK;
        end = content.indexOf('\n', end);
        if (end < 0) end = content.length();
        else end++;
        return content.substring(0, start) + HOOK_BLOCK + content.substring(end);
    }

    private static void makeExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows or permission issue — hook still works if git can execute it
        }
    }
}
