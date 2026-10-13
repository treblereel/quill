package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;

/** K2 analysis in a bounded JVM child process; runtime preparation belongs to init. */
final class KotlinSemanticQueries {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static KotlinWarmupCoordinator warmup;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(KotlinSemanticQueries::closeWarmup, "quill-k2-watch-shutdown"));
    }
    private static synchronized KotlinWarmupCoordinator warmup() {
        if (warmup == null) warmup = new KotlinWarmupCoordinator(
                org.treblereel.mcp.kotlin.KotlinWorkerClient.shared()::query, java.time.Duration.ofMillis(150));
        return warmup;
    }
    static synchronized void closeWarmup() {
        if (warmup != null) { warmup.close(); warmup = null; }
    }

    static Runnable startWarmup(ProjectRegistry registry) {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> {
            try {
                var projects = registry.initialized();
                synchronized (KotlinSemanticQueries.class) {
                    // Closing MCP prevents a late registry resolution from reopening the worker.
                    if (!cancelled.get()) prewarm(projects);
                }
            } catch (RuntimeException unavailable) { /* Foreground retains normal preparation/fallback. */ }
        }, "quill-k2-startup");
        thread.setDaemon(true); thread.start();
        return () -> { cancelled.set(true); thread.interrupt(); };
    }

    static synchronized boolean prewarm(List<ProjectRegistry.ProjectEntry> projects) {
        if (warmup != null) return false;
        ProjectRegistry.ProjectEntry selected = null; Path file = null;
        for (var project : projects) {
            List<String> modules = project.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT MIN(repository_path) FROM files
                    WHERE kind = 'kotlin' AND lifecycle = 'current' AND source_set = 'main'
                      AND repository_path LIKE '%.kt'
                    GROUP BY module LIMIT 2
                    """).mapTo(String.class).list());
            if (modules.isEmpty()) continue;
            if (selected != null || modules.size() != 1) return false;
            selected = project; file = project.root().resolve(modules.getFirst()).toAbsolutePath().normalize();
        }
        if (selected == null || !Files.isRegularFile(file)) return false;
        final var project = selected; final Path anchor = file;
        JsonNode initial = prepare(project.jdbi(), project.root(), anchor, -1, true);
        if (!initial.has("sources") || org.treblereel.mcp.kotlin.KotlinRuntime.available().isEmpty()) return false;
        return warmup().prewarm(key(project.root(), initial),
                () -> prepare(project.jdbi(), project.root(), anchor, -1, true));
    }

    private static String key(Path root, JsonNode request) {
        return root.toAbsolutePath().normalize() + ":" + request.path("module").asText()
                + ":" + request.path("source_set").asText();
    }

    static JsonNode resolve(Jdbi db, Path root, Path file, int offset) {
        JsonNode initial = prepare(db, root, file, offset, false);
        if (!initial.has("sources")) return initial;
        return warmup().query(key(root, initial),
                () -> prepare(db, root, file, offset, false), () -> prepare(db, root, file, -1, true));
    }

    private static JsonNode prepare(Jdbi db, Path root, Path file, int offset, boolean warming) {
        if (file.getFileName().toString().endsWith(".gradle.kts") || file.getFileName().toString().equals("settings.gradle.kts")) return status("script_definition_missing");
        try {
            root = root.toAbsolutePath().normalize();
            file = file.toAbsolutePath().normalize();
            if (!file.startsWith(root) || Files.exists(file)
                    && !file.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
            Path realRoot = root.toRealPath();
            file = Files.exists(file) ? file.toRealPath() : realRoot.resolve(root.relativize(file));
            root = realRoot;
            var indexedFile = IndexReader.findFileByPath(db, root.relativize(file).toString().replace('\\', '/'));
            Path module = file.getParent();
            String sourceSet = null;
            if (indexedFile.isPresent()) {
                var context = indexedFile.get();
                sourceSet = context.sourceSet();
                if (sourceSet == null || !org.treblereel.mcp.core.GradleProjectDiscovery.validSourceSet(sourceSet))
                    return status("source_set_unknown");
                if (context.module() == null) return status("module_unknown");
                module = root.resolve(context.module()).normalize();
            } else {
                while (module != null && module.startsWith(root) && !Files.isRegularFile(module.resolve("pom.xml"))
                        && !BuildSystem.hasGradleMarker(module)) module = module.getParent();
            }
            if (module == null || !module.startsWith(root)) return status("module_unknown");
            if (sourceSet == null) sourceSet = module.relativize(file).toString().replace('\\', '/').startsWith("src/test/") ? "test" : "main";
            boolean test = sourceSet.equals("test");
            boolean custom = !sourceSet.equals("main") && !test;
            BuildSystem system = BuildSystem.hasGradleMarker(module) || !Files.isRegularFile(module.resolve("pom.xml"))
                    && BuildSystem.hasGradleMarker(root) ? BuildSystem.GRADLE : BuildSystem.detect(module);
            Path settingsOutput = system.classpathFile(module).getParent();
            Path captured = test ? system.testClasspathFile(module) : system.classpathFile(module);
            var request = JSON.createObjectNode();
            request.put("file", file.toString()); request.put("offset", offset);
            request.put("module", module.toString()); request.put("source_set", sourceSet);
            KotlinCompilerSettings.apply(module, settingsOutput, sourceSet, request);
            boolean capturedInputs = request.path("captured_analysis_inputs").asBoolean();
            if (custom && !capturedInputs) return status(request.path("compiler_configuration_error").asText("source_set_inputs_missing"));
            if (capturedInputs) captured = settingsOutput.resolve("quill-kotlin-" + sourceSet + ".properties");
            if (!Files.isRegularFile(captured)) return status("classpath_missing");
            request.put("captured_classpath", captured.toString());
            var sources = request.putArray("sources"); var javaRoots = request.putArray("java_roots");
            LinkedHashSet<Path> kotlinFiles = new LinkedHashSet<>();
            var roots = new LinkedHashSet<Path>();
            if (capturedInputs) {
                for (JsonNode path : request.path("captured_sourceRoot")) roots.add(canonical(Path.of(path.asText())));
                for (JsonNode path : request.path("captured_javaRoot")) {
                    Path javaRoot = canonical(Path.of(path.asText()));
                    if (Files.exists(javaRoot) && !javaRoot.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
                    if (Files.isDirectory(javaRoot)) javaRoots.add(javaRoot.toString());
                }
                if (roots.stream().noneMatch(file::startsWith)) return status("source_not_in_source_set");
                var watchedRoots = request.putArray("source_roots");
                roots.forEach(path -> watchedRoots.add(path.toString()));
            } else {
                for (String set : test ? List.of("main", "test") : List.of("main")) {
                    Path source = module.resolve("src/" + set);
                    roots.add(source);
                    if (Files.isDirectory(source.resolve("java"))) javaRoots.add(source.resolve("java").toString());
                }
            }
            for (Path source : roots) {
                if (!Files.isDirectory(source)) continue;
                if (!source.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
                try (var paths = Files.walk(source)) {
                    for (Path path : paths.filter(value -> value.toString().endsWith(".kt") && Files.isRegularFile(value)).toList()) {
                        if (!path.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
                        kotlinFiles.add(path.toAbsolutePath().normalize());
                        if (kotlinFiles.size() > 5000) return status("source_limit");
                    }
                }
            }
            if (!warming || Files.isRegularFile(file)) kotlinFiles.add(file);
            if (kotlinFiles.isEmpty()) return status("unavailable");
            if (warming) request.put("file", kotlinFiles.iterator().next().toString());
            kotlinFiles.stream().sorted().forEach(value -> sources.add(value.toString()));
            var classpath = request.putArray("classpath");
            var entries = new LinkedHashSet<String>();
            var capturedEntries = new java.util.ArrayList<String>();
            if (capturedInputs) request.path("captured_compileClasspath").forEach(value -> capturedEntries.add(value.asText()));
            else capturedEntries.addAll(List.of(Files.readString(captured).strip().split(java.util.regex.Pattern.quote(File.pathSeparator))));
            for (String entry : capturedEntries) {
                if (entry.isBlank()) continue;
                Path path = module.resolve(entry).normalize();
                if (!Files.exists(path)) {
                    boolean optional = capturedInputs && optionalOutput(request, path);
                    if (optional) continue;
                    return status("classpath_missing");
                }
                entries.add(path.toAbsolutePath().toString());
            }
            var friends = request.withArray("friend_paths");
            for (int i = friends.size() - 1; i >= 0; i--) {
                Path path = Path.of(friends.get(i).asText());
                if (!Files.exists(path)) {
                    if (optionalOutput(request, path) || java.util.stream.StreamSupport.stream(request.path("optional_friend_paths").spliterator(), false)
                            .anyMatch(value -> Path.of(value.asText()).normalize().equals(path))) friends.remove(i);
                    else return status("classpath_missing");
                }
            }
            if (!capturedInputs) {
                String relative = root.relativize(module).toString().replace('\\', '/');
                String applicationModule = relative.isEmpty() ? "." : relative;
                var modules = new LinkedHashSet<String>(); modules.add(applicationModule);
                IndexReader.findModuleClasspath(db, applicationModule).forEach(value -> modules.add(value.visibleModule()));
                for (String visible : modules) {
                    Path directory = root.resolve(visible).normalize();
                    if (!directory.startsWith(root)) return status("module_unknown");
                    for (String output : List.of("target/classes", "build/classes/java/main", "build/classes/kotlin/main"))
                        if (Files.isDirectory(directory.resolve(output))) entries.add(directory.resolve(output).toString());
                }
                if (test && Files.isDirectory(module.resolve("target/test-classes"))) entries.add(module.resolve("target/test-classes").toString());
            }
            entries.forEach(classpath::add);
            return request;
        } catch (Exception failure) { return status("unavailable"); }
    }

    private static boolean optionalOutput(JsonNode request, Path path) {
        return java.util.stream.StreamSupport.stream(request.path("optional_outputs").spliterator(), false)
                .anyMatch(value -> Path.of(value.asText()).normalize().equals(path));
    }

    private static Path canonical(Path path) throws java.io.IOException {
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor)) return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    private static JsonNode status(String status) { return JSON.createObjectNode().put("status", status); }
}
