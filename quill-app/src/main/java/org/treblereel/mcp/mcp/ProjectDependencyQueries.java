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
import java.util.stream.Stream;

/** Reads the dependency artifacts captured by Quill's Maven or Gradle classpath discovery. */
final class ProjectDependencyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getProjectDependencies(Path root, String module, String query, int limit, int offset) {
        List<ClasspathFile> classpaths = findClasspathFiles(root);
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (ClasspathFile classpath : classpaths) {
            if (module != null && !module.isBlank()
                    && !classpath.module().equals(normalizeModule(module))) continue;
            for (Path jar : readClasspath(classpath.path())) {
                Coordinates coordinates = coordinates(jar);
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
        result.put("directness", "unknown");
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
        });
        ObjectNode discovery = result.putObject("discovery");
        discovery.put("classpath_files", classpaths.size());
        discovery.put("build_invoked", false);
        discovery.put("limitation",
                "Resolved classpaths do not preserve direct versus transitive declarations");
        return result.toString();
    }

    private static List<ClasspathFile> findClasspathFiles(Path root) {
        try (Stream<Path> files = Files.find(root, 16,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals("quill-classpath.txt"))) {
            return files.map(path -> new ClasspathFile(path, module(root, path)))
                    .sorted(Comparator.comparing(entry -> entry.path().toString())).toList();
        } catch (IOException error) {
            return List.of();
        }
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

    private static String module(Path root, Path classpath) {
        Path output = classpath.getParent();
        Path module = output == null ? root : output.getParent();
        if (module == null || module.equals(root)) return ".";
        try {
            return root.relativize(module).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException error) {
            return module.toString().replace(File.separatorChar, '/');
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
    }

    private record ClasspathFile(Path path, String module) {}
    private record Artifact(Coordinates coordinates, Path jar, Set<String> modules) {}
}
