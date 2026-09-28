package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.DeclaredDependencyDiscovery;
import org.treblereel.mcp.core.ProjectCoordinatesDiscovery;
import org.treblereel.mcp.diagnostics.DebugTrace;

/** Reads the dependency artifacts captured by Quill's Maven or Gradle classpath discovery. */
final class ProjectDependencyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROJECT_CACHES = 8;
    private static final ConcurrentHashMap<Path, CachedMetadata> METADATA =
            new ConcurrentHashMap<>();

    String getProjectDependencies(Jdbi jdbi, Path root, String module, String sourceSet, String query,
            String directness, String group, String artifactName, String scope,
            int limit, int offset) {
        DebugTrace.Trace trace = DebugTrace.start("get_project_dependencies");
        try {
            trace.event("query_parameters", Map.of(
                    "project_root", root.toAbsolutePath().normalize().toString(),
                    "module", normalizedOrAll(module),
                    "source_set", normalizedOrAll(sourceSet),
                    "query", normalizedOrAll(query),
                    "scope", normalizedOrAll(scope),
                    "directness", normalizedOrAll(directness)));
            String requestedDirectness = normalized(directness);
            if (!requestedDirectness.isEmpty()
                    && !Set.of("direct", "transitive", "mixed", "unknown")
                            .contains(requestedDirectness)) {
                trace.event("query_rejected", Map.of("reason", "invalid_directness"));
                return JSON.createObjectNode().put("error",
                        "directness must be direct, transitive, mixed, or unknown").toString();
            }
            DependencyMetadata metadata = metadata(jdbi, root);
            List<ClasspathFile> classpaths = metadata.classpaths();
            DeclaredDependencyDiscovery.Result declared = metadata.declared();
            trace.event("metadata_loaded", Map.of(
                    "indexed_modules", metadata.indexedModules().size(),
                    "classpath_files", classpaths.size(),
                    "declarations_complete", declared.complete(),
                    "declaration_source", declared.source(),
                    "metadata_cache", metadata.cacheHit() ? "hit" : "miss"));
            List<ParsedClasspath> effectiveClasspaths = effectiveClasspaths(
                    metadata, module, sourceSet);
            for (ParsedClasspath classpath : effectiveClasspaths) {
                if (module != null && !module.isBlank()
                        && !classpath.module().equals(normalizeModule(module))) continue;
                trace.event(classpath.inferred() ? "classpath_inferred" : "classpath_loaded", Map.of(
                        "module", classpath.module(),
                        "path", classpath.path().toString(),
                        "source_set", classpath.sourceSet(),
                        "classpath_scope", classpath.sourceSet().equals("test")
                                ? "test_runtime" : "runtime",
                        "evidence", classpath.evidence(),
                        "entries", classpath.jars().size(),
                        "last_modified_ms", lastModified(classpath.path())));
            }
            Map<String, Artifact> artifacts = new LinkedHashMap<>();
            int scannedJars = 0;
            String requestedSourceSet = normalized(sourceSet);
            if (!requestedSourceSet.isEmpty()
                    && !Set.of("main", "test").contains(requestedSourceSet)) {
                trace.event("query_rejected", Map.of("reason", "invalid_source_set"));
                return JSON.createObjectNode().put("error",
                        "source_set must be main or test").toString();
            }
            for (ParsedClasspath classpath : effectiveClasspaths) {
                if (module != null && !module.isBlank()
                        && !classpath.module().equals(normalizeModule(module))) continue;
                if (!requestedSourceSet.isEmpty()
                        && !classpath.sourceSet().equals(requestedSourceSet)) continue;
                scannedJars += classpath.jars().size();
                for (JarReference reference : classpath.jars()) {
                    Path jar = reference.jar();
                    Coordinates coordinates = reference.coordinates();
                    String searchable = (coordinates.id() + " " + jar.getFileName())
                            .toLowerCase();
                    if (query != null && !query.isBlank()
                            && !searchable.contains(query.strip().toLowerCase())) continue;
                    artifacts.computeIfAbsent(coordinates.id(), ignored ->
                                    new Artifact(coordinates, jar, new LinkedHashMap<>(),
                                            new LinkedHashMap<>()))
                            .add(classpath.module(), classpath.sourceSet(), classpath.evidence());
                }
            }
            List<ArtifactView> ordered = artifacts.values().stream()
                    .map(value -> view(value, declared))
                    .filter(value -> requestedDirectness.isEmpty()
                            || value.directness().equals(requestedDirectness))
                    .filter(value -> contains(value.artifact().coordinates().group(), group))
                    .filter(value -> contains(value.artifact().coordinates().artifact(), artifactName))
                    .filter(value -> normalized(scope).isEmpty()
                            || value.scopes().stream().anyMatch(candidate ->
                                    candidate.equalsIgnoreCase(scope.strip())))
                    .sorted(Comparator.comparing(value -> value.artifact().coordinates().id()))
                    .toList();
            int from = Math.min(offset, ordered.size());
            int to = Math.min(from + limit, ordered.size());
            Coverage coverage = coverage(metadata, effectiveClasspaths, module, sourceSet);
            boolean answerComplete = declared.complete()
                    && coverage.missingModules().isEmpty() && coverage.scopeCovered();
            trace.event("filter_summary", Map.of(
                    "scanned_jars", scannedJars,
                    "matching_artifacts_before_field_filters", artifacts.size(),
                    "matching_artifacts", ordered.size()));
            trace.event("result_completeness", Map.of(
                    "answer_complete", answerComplete,
                    "requested_source_set_covered", coverage.scopeCovered(),
                    "missing_classpath_modules", coverage.missingModules(),
                    "inferred_test_modules", coverage.inferredTestModules(),
                    "uncovered_source_sets", coverage.uncoveredSourceSets()));

            ObjectNode result = JSON.createObjectNode();
            result.put("total", ordered.size());
            result.put("showing", to - from);
            result.put("offset", offset);
            result.put("has_more", to < ordered.size());
            result.put("relationship", "resolved_runtime_classpath");
            result.put("requested_source_set", requestedSourceSet.isEmpty()
                    ? "all" : requestedSourceSet);
            result.put("directness", answerComplete ? "resolved" : "partial");
            result.put("answer_complete", answerComplete);
            ArrayNode dependencies = result.putArray("dependencies");
            ordered.subList(from, to).forEach(view -> appendDependency(dependencies, view));
            ObjectNode discovery = result.putObject("discovery");
            discovery.put("classpath_files", classpaths.size());
            discovery.put("build_invoked", false);
            discovery.put("declaration_source", declared.source());
            discovery.put("declaration_kind", "direct_only");
            discovery.put("declarations_complete", declared.complete());
            discovery.put("metadata_cache", metadata.cacheHit() ? "hit" : "miss");
            discovery.put("classpath_scope", "main_and_test_runtime");
            ArrayNode coveredSourceSets = discovery.putArray("covered_source_sets");
            coveredSourceSets.add("main");
            if (metadata.classpaths().stream().anyMatch(value ->
                    value.sourceSet().equals("test"))) coveredSourceSets.add("test");
            discovery.putArray("covered_scopes").add("compile").add("runtime");
            discovery.put("requested_source_set_covered", coverage.scopeCovered());
            discovery.put("classpath_complete_for_requested_modules",
                    coverage.missingModules().isEmpty());
            ArrayNode missingModules = discovery.putArray("missing_classpath_modules");
            coverage.missingModules().forEach(missingModules::add);
            ArrayNode inferredTestModules = discovery.putArray("inferred_test_modules");
            coverage.inferredTestModules().forEach(inferredTestModules::add);
            ArrayNode limitations = discovery.putArray("limitations");
            if (!declared.complete()) limitations.add("Some direct dependency declarations "
                    + "could not be resolved from build metadata");
            if (!coverage.missingModules().isEmpty()) limitations.add(
                    "Complete requested source-set classpath snapshots are missing for some modules");
            if (!coverage.inferredTestModules().isEmpty()) limitations.add(
                    "Some test visibility is inferred from reactor declarations and main "
                            + "runtime snapshots; direct external test dependencies require a "
                            + "successful Maven or Gradle test-classpath capture");
            if (!coverage.scopeCovered()) limitations.add(
                    "Requested source sets do not have complete classpath snapshots");
            if (trace.enabled()) {
                ObjectNode debug = result.putObject("debug");
                debug.put("trace_id", trace.id());
                debug.put("log_file", DebugTrace.logFile().toString());
            }
            return result.toString();
        } catch (RuntimeException | Error error) {
            trace.failure(error);
            throw error;
        } finally {
            trace.close();
        }
    }

    private static void appendDependency(ArrayNode dependencies, ArtifactView view) {
        Artifact artifact = view.artifact();
        ObjectNode item = dependencies.addObject();
        Coordinates value = artifact.coordinates();
        item.put("id", value.id());
        item.put("group", value.group());
        item.put("artifact", value.artifact());
        item.put("version", value.version());
        if (value.classifier() != null) item.put("classifier", value.classifier());
        item.put("repository_layout", value.layout());
        item.put("jar", artifact.jar().toString());
        ArrayNode modules = item.putArray("used_by_modules");
        artifact.modules().stream().sorted().forEach(modules::add);
        ArrayNode direct = item.putArray("direct_in_modules");
        view.directModules().forEach(direct::add);
        ArrayNode transitive = item.putArray("transitive_in_modules");
        view.transitiveModules().forEach(transitive::add);
        ArrayNode unknown = item.putArray("unknown_in_modules");
        view.unknownModules().forEach(unknown::add);
        ArrayNode scopes = item.putArray("declared_scopes");
        view.scopes().forEach(scopes::add);
        ArrayNode mainModules = item.putArray("main_runtime_in_modules");
        artifact.modulesFor("main").forEach(mainModules::add);
        ArrayNode testModules = item.putArray("test_runtime_in_modules");
        artifact.modulesFor("test").forEach(testModules::add);
        ArrayNode inferredModules = item.putArray("inferred_in_modules");
        artifact.inferredModules().forEach(inferredModules::add);
        item.put("directness", view.directness());
        item.put("direct", !view.directModules().isEmpty());
    }

    private static BuildSystem detectBuildSystem(Path root) {
        try {
            return BuildSystem.detect(root);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static List<String> findIndexedModules(Jdbi jdbi) {
        List<String> modules = jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT DISTINCT module FROM (
                          SELECT module FROM files WHERE lifecycle = 'current'
                          UNION ALL
                          SELECT module FROM classes WHERE lifecycle = 'current'
                        ) WHERE module IS NOT NULL AND module != ''
                        ORDER BY module
                        """).mapTo(String.class).list());
        return modules.isEmpty() ? List.of(".") : List.copyOf(modules);
    }

    private static List<ClasspathFile> findClasspathFiles(Path root, List<String> modules) {
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException error) {
            return List.of();
        }
        List<ClasspathFile> result = new ArrayList<>();
        for (String module : modules) {
            String normalized = normalizeModule(module);
            Path moduleRoot = normalized.equals(".") ? root : root.resolve(normalized);
            Path classpath = buildSystem.classpathFile(moduleRoot);
            if (Files.isRegularFile(classpath)) result.add(new ClasspathFile(
                    classpath, normalized, "main", "captured_runtime_classpath"));
            Path testClasspath = buildSystem.testClasspathFile(moduleRoot);
            if (Files.isRegularFile(testClasspath)) result.add(new ClasspathFile(
                    testClasspath, normalized, "test", "captured_test_runtime_classpath"));
        }
        return List.copyOf(result);
    }

    private static List<Path> readClasspath(Path file) {
        try {
            String value = Files.readString(file).strip();
            if (value.isEmpty()) return List.of();
            List<Path> jars = new ArrayList<>();
            for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path path = Path.of(entry);
                if (path.toString().endsWith(".jar") && Files.isRegularFile(path)) jars.add(path);
            }
            return List.copyOf(jars);
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
    }

    static List<Path> resolvedJars(Jdbi jdbi, Path root) {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        for (ParsedClasspath classpath : metadata(jdbi, root).parsedClasspaths()) {
            classpath.jars().forEach(reference -> jars.add(reference.jar()));
        }
        return List.copyOf(jars);
    }

    static void prewarm(Jdbi jdbi, Path root) {
        metadata(jdbi, root);
    }

    private static List<ParsedClasspath> effectiveClasspaths(
            DependencyMetadata metadata, String requestedModule, String requestedSourceSet) {
        List<ParsedClasspath> result = new ArrayList<>(metadata.parsedClasspaths());
        if (normalized(requestedSourceSet).equals("main")) return List.copyOf(result);
        Set<String> capturedTests = metadata.parsedClasspaths().stream()
                .filter(value -> value.sourceSet().equals("test"))
                .map(ParsedClasspath::module)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> modulesByGa = new LinkedHashMap<>();
        for (ProjectCoordinatesDiscovery.Module module : metadata.coordinates().modules()) {
            if (module.ga() != null) modulesByGa.putIfAbsent(module.ga(), module.module());
        }
        Map<String, ParsedClasspath> mainByModule = new LinkedHashMap<>();
        metadata.parsedClasspaths().stream()
                .filter(value -> value.sourceSet().equals("main"))
                .forEach(value -> mainByModule.put(value.module(), value));
        Map<String, List<ReactorEdge>> reactor = reactorEdges(metadata.declared(), modulesByGa);

        List<String> applications = requestedModule == null || requestedModule.isBlank()
                ? metadata.indexedModules() : List.of(normalizeModule(requestedModule));
        for (String application : applications) {
            if (capturedTests.contains(application)) continue;
            LinkedHashSet<String> visible = new LinkedHashSet<>();
            ArrayList<String> queue = new ArrayList<>();
            visible.add(application);
            queue.add(application);
            for (ReactorEdge edge : reactor.getOrDefault(application, List.of())) {
                if (testVisible(edge.scopes()) && visible.add(edge.target())) {
                    queue.add(edge.target());
                }
            }
            for (int index = 1; index < queue.size(); index++) {
                String current = queue.get(index);
                for (ReactorEdge edge : reactor.getOrDefault(current, List.of())) {
                    if (runtimeVisible(edge.scopes()) && visible.add(edge.target())) {
                        queue.add(edge.target());
                    }
                }
            }
            Map<Path, JarReference> jars = new LinkedHashMap<>();
            for (String module : visible) {
                ParsedClasspath main = mainByModule.get(module);
                if (main == null) continue;
                main.jars().forEach(reference -> jars.putIfAbsent(reference.jar(), reference));
            }
            if (!jars.isEmpty()) {
                result.add(new ParsedClasspath(Path.of("reactor-model"), application,
                        "test", "reactor_model_inference", true,
                        List.copyOf(jars.values())));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, List<ReactorEdge>> reactorEdges(
            DeclaredDependencyDiscovery.Result declared, Map<String, String> modulesByGa) {
        Map<String, List<ReactorEdge>> result = new LinkedHashMap<>();
        declared.dependenciesByModule().forEach((module, dependencies) -> {
            List<ReactorEdge> edges = new ArrayList<>();
            for (String dependency : dependencies) {
                String target = modulesByGa.get(dependency);
                if (target == null || target.equals(module)) continue;
                Set<String> scopes = declared.scopesByModule().getOrDefault(module, Map.of())
                        .getOrDefault(dependency, Set.of("compile"));
                edges.add(new ReactorEdge(target, scopes));
            }
            result.put(module, List.copyOf(edges));
        });
        return result;
    }

    private static boolean runtimeVisible(Set<String> scopes) {
        return scopes.stream().map(ProjectDependencyQueries::normalized)
                .anyMatch(value -> value.equals("compile") || value.equals("runtime"));
    }

    private static boolean testVisible(Set<String> scopes) {
        return scopes.stream().map(ProjectDependencyQueries::normalized)
                .anyMatch(value -> Set.of("compile", "runtime", "test", "provided")
                        .contains(value));
    }

    private static DependencyMetadata metadata(Jdbi jdbi, Path root) {
        Path key = root.toAbsolutePath().normalize();
        List<String> indexedModules = findIndexedModules(jdbi);
        List<ClasspathFile> classpaths = findClasspathFiles(key, indexedModules);
        String fingerprint = metadataFingerprint(key, indexedModules, classpaths);
        CachedMetadata cached = METADATA.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.metadata().withCacheHit(true);
        }
        List<ParsedClasspath> parsed = new ArrayList<>();
        for (ClasspathFile classpath : classpaths) {
            List<JarReference> jars = readClasspath(classpath.path()).stream()
                    .map(jar -> new JarReference(jar, coordinates(jar))).toList();
            parsed.add(new ParsedClasspath(classpath.path(), classpath.module(),
                    classpath.sourceSet(), classpath.evidence(), false, jars));
        }
        BuildSystem buildSystem = detectBuildSystem(key);
        DeclaredDependencyDiscovery.Result declared = buildSystem == null
                ? new DeclaredDependencyDiscovery.Result(
                        Map.of(), Map.of(), Set.of(), "unavailable", false)
                : DeclaredDependencyDiscovery.discover(key, buildSystem,
                        new LinkedHashSet<>(indexedModules));
        DependencyMetadata created = new DependencyMetadata(
                indexedModules, List.copyOf(classpaths), List.copyOf(parsed), declared,
                ProjectCoordinatesDiscovery.discover(key), false);
        if (METADATA.size() >= MAX_PROJECT_CACHES) METADATA.clear();
        METADATA.put(key, new CachedMetadata(fingerprint, created));
        return created;
    }

    private static String metadataFingerprint(Path root, List<String> indexedModules,
            List<ClasspathFile> classpaths) {
        StringBuilder value = new StringBuilder();
        value.append("modules=").append(indexedModules);
        for (String module : indexedModules) {
            Path moduleRoot = module.equals(".") ? root : root.resolve(module);
            appendFingerprint(value, moduleRoot.resolve("pom.xml"));
            appendFingerprint(value, moduleRoot.resolve("build/quill-direct-dependencies.tsv"));
            BuildSystem buildSystem = detectBuildSystem(root);
            if (buildSystem != null) {
                appendFingerprint(value, buildSystem.testClasspathFile(moduleRoot));
            }
        }
        for (ClasspathFile classpath : classpaths) {
            appendFingerprint(value, classpath.path());
        }
        return value.toString();
    }

    private static void appendFingerprint(StringBuilder target, Path path) {
        target.append('|').append(path);
        try {
            target.append(':').append(Files.getLastModifiedTime(path).toMillis())
                    .append(':').append(Files.size(path));
        } catch (IOException error) {
            target.append(":missing");
        }
    }

    private static Coverage coverage(DependencyMetadata metadata,
            List<ParsedClasspath> effectiveClasspaths, String module, String sourceSet) {
        Set<String> requested = new LinkedHashSet<>();
        if (module == null || module.isBlank()) {
            requested.addAll(metadata.indexedModules());
        } else {
            requested.add(normalizeModule(module));
        }
        Set<String> main = modulesFor(metadata.classpaths(), "main");
        Set<String> test = modulesFor(metadata.classpaths(), "test");
        Set<String> inferred = effectiveClasspaths.stream()
                .filter(ParsedClasspath::inferred).map(ParsedClasspath::module)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String requestedSourceSet = normalized(sourceSet);
        Set<String> available = new LinkedHashSet<>();
        if (!requestedSourceSet.equals("test")) available.addAll(main);
        if (!requestedSourceSet.equals("main")) available.addAll(test);
        Set<String> missing = new LinkedHashSet<>(requested);
        if (requestedSourceSet.isEmpty()) {
            missing.removeIf(candidate -> main.contains(candidate) && test.contains(candidate));
        } else {
            missing.removeAll(available);
        }
        List<String> uncovered = new ArrayList<>();
        if (!requestedSourceSet.equals("test") && !main.containsAll(requested)) {
            uncovered.add("main");
        }
        if (!requestedSourceSet.equals("main") && !test.containsAll(requested)) {
            uncovered.add("test");
        }
        List<String> inferredRequested = requestedSourceSet.equals("main") ? List.of()
                : requested.stream().filter(inferred::contains).sorted().toList();
        return new Coverage(missing.stream().sorted().toList(), missing.isEmpty(),
                inferredRequested, List.copyOf(uncovered));
    }

    private static Set<String> modulesFor(List<ClasspathFile> classpaths, String sourceSet) {
        return classpaths.stream().filter(value -> value.sourceSet().equals(sourceSet))
                .map(ClasspathFile::module)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizedOrAll(String value) {
        return value == null || value.isBlank() ? "<all>" : value.strip();
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            return -1;
        }
    }

    private static String normalizeModule(String module) {
        String value = module.strip().replace('\\', '/');
        if (value.equals("./") || value.isEmpty()) return ".";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean contains(String value, String filter) {
        return normalized(filter).isEmpty()
                || value.toLowerCase(java.util.Locale.ROOT).contains(normalized(filter));
    }

    private static ArtifactView view(
            Artifact artifact, DeclaredDependencyDiscovery.Result declared) {
        List<String> direct = artifact.modules().stream().sorted()
                .filter(candidate -> declared.dependenciesByModule()
                        .getOrDefault(candidate, Set.of())
                        .contains(artifact.coordinates().ga())).toList();
        List<String> transitive = artifact.modules().stream().sorted()
                .filter(declared.completeModules()::contains)
                .filter(candidate -> !direct.contains(candidate)).toList();
        List<String> unknown = artifact.modules().stream().sorted()
                .filter(candidate -> !declared.completeModules().contains(candidate)).toList();
        String directness;
        if (!direct.isEmpty() && transitive.isEmpty() && unknown.isEmpty()) {
            directness = "direct";
        } else if (direct.isEmpty() && !transitive.isEmpty() && unknown.isEmpty()) {
            directness = "transitive";
        } else if (direct.isEmpty() && transitive.isEmpty()) {
            directness = "unknown";
        } else {
            directness = "mixed";
        }
        List<String> scopes = direct.stream()
                .flatMap(module -> declared.scopesByModule().getOrDefault(module, Map.of())
                        .getOrDefault(artifact.coordinates().ga(), Set.of()).stream())
                .distinct().sorted().toList();
        return new ArtifactView(artifact, direct, transitive, unknown, scopes, directness);
    }

    static Coordinates coordinates(Path jar) {
        List<String> segments = new ArrayList<>();
        jar.toAbsolutePath().normalize().forEach(part -> segments.add(part.toString()));
        int gradle = segments.indexOf("files-2.1");
        if (gradle >= 0 && gradle + 4 < segments.size()) {
            return build(segments.get(gradle + 1), segments.get(gradle + 2),
                    segments.get(gradle + 3), jar, "gradle_cache");
        }
        int repository = lastIndexOf(segments, "repository");
        if (repository >= 0 && repository + 3 < segments.size()) {
            int artifactIndex = segments.size() - 3;
            if (artifactIndex > repository) {
                String group = String.join(".", segments.subList(repository + 1, artifactIndex));
                return build(group, segments.get(artifactIndex),
                        segments.get(artifactIndex + 1), jar, "maven_repository");
            }
        }
        String filename = jar.getFileName().toString();
        String artifact = filename.substring(0, filename.length() - 4);
        return new Coordinates(artifact, artifact, "unknown", null, "other");
    }

    private static Coordinates build(
            String group, String artifact, String version, Path jar, String layout) {
        String filename = jar.getFileName().toString();
        String stem = filename.substring(0, filename.length() - 4);
        String prefix = artifact + "-" + version;
        String classifier = stem.startsWith(prefix + "-")
                ? stem.substring(prefix.length() + 1) : null;
        return new Coordinates(group + ":" + artifact + ":" + version
                + (classifier == null ? "" : ":" + classifier),
                artifact, group, version, classifier, layout);
    }

    private static int lastIndexOf(List<String> values, String value) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).equals(value)) return i;
        }
        return -1;
    }

    record Coordinates(String id, String artifact, String group, String version,
            String classifier, String layout) {
        Coordinates(String id, String artifact, String version, String classifier, String layout) {
            this(id, artifact, "unknown", version, classifier, layout);
        }

        String ga() {
            return group + ":" + artifact;
        }
    }

    private record ClasspathFile(Path path, String module, String sourceSet, String evidence) {}
    private record JarReference(Path jar, Coordinates coordinates) {}
    private record ParsedClasspath(Path path, String module, String sourceSet, String evidence,
            boolean inferred, List<JarReference> jars) {}
    private record DependencyMetadata(List<String> indexedModules, List<ClasspathFile> classpaths,
            List<ParsedClasspath> parsedClasspaths,
            DeclaredDependencyDiscovery.Result declared,
            ProjectCoordinatesDiscovery.Result coordinates, boolean cacheHit) {
        DependencyMetadata withCacheHit(boolean value) {
            return new DependencyMetadata(indexedModules, classpaths, parsedClasspaths,
                    declared, coordinates, value);
        }
    }
    private record CachedMetadata(String fingerprint, DependencyMetadata metadata) {}
    private record Artifact(Coordinates coordinates, Path jar,
            Map<String, Set<String>> sourceSetsByModule,
            Map<String, Set<String>> evidenceByModule) {
        void add(String module, String sourceSet, String evidence) {
            sourceSetsByModule.computeIfAbsent(module, ignored -> new LinkedHashSet<>())
                    .add(sourceSet);
            evidenceByModule.computeIfAbsent(module, ignored -> new LinkedHashSet<>())
                    .add(evidence);
        }

        Set<String> modules() {
            return sourceSetsByModule.keySet();
        }

        List<String> modulesFor(String sourceSet) {
            return sourceSetsByModule.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(sourceSet))
                    .map(Map.Entry::getKey).sorted().toList();
        }

        List<String> inferredModules() {
            return evidenceByModule.entrySet().stream()
                    .filter(entry -> entry.getValue().contains("reactor_model_inference"))
                    .map(Map.Entry::getKey).sorted().toList();
        }
    }
    private record ArtifactView(Artifact artifact, List<String> directModules,
            List<String> transitiveModules, List<String> unknownModules,
            List<String> scopes, String directness) {}
    private record Coverage(List<String> missingModules, boolean scopeCovered,
            List<String> inferredTestModules, List<String> uncoveredSourceSets) {}
    private record ReactorEdge(String target, Set<String> scopes) {}
}
