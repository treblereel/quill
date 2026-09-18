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

/** Reads the dependency artifacts captured by Quill's Maven or Gradle classpath discovery. */
final class ProjectDependencyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROJECT_CACHES = 8;
    private static final ConcurrentHashMap<Path, CachedMetadata> METADATA =
            new ConcurrentHashMap<>();

    String getProjectDependencies(Jdbi jdbi, Path root, String module, String query,
            int limit, int offset) {
        DependencyMetadata metadata = metadata(jdbi, root);
        List<ClasspathFile> classpaths = metadata.classpaths();
        DeclaredDependencyDiscovery.Result declared = metadata.declared();
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (ParsedClasspath classpath : metadata.parsedClasspaths()) {
            if (module != null && !module.isBlank()
                    && !classpath.module().equals(normalizeModule(module))) continue;
            for (JarReference reference : classpath.jars()) {
                Path jar = reference.jar();
                Coordinates coordinates = reference.coordinates();
                String searchable = (coordinates.id() + " " + jar.getFileName())
                        .toLowerCase();
                if (query != null && !query.isBlank()
                        && !searchable.contains(query.strip().toLowerCase())) continue;
                artifacts.computeIfAbsent(coordinates.id(), ignored ->
                                new Artifact(coordinates, jar, new LinkedHashSet<>()))
                        .modules().add(classpath.module());
            }
        }
        List<Artifact> ordered = artifacts.values().stream()
                .sorted(Comparator.comparing(artifact -> artifact.coordinates().id()))
                .toList();
        int from = Math.min(offset, ordered.size());
        int to = Math.min(from + limit, ordered.size());

        ObjectNode result = JSON.createObjectNode();
        result.put("total", ordered.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < ordered.size());
        result.put("relationship", "resolved_runtime_classpath");
        result.put("directness", declared.complete() ? "resolved" : "partial");
        ArrayNode dependencies = result.putArray("dependencies");
        ordered.subList(from, to).forEach(artifact -> {
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
            ArrayNode directModules = item.putArray("direct_in_modules");
            artifact.modules().stream().sorted()
                    .filter(candidate -> declared.dependenciesByModule()
                            .getOrDefault(candidate, Set.of()).contains(value.ga()))
                    .forEach(directModules::add);
            ArrayNode transitiveModules = item.putArray("transitive_in_modules");
            artifact.modules().stream().sorted()
                    .filter(declared.completeModules()::contains)
                    .filter(candidate -> !declared.dependenciesByModule()
                            .getOrDefault(candidate, Set.of()).contains(value.ga()))
                    .forEach(transitiveModules::add);
            ArrayNode unknownModules = item.putArray("unknown_in_modules");
            artifact.modules().stream().sorted()
                    .filter(candidate -> !declared.completeModules().contains(candidate))
                    .forEach(unknownModules::add);
            String itemDirectness;
            if (!directModules.isEmpty() && transitiveModules.isEmpty() && unknownModules.isEmpty()) {
                itemDirectness = "direct";
            } else if (directModules.isEmpty() && !transitiveModules.isEmpty()
                    && unknownModules.isEmpty()) {
                itemDirectness = "transitive";
            } else if (directModules.isEmpty() && transitiveModules.isEmpty()) {
                itemDirectness = "unknown";
            } else {
                itemDirectness = "mixed";
            }
            item.put("directness", itemDirectness);
            item.put("direct", !directModules.isEmpty());
        });
        ObjectNode discovery = result.putObject("discovery");
        discovery.put("classpath_files", classpaths.size());
        discovery.put("build_invoked", false);
        discovery.put("declaration_source", declared.source());
        discovery.put("declarations_complete", declared.complete());
        discovery.put("metadata_cache", metadata.cacheHit() ? "hit" : "miss");
        if (!declared.complete()) {
            discovery.put("limitation", "Missing or unresolved dependency declarations are "
                    + "reported as transitive until the next Gradle discovery snapshot");
        }
        return result.toString();
    }

    private static BuildSystem detectBuildSystem(Path root) {
        try {
            return BuildSystem.detect(root);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static List<ClasspathFile> findClasspathFiles(Jdbi jdbi, Path root) {
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException error) {
            return List.of();
        }
        List<String> modules = jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT DISTINCT module FROM (
                          SELECT module FROM files WHERE lifecycle = 'current'
                          UNION ALL
                          SELECT module FROM classes WHERE lifecycle = 'current'
                        ) WHERE module IS NOT NULL AND module != ''
                        ORDER BY module
                        """).mapTo(String.class).list());
        if (modules.isEmpty()) modules = List.of(".");
        List<ClasspathFile> result = new ArrayList<>();
        for (String module : modules) {
            String normalized = normalizeModule(module);
            Path moduleRoot = normalized.equals(".") ? root : root.resolve(normalized);
            Path classpath = buildSystem.classpathFile(moduleRoot);
            if (Files.isRegularFile(classpath)) {
                result.add(new ClasspathFile(classpath, normalized));
            }
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

    private static DependencyMetadata metadata(Jdbi jdbi, Path root) {
        Path key = root.toAbsolutePath().normalize();
        List<ClasspathFile> classpaths = findClasspathFiles(jdbi, key);
        String fingerprint = metadataFingerprint(key, classpaths);
        CachedMetadata cached = METADATA.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.metadata().withCacheHit(true);
        }
        List<ParsedClasspath> parsed = new ArrayList<>();
        Set<String> modules = new LinkedHashSet<>();
        for (ClasspathFile classpath : classpaths) {
            modules.add(classpath.module());
            List<JarReference> jars = readClasspath(classpath.path()).stream()
                    .map(jar -> new JarReference(jar, coordinates(jar))).toList();
            parsed.add(new ParsedClasspath(classpath.module(), jars));
        }
        BuildSystem buildSystem = detectBuildSystem(key);
        DeclaredDependencyDiscovery.Result declared = buildSystem == null
                ? new DeclaredDependencyDiscovery.Result(
                        Map.of(), Set.of(), "unavailable", false)
                : DeclaredDependencyDiscovery.discover(key, buildSystem, modules);
        DependencyMetadata created = new DependencyMetadata(
                List.copyOf(classpaths), List.copyOf(parsed), declared, false);
        if (METADATA.size() >= MAX_PROJECT_CACHES) METADATA.clear();
        METADATA.put(key, new CachedMetadata(fingerprint, created));
        return created;
    }

    private static String metadataFingerprint(Path root, List<ClasspathFile> classpaths) {
        StringBuilder value = new StringBuilder();
        for (ClasspathFile classpath : classpaths) {
            appendFingerprint(value, classpath.path());
            Path moduleRoot = classpath.module().equals(".")
                    ? root : root.resolve(classpath.module());
            appendFingerprint(value, moduleRoot.resolve("pom.xml"));
            appendFingerprint(value, moduleRoot.resolve("build/quill-direct-dependencies.tsv"));
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

    private static String normalizeModule(String module) {
        String value = module.strip().replace('\\', '/');
        if (value.equals("./") || value.isEmpty()) return ".";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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

    private record ClasspathFile(Path path, String module) {}
    private record JarReference(Path jar, Coordinates coordinates) {}
    private record ParsedClasspath(String module, List<JarReference> jars) {}
    private record DependencyMetadata(List<ClasspathFile> classpaths,
            List<ParsedClasspath> parsedClasspaths,
            DeclaredDependencyDiscovery.Result declared, boolean cacheHit) {
        DependencyMetadata withCacheHit(boolean value) {
            return new DependencyMetadata(classpaths, parsedClasspaths, declared, value);
        }
    }
    private record CachedMetadata(String fingerprint, DependencyMetadata metadata) {}
    private record Artifact(Coordinates coordinates, Path jar, Set<String> modules) {}
}
