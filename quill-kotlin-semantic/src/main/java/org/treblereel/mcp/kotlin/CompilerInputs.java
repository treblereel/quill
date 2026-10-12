package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Content identity for a compiler snapshot, excluding the requested file/offset. */
public record CompilerInputs(String fingerprint, String moduleHome, String jdkHome, String sourceSet) {
    public static final class ChangedException extends IOException { ChangedException() { super("Captured compiler settings changed"); } }
    private record Hash(String stamp, String digest) {}
    // Only content hashes, never JAR bytes. Access is serialized by K2Analyzer.
    private static final Map<Path, Hash> ARCHIVES = boundedHashes(2048);
    private static final Map<Path, Hash> OUTPUTS = boundedHashes(8192);
    private static Map<Path, Hash> boundedHashes(int limit) {
        return new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Path, Hash> eldest) { return size() > limit; }
        };
    }

    public static synchronized CompilerInputs capture(JsonNode request) throws IOException {
        String jdk = System.getenv("JAVA_HOME");
        if (jdk == null || !Files.isRegularFile(Path.of(jdk, "release"))) jdk = System.getProperty("java.home");
        return capture(request, Path.of(jdk));
    }

    static synchronized CompilerInputs capture(JsonNode request, Path jdkDirectory) throws IOException {
        String first = request.path("sources").isEmpty() ? request.path("file").asText() : request.path("sources").get(0).asText();
        String module = request.path("module").asText(Path.of(first).toAbsolutePath().normalize().getParent().toString());
        module = Path.of(module).toAbsolutePath().normalize().toString();
        String jdk = jdkDirectory.toAbsolutePath().normalize().toString();
        MessageDigest digest = sha256();
        token(digest, "K2-2.4.21-project-settings-scripting");
        String sourceSet = request.path("source_set").asText("main");
        token(digest, module); token(digest, sourceSet);
        for (String field : List.of("languageVersion", "apiVersion", "jvmTarget", "compilerVersion", "compiler_arguments", "plugin_options", "allow_compiler_plugins", "unsupported_plugins")) {
            token(digest, field); token(digest, request.path(field).toString());
        }
        token(digest, "compiler_settings");
        for (JsonNode setting : request.path("compiler_settings_fingerprints")) {
            Path path = Path.of(setting.path("path").asText());
            String current = Files.isRegularFile(path) ? contentHash(path) : "absent";
            if (!current.equals(setting.path("sha256").asText())) throw new ChangedException();
            token(digest, path.toString()); token(digest, current);
        }
        token(digest, "compiler_build_inputs");
        for (JsonNode input : request.path("compiler_build_inputs")) {
            Path path = Path.of(input.asText()); token(digest, path.toString());
            token(digest, Files.isRegularFile(path) ? contentHash(path) : "absent");
        }
        token(digest, "plugin_classpath");
        for (JsonNode plugin : request.path("plugin_classpath")) file(digest, Path.of(plugin.asText()), true);
        token(digest, jdk);
        for (String name : List.of("release", "lib/modules", "lib/jrt-fs.jar")) {
            Path path = Path.of(jdk, name);
            token(digest, name);
            if (Files.isRegularFile(path)) token(digest, hash(path, true)); else token(digest, "absent");
        }
        token(digest, "sources");
        for (JsonNode source : request.path("sources")) file(digest, Path.of(source.asText()), false);
        token(digest, "java_roots");
        for (JsonNode root : request.path("java_roots")) tree(digest, Path.of(root.asText()), true);
        token(digest, "classpath");
        for (JsonNode entry : request.path("classpath")) {
            Path path = Path.of(entry.asText());
            if (Files.isDirectory(path)) tree(digest, path, false); else file(digest, path, true);
        }
        return new CompilerInputs(HexFormat.of().formatHex(digest.digest()), module, jdk, sourceSet);
    }

    private static void tree(MessageDigest digest, Path root, boolean javaOnly) throws IOException {
        token(digest, root.toAbsolutePath().normalize().toString());
        // Also captures file creation/deletion and symlinked source/output directories.
        try (var paths = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> !javaOnly || (p.toString().endsWith(".java") || p.toString().endsWith(".kt") || p.toString().endsWith(".kts"))).sorted().toList())
                file(digest, path, !javaOnly);
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    private static void file(MessageDigest digest, Path path, boolean memoize) throws IOException {
        token(digest, path.toAbsolutePath().normalize().toString());
        token(digest, hash(path, memoize));
    }

    private static String hash(Path path, boolean memoize) throws IOException {
        path = path.toAbsolutePath().normalize();
        // Sources are deliberately read every time: cache stamps would add filesystem
        // calls without avoiding any I/O or strengthening their content identity.
        if (!memoize) return contentHash(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        Object changedAt;
        try { changedAt = Files.getAttribute(path, "unix:ctime"); }
        catch (UnsupportedOperationException | IllegalArgumentException unsupported) { changedAt = null; }
        // Without a filesystem change-time guarantee, hash bytes each time rather than trust mtime.
        String stamp = changedAt == null ? null : attributes.fileKey() + ":" + attributes.size()
                + ":" + attributes.lastModifiedTime() + ":" + changedAt;
        // Class-heavy output directories must not evict expensive dependency/JDK image hashes.
        var hashes = path.toString().endsWith(".jar") || path.getFileName().toString().equals("modules")
                ? ARCHIVES : OUTPUTS;
        Hash cached = memoize && stamp != null ? hashes.get(path) : null;
        if (cached != null && cached.stamp().equals(stamp)) return cached.digest();
        String result = contentHash(path);
        // A raced write must not publish a hash under the post-write attributes.
        if (stamp != null) hashes.put(path, new Hash(stamp, result));
        return result;
    }

    private static String contentHash(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void token(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
