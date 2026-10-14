package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;

/** Compiler-confirmed Java navigation from successful build capture; never executes a build. */
final class JavaSemanticQueries {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JavaWorkerClient WORKER = new JavaWorkerClient();
    private static KotlinWarmupCoordinator coordinator;
    static { Runtime.getRuntime().addShutdownHook(new Thread(JavaSemanticQueries::close, "quill-java-shutdown")); }
    private static synchronized KotlinWarmupCoordinator coordinator() {
        if (coordinator == null) coordinator = new KotlinWarmupCoordinator(JavaSemanticQueries::queryWorker, Duration.ofMillis(150));
        return coordinator;
    }
    static synchronized void close() { if (coordinator != null) { coordinator.close(); coordinator = null; } WORKER.close(); }
    static Runnable startWarmup(ProjectRegistry registry) {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                ProjectRegistry.ProjectEntry selected = null; Path anchor = null;
                for (var project : registry.initialized()) {
                    List<String> candidates = project.jdbi().withHandle(handle -> handle.createQuery("""
                            SELECT MIN(repository_path) FROM files WHERE kind = 'java' AND lifecycle = 'current'
                            AND source_set = 'main' AND repository_path LIKE '%.java' GROUP BY module LIMIT 2
                            """).mapTo(String.class).list());
                    if (candidates.isEmpty()) continue;
                    if (selected != null || candidates.size() != 1) return;
                    selected = project; anchor = project.root().resolve(candidates.getFirst());
                }
                if (selected == null) return;
                Path root = selected.root(), file = anchor;
                Jdbi db = selected.jdbi();
                JsonNode request = prepare(db, root, file, -1);
                if (!request.has("sources")) return;
                synchronized (JavaSemanticQueries.class) {
                    if (!cancelled.get()) coordinator().prewarm(key(root, request),
                            () -> prepare(db, root, file, -1));
                }
            } catch (RuntimeException unavailable) { /* Foreground retains preparation and fallback. */ }
        }, "quill-java-startup");
        thread.setDaemon(true); thread.start();
        return () -> { cancelled.set(true); thread.interrupt(); };
    }
    static JsonNode resolve(Jdbi db, Path root, Path file, int offset) {
        JsonNode request = prepare(db, root, file, offset);
        if (!request.has("sources")) return request;
        String key = key(root, request);
        return coordinator().query(key, () -> prepare(db, root, file, offset), () -> prepare(db, root, file, -1));
    }
    private static JsonNode queryWorker(JsonNode request) {
        JsonNode result = WORKER.query(request);
        JsonNode current = prepare(Path.of(request.path("project_root").asText()), Path.of(request.path("file").asText()),
                request.path("offset").asInt(), Path.of(request.path("module").asText()));
        if (!current.equals(request)) return status("inputs_changed");
        return result;
    }
    private static String key(Path root, JsonNode request) {
        return org.treblereel.mcp.core.ProjectIdentity.canonical(root) + ":" + request.path("project_id").asText()
                + ":" + request.path("module").asText() + ":" + request.path("source_set").asText();
    }

    static JsonNode prepare(Jdbi db, Path root, Path file, int offset) {
        Path module = null;
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize(), normalizedFile = file.toAbsolutePath().normalize();
            if (normalizedFile.startsWith(normalizedRoot)) {
                var indexed = IndexReader.findFileByPath(db, normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/'));
                if (indexed.isPresent() && indexed.get().module() != null)
                    module = normalizedRoot.resolve(indexed.get().module()).normalize();
            }
        } catch (RuntimeException unavailable) { /* Capture discovery remains available without index context. */ }
        return prepare(root, file, offset, module);
    }
    static JsonNode prepare(Path root, Path file, int offset) { return prepare(root, file, offset, null); }
    private static JsonNode prepare(Path root, Path file, int offset, Path moduleHint) {
        try {
            root = root.toAbsolutePath().normalize(); file = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) return status("source_missing");
            if (!file.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
            root = root.toRealPath(); file = file.toRealPath();
            Path module = moduleHint == null ? file.getParent() : canonical(moduleHint);
            if (moduleHint == null) while (module != null && module.startsWith(root) && !Files.isRegularFile(module.resolve("pom.xml"))
                    && !BuildSystem.hasGradleMarker(module) && !hasCapture(module)) module = module.getParent();
            if (module == null || !module.startsWith(root)) return status("module_unknown");
            if (!file.startsWith(module)) return status("module_unknown");
            Path output = module.resolve(Files.isRegularFile(module.resolve("pom.xml")) ? "target" : "build");
            if (!Files.isDirectory(output)) return status("compiler_settings_missing");
            // Choose the capture whose configured source root is most specific. Custom Gradle sets are included.
            Path selected = null; Properties settings = null; int specificity = -1;
            try (var captures = Files.newDirectoryStream(output, "quill-java-*.properties")) {
                int count = 0;
                for (Path capture : captures) {
                    if (++count > 128) return status("compiler_settings_limit");
                    Properties values = load(capture);
                    for (Path source : paths(values, "sourceRoot", module)) if (file.startsWith(source)) {
                        int width = source.getNameCount();
                        if (width > specificity) { selected = capture; settings = values; specificity = width; }
                        else if (width == specificity && !capture.equals(selected)) return status("source_set_ambiguous");
                    }
                }
            }
            if (settings == null) return status("source_set_unknown");
            if (!"1".equals(settings.getProperty("compilerSettingsProtocol"))) return status("compiler_settings_protocol_unsupported");
            if (!Set.of("successful_mojo", "successful_task").contains(settings.getProperty("evidence", ""))) return status("compiler_not_executed");
            if (!"javac".equals(settings.getProperty("compilerId", "javac"))) return status("compiler_unsupported");
            if (settings.containsKey("configurationError")) return status("compiler_settings_unavailable");
            if (Boolean.parseBoolean(settings.getProperty("enablePreview", "false"))) return status("preview_unsupported");
            if (!"UTF-8".equalsIgnoreCase(settings.getProperty("encoding", "UTF-8"))) return status("encoding_unsupported");
            if (settings.containsKey("legacyCompilerArgument")) return status("compiler_arguments_unsupported");
            for (String argument : values(settings, "argument"))
                if (!argument.equals("-parameters") && !argument.matches("-g(?::[a-z,]+)?") && !argument.matches("-Xlint(?::[a-z,\\-]+)?")
                        && !argument.equals("-Werror") && !argument.startsWith("-A")) return status("compiler_arguments_unsupported");
            String release = settings.getProperty("release");
            String source = settings.getProperty("source"), target = settings.getProperty("target");
            if (release != null && !release.matches("[0-9]+") || release == null
                    && (source == null || target == null || !source.matches("(?:1\\.)?[0-9]+") || !target.matches("(?:1\\.)?[0-9]+")))
                return status("release_settings_unsupported");
            Path home = canonical(Path.of(settings.getProperty("jdkHome", "")));
            if (!Files.isRegularFile(home.resolve("release")) || !Files.isExecutable(home.resolve("bin/java"))
                    || !Files.isExecutable(home.resolve("bin/javac"))) return status("jdk_unavailable");
            Properties jdk = load(home.resolve("release"));
            String version = jdk.getProperty("JAVA_VERSION", "").replace("\"", "");
            int major = Integer.parseInt(version.split("[.\\-+]")[0]);
            if (major < 17 || release != null && Integer.parseInt(release) > major) return status("jdk_unsupported");
            if (!version.split("\\+")[0].equals(settings.getProperty("jdkVersion", "").split("\\+")[0])) return status("jdk_changed");
            var identity = org.treblereel.mcp.core.ProjectIdentity.resolve(root);
            var request = JSON.createObjectNode().put("project_id", identity.projectId())
                    .put("worktree_id", identity.worktreeId()).put("file", file.toString()).put("offset", offset)
                    .put("project_root", root.toString()).put("module", module.toString())
                    .put("source_set", settings.getProperty("sourceSet", "unknown")).put("jdk_home", home.toString())
                    .put("captured_classpath", selected.toString()).put("settings_sha256", hash(selected));
            if (release != null) request.put("release", release);
            else { request.put("source", source); request.put("target", target); }
            var watched = request.putArray("compiler_settings_files").add(selected.toString()).add(home.resolve("release").toString());
            int buildInputs = count(settings, "buildInput");
            if (buildInputs == 0) return status("compiler_build_inputs_missing");
            for (int i = 0; i < buildInputs; i++) {
                Path input = canonical(Path.of(required(settings, "buildInput.path." + i))); watched.add(input.toString());
                if (!hash(input).equals(required(settings, "buildInput.sha256." + i))) return status("compiler_settings_stale");
            }
            var roots = new LinkedHashSet<>(paths(settings, "sourceRoot", module));
            if (settings.containsKey("generatedSourcesDirectory")) roots.add(canonical(module.resolve(settings.getProperty("generatedSourcesDirectory"))));
            var sources = request.putArray("sources"); var javaRoots = request.putArray("java_roots");
            Set<Path> files = new TreeSet<>();
            for (Path sourceRoot : roots) {
                if (!sourceRoot.startsWith(root)) return status("source_outside_root");
                javaRoots.add(sourceRoot.toString());
                if (!Files.isDirectory(sourceRoot)) continue;
                try (var stream = Files.walk(sourceRoot)) {
                    for (Path path : stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                        if (!path.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
                        if (path.getFileName().toString().equals("module-info.java")) return status("module_path_unsupported");
                        files.add(path);
                        if (files.size() > 5000) return status("source_limit");
                    }
                }
            }
            if (!files.contains(file)) return status("source_not_in_module");
            files.forEach(p -> sources.add(p.toString()));
            var classpath = request.putArray("classpath");
            for (Path entry : paths(settings, "compileClasspath", module)) {
                if (!Files.exists(entry)) return status("classpath_missing");
                classpath.add(entry.toString());
            }
            return request;
        } catch (Exception unavailable) { return status("compiler_settings_unavailable"); }
    }
    private static boolean hasCapture(Path module) throws IOException {
        Path output = module.resolve("build");
        if (!Files.isDirectory(output)) return false;
        try (var captures = Files.newDirectoryStream(output, "quill-java-*.properties")) {
            return captures.iterator().hasNext();
        }
    }
    private static Properties load(Path file) throws IOException {
        var values = new Properties(); try (var reader = Files.newBufferedReader(file)) { values.load(reader); } return values;
    }
    private static int count(Properties settings, String prefix) throws IOException {
        int count = Integer.parseInt(required(settings, prefix + ".count"));
        if (count < 0 || count > 8192) throw new IOException("Capture input limit"); return count;
    }
    private static String required(Properties settings, String key) throws IOException {
        String value = settings.getProperty(key); if (value == null) throw new IOException("Incomplete capture"); return value;
    }
    private static List<String> values(Properties settings, String prefix) throws IOException {
        List<String> result = new ArrayList<>(); for (int i = 0; i < count(settings, prefix); i++) result.add(required(settings, prefix + "." + i)); return result;
    }
    private static List<Path> paths(Properties settings, String prefix, Path module) throws IOException {
        List<Path> result = new ArrayList<>();
        for (String value : values(settings, prefix)) result.add(canonical(module.resolve(value)));
        return result;
    }
    private static Path canonical(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(), parent = normalized;
        while (parent != null && !Files.exists(parent)) parent = parent.getParent();
        if (parent == null) return normalized;
        return parent.toRealPath().resolve(parent.relativize(normalized)).normalize();
    }
    static String hash(Path file) throws Exception {
        if (!Files.isRegularFile(file)) return "absent";
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static ObjectNode status(String value) { return JSON.createObjectNode().put("status", value).put("backend", "javac"); }
}
