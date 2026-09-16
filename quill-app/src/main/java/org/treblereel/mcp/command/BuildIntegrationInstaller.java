package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.treblereel.mcp.QuillTopCommand;
import org.treblereel.mcp.core.BuildSystem;

/** Installs and removes the build-success notification owned by Quill. */
final class BuildIntegrationInstaller {

    private static final String START = "quill:build-integration:start";
    private static final String END = "quill:build-integration:end";
    private static final String MAVEN_START = "    <!-- " + START + " -->";
    private static final String MAVEN_END = "    <!-- " + END + " -->";
    private static final String GRADLE_START = "// " + START;
    private static final String GRADLE_END = "// " + END;
    private static final String GRADLE_CREATED = "// quill:build-integration:created-settings";

    private BuildIntegrationInstaller() {}

    enum Result { INSTALLED, UPDATED, UNCHANGED, REMOVED, NOT_FOUND, FAILED }

    static Result install(Path root) {
        try {
            return switch (BuildSystem.detect(root)) {
                case MAVEN -> installMaven(root);
                case GRADLE -> installGradle(root);
            };
        } catch (Exception e) {
            System.err.println("[quill] Could not install build integration: " + e.getMessage());
            return Result.FAILED;
        }
    }

    static Result uninstall(Path root) {
        try {
            boolean removed = removeManagedBlock(root.resolve(".mvn/extensions.xml"),
                    MAVEN_START, MAVEN_END, true);
            Path settings = gradleSettings(root);
            if (settings != null) {
                removed |= removeGradleManagedBlock(settings);
            }
            return removed ? Result.REMOVED : Result.NOT_FOUND;
        } catch (IOException e) {
            System.err.println("[quill] Could not remove build integration: " + e.getMessage());
            return Result.FAILED;
        }
    }

    private static Result installMaven(Path root) throws IOException {
        Path file = root.resolve(".mvn/extensions.xml");
        String version = QuillTopCommand.version();
        String block = MAVEN_START + "\n"
                + "    <extension>\n"
                + "        <groupId>org.treblereel.mcp</groupId>\n"
                + "        <artifactId>quill-maven-extension</artifactId>\n"
                + "        <version>" + xml(version) + "</version>\n"
                + "    </extension>\n"
                + MAVEN_END;
        String content;
        if (Files.isRegularFile(file)) {
            content = Files.readString(file);
            String updated = replaceOrInsertXmlBlock(content, block);
            if (updated.equals(content)) return Result.UNCHANGED;
            atomicWrite(file, updated);
            return content.contains(MAVEN_START) ? Result.UPDATED : Result.INSTALLED;
        }
        content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<extensions>\n" + block + "\n</extensions>\n";
        atomicWrite(file, content);
        return Result.INSTALLED;
    }

    private static Result installGradle(Path root) throws IOException {
        Path settings = gradleSettings(root);
        if (settings == null) {
            boolean kotlin = Files.isRegularFile(root.resolve("build.gradle.kts"));
            settings = root.resolve(kotlin ? "settings.gradle.kts" : "settings.gradle");
        }
        boolean settingsExisted = Files.isRegularFile(settings);
        boolean kotlin = settings.getFileName().toString().endsWith(".kts");
        String block = kotlin ? kotlinGradleBlock() : groovyGradleBlock();
        if (!settingsExisted) block = GRADLE_CREATED + "\n" + block;
        String content = settingsExisted ? Files.readString(settings) : "";
        String updated = replaceOrPrepend(content, GRADLE_START, GRADLE_END, block);
        if (updated.equals(content)) return Result.UNCHANGED;
        atomicWrite(settings, updated);
        return content.contains(GRADLE_START) ? Result.UPDATED : Result.INSTALLED;
    }

    private static String groovyGradleBlock() {
        return GRADLE_START + "\n"
                + "gradle.buildFinished { result ->\n"
                + "    if (result.failure == null && System.getProperty('quill.internal') != 'true') {\n"
                + "        def dir = new File(settingsDir, '.quill/build-events')\n"
                + "        dir.mkdirs()\n"
                + "        def event = new File(dir, 'gradle-' + System.currentTimeMillis() + '-' + UUID.randomUUID() + '.json')\n"
                + "        def temporary = File.createTempFile('.gradle-', '.tmp', dir)\n"
                + "        temporary.text = '{\"version\":1,\"buildTool\":\"gradle\",\"successful\":true,\"finishedAt\":' + System.currentTimeMillis() + '}'\n"
                + "        try {\n"
                + "            java.nio.file.Files.move(temporary.toPath(), event.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)\n"
                + "        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {\n"
                + "            java.nio.file.Files.move(temporary.toPath(), event.toPath())\n"
                + "        }\n"
                + "    }\n"
                + "}\n" + GRADLE_END;
    }

