package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

public final class GitHookInstaller {

    private static final String LEGACY_MARKER_START = "# --- quill-start ---";
    private static final String LEGACY_MARKER_END = "# --- quill-end ---";

    public record Launcher(List<String> command, Path requiredPath) {
        public Launcher {
            command = List.copyOf(command);
            if (command.isEmpty()) throw new IllegalArgumentException("Launcher command is empty");
        }
    }

    private static String hookBlock(String projectDir, String projectId, Launcher launcher) {
        String quotedProject = shellQuote(projectDir);
        return """
                %s
                %s%s
                """.formatted(markerStart(projectId),
                        launcherBlock(launcher, quotedProject, ""),
                        markerEnd(projectId));
    }

    private static String postCheckoutBlock(String projectDir, String projectId, Launcher launcher) {
        String quotedProject = shellQuote(projectDir);
        return """
                %s
                # Only re-index on branch checkout ($3=1), not file checkout ($3=0)
                if [ "$3" = "1" ]; then
                %s
                fi
                %s
                """.formatted(markerStart(projectId),
                        launcherBlock(launcher, quotedProject, "    ").stripTrailing(),
                        markerEnd(projectId));
    }

    private static String launcherBlock(
            Launcher launcher, String quotedProject, String indent) {
        String fallback = indent + "if command -v quill >/dev/null 2>&1; then\n"
                + indent + "    quill update --compile --project " + quotedProject
                + " >/dev/null 2>&1 &\n"
                + indent + "fi\n";
        if (launcher == null) return fallback;
        String command = launcher.command().stream()
                .map(GitHookInstaller::shellQuote)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
        return indent + "if [ -e " + shellQuote(launcher.requiredPath().toString()) + " ]; then\n"
                + indent + "    " + command + " update --compile --project " + quotedProject
                + " >/dev/null 2>&1 &\n"
                + indent + "elif command -v quill >/dev/null 2>&1; then\n"
                + indent + "    quill update --compile --project " + quotedProject
                + " >/dev/null 2>&1 &\n"
                + indent + "fi\n";
    }

    private GitHookInstaller() {}

    public static void install(Path projectRoot) {
        install(projectRoot, null);
    }

    public static void install(Path projectRoot, Launcher launcher) {
        Path hooksDir = resolveHooksDir(projectRoot);
        if (hooksDir == null || !Files.isDirectory(hooksDir)) return;

        String projectDir = projectRoot.toAbsolutePath().normalize().toString();
        String projectId = projectId(projectDir);
        installHook(hooksDir.resolve("post-commit"), hookBlock(projectDir, projectId, launcher),
                projectDir, projectId);
        installHook(hooksDir.resolve("post-merge"), hookBlock(projectDir, projectId, launcher),
                projectDir, projectId);
        installHook(hooksDir.resolve("post-checkout"), postCheckoutBlock(projectDir, projectId, launcher),
                projectDir, projectId);
    }

    public static void uninstall(Path projectRoot) {
        Path hooksDir = resolveHooksDir(projectRoot);
        if (hooksDir == null || !Files.isDirectory(hooksDir)) return;

        String projectDir = projectRoot.toAbsolutePath().normalize().toString();
        String projectId = projectId(projectDir);
        removeQuillBlock(hooksDir.resolve("post-commit"), projectDir, projectId);
        removeQuillBlock(hooksDir.resolve("post-merge"), projectDir, projectId);
        removeQuillBlock(hooksDir.resolve("post-checkout"), projectDir, projectId);
    }

    private static Path resolveHooksDir(Path projectRoot) {
        Path gitDir = GitAnalyzer.findGitDir(projectRoot);
        if (gitDir == null) return null;
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .build()) {
            String hooksPath = repo.getConfig().getString("core", null, "hooksPath");
            if (hooksPath != null) {
                Path resolved = Path.of(hooksPath);
                if (!resolved.isAbsolute()) {
                    resolved = repo.getWorkTree().toPath().resolve(resolved);
                }
                return resolved;
            }
            return repo.getCommonDirectory().toPath().resolve("hooks");
        } catch (IOException e) {
            return null;
        }
    }

    private static void installHook(
            Path hookFile, String block, String projectDir, String projectId) {
        try {
            if (Files.exists(hookFile)) {
                String content = Files.readString(hookFile);
                String startMarker = markerStart(projectId);
                if (content.contains(startMarker)) {
                    content = replaceBlock(content, startMarker, markerEnd(projectId), block);
                } else if (containsLegacyBlockForProject(content, projectDir)) {
                    content = replaceBlock(
                            content, LEGACY_MARKER_START, LEGACY_MARKER_END, block);
                } else {
                    String separator = content.endsWith("\n") ? "" : "\n";
                    content = content + separator + block;
                }
                Files.writeString(hookFile, content);
            } else {
                Files.writeString(hookFile, "#!/bin/sh\n" + block);
            }
            makeExecutable(hookFile);
        } catch (IOException e) {
            System.err.println("Warning: could not install git hook " + hookFile.getFileName() + ": " + e.getMessage());
        }
    }

    private static void removeQuillBlock(Path hookFile, String projectDir, String projectId) {
        if (!Files.exists(hookFile)) return;
        try {
            String content = Files.readString(hookFile);
            String startMarker = markerStart(projectId);
            String endMarker = markerEnd(projectId);
            if (!content.contains(startMarker)) {
                if (!containsLegacyBlockForProject(content, projectDir)) return;
                startMarker = LEGACY_MARKER_START;
                endMarker = LEGACY_MARKER_END;
            }

            String result = removeBlock(content, startMarker, endMarker);
            if (result == null) return;

            if (result.strip().equals("#!/bin/sh") || result.isBlank()) {
                Files.delete(hookFile);
            } else {
                Files.writeString(hookFile, result);
            }
        } catch (IOException e) {
            System.err.println("Warning: could not remove quill hook from " + hookFile.getFileName() + ": " + e.getMessage());
        }
    }

    private static String replaceBlock(
            String content, String startMarker, String endMarker, String block) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start);
        if (end < 0) return content + block;
        end = content.indexOf('\n', end);
        if (end < 0) end = content.length();
        else end++;
        return content.substring(0, start) + block + content.substring(end);
    }

    private static String removeBlock(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        if (start < 0) return null;
        int end = content.indexOf(endMarker, start);
        if (end < 0) return null;
        end = content.indexOf('\n', end);
        if (end < 0) end = content.length();
        else end++;
        return content.substring(0, start) + content.substring(end);
    }

    private static boolean containsLegacyBlockForProject(String content, String projectDir) {
        int start = content.indexOf(LEGACY_MARKER_START);
        if (start < 0) return false;
        int end = content.indexOf(LEGACY_MARKER_END, start);
        return end >= 0 && content.substring(start, end).contains(projectDir);
    }

    private static String markerStart(String projectId) {
        return "# --- quill-start:" + projectId + " ---";
    }

    private static String markerEnd(String projectId) {
        return "# --- quill-end:" + projectId + " ---";
    }

    private static String projectId(String projectDir) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(projectDir.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void makeExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException | UnsupportedOperationException e) {
            // Windows or permission issue — hook still works if git can execute it
        }
    }
}