    private static String kotlinGradleBlock() {
        return GRADLE_START + "\n"
                + "gradle.buildFinished {\n"
                + "    if (failure == null && System.getProperty(\"quill.internal\") != \"true\") {\n"
                + "        val dir = file(\".quill/build-events\").apply { mkdirs() }\n"
                + "        val now = System.currentTimeMillis()\n"
                + "        val event = dir.resolve(\"gradle-$now-${java.util.UUID.randomUUID()}.json\")\n"
                + "        val temporary = kotlin.io.path.createTempFile(dir.toPath(), \".gradle-\", \".tmp\")\n"
                + "        java.nio.file.Files.writeString(temporary, \"{\\\"version\\\":1,\\\"buildTool\\\":\\\"gradle\\\",\\\"successful\\\":true,\\\"finishedAt\\\":$now}\")\n"
                + "        try {\n"
                + "            java.nio.file.Files.move(temporary, event.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)\n"
                + "        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {\n"
                + "            java.nio.file.Files.move(temporary, event.toPath())\n"
                + "        }\n"
                + "    }\n"
                + "}\n" + GRADLE_END;
    }

    private static Path gradleSettings(Path root) {
        Path kotlin = root.resolve("settings.gradle.kts");
        if (Files.isRegularFile(kotlin)) return kotlin;
        Path groovy = root.resolve("settings.gradle");
        if (Files.isRegularFile(groovy)) return groovy;
        return null;
    }

    private static String replaceOrInsertXmlBlock(String content, String block) throws IOException {
        if (content.contains(MAVEN_START)) {
            return replaceBlock(content, MAVEN_START, MAVEN_END, block);
        }
        int closing = content.lastIndexOf("</extensions>");
        if (closing < 0) throw new IOException("invalid .mvn/extensions.xml: missing </extensions>");
        return content.substring(0, closing) + block + "\n" + content.substring(closing);
    }

    private static String replaceOrPrepend(
            String content, String start, String end, String block) throws IOException {
        if (content.contains(start)) return replaceBlock(content, start, end, block);
        return block + "\n" + content;
    }

    private static String replaceBlock(
            String content, String start, String end, String block) throws IOException {
        int from = content.indexOf(start);
        int endStart = content.indexOf(end, from + start.length());
        if (from < 0 || endStart < 0) throw new IOException("unterminated Quill-managed block");
        int to = endStart + end.length();
        return content.substring(0, from) + block + content.substring(to);
    }

    private static boolean removeManagedBlock(
            Path file, String start, String end, boolean deleteEmptyMavenFile) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        String content = Files.readString(file);
        int from = content.indexOf(start);
        if (from < 0) return false;
        int endStart = content.indexOf(end, from + start.length());
        if (endStart < 0) throw new IOException("unterminated Quill-managed block in " + file);
        int to = endStart + end.length();
        if (to < content.length() && content.charAt(to) == '\r') to++;
        if (to < content.length() && content.charAt(to) == '\n') to++;
        String updated = content.substring(0, from) + content.substring(to);
        if (deleteEmptyMavenFile && updated.replaceAll("(?s)<\\?xml.*?\\?>", "")
                .replace("<extensions>", "").replace("</extensions>", "").isBlank()) {
            Files.delete(file);
            Path parent = file.getParent();
            if (parent != null) {
                try (var files = Files.list(parent)) {
                    if (files.findAny().isEmpty()) Files.delete(parent);
                }
            }
        } else {
            atomicWrite(file, updated);
        }
        return true;
    }

    private static boolean removeGradleManagedBlock(Path file) throws IOException {
        String content = Files.readString(file);
        boolean created = content.startsWith(GRADLE_CREATED + "\n")
                || content.startsWith(GRADLE_CREATED + "\r\n");
        boolean removed = removeManagedBlock(file, GRADLE_START, GRADLE_END, false);
        if (!removed) return false;
        String updated = Files.readString(file).replace(GRADLE_CREATED + "\r\n", "")
                .replace(GRADLE_CREATED + "\n", "");
        if (created && updated.isBlank()) {
            Files.delete(file);
        } else if (!updated.equals(Files.readString(file))) {
            atomicWrite(file, updated);
        }
        return true;
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Path temp = Files.createTempFile(file.getParent(), ".quill-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
