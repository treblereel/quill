package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Reads direct dependency declarations without invoking the target build. */
public final class DeclaredDependencyDiscovery {

    public record Result(Map<String, Set<String>> dependenciesByModule, Set<String> completeModules,
            String source, boolean complete) {
        public Result {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            dependenciesByModule.forEach((module, dependencies) ->
                    copy.put(module, Set.copyOf(dependencies)));
            dependenciesByModule = Map.copyOf(copy);
            completeModules = Set.copyOf(completeModules);
        }
    }

    private DeclaredDependencyDiscovery() {}

    public static Result discover(Path root, BuildSystem buildSystem, Set<String> modules) {
        return switch (buildSystem) {
            case MAVEN -> discoverMaven(root, modules);
            case GRADLE -> discoverGradle(root, modules);
        };
    }

    private static Result discoverMaven(Path root, Set<String> modules) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> completeModules = new LinkedHashSet<>();
        boolean complete = true;
        for (String module : modules) {
            Path directory = module.equals(".") ? root : root.resolve(module);
            Set<String> dependencies = new LinkedHashSet<>();
            if (readMavenDependencies(directory.resolve("pom.xml"), dependencies,
                    new LinkedHashSet<>(), new Properties())) {
                completeModules.add(module);
            } else {
                complete = false;
            }
            result.put(module, dependencies);
        }
        return new Result(result, completeModules, "maven_model", complete);
    }

    private static boolean readMavenDependencies(Path pom, Set<String> result,
            Set<Path> visited, Properties inheritedProperties) {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!visited.add(normalized) || !Files.isRegularFile(normalized)) return false;
        Model model = readModel(normalized);
        if (model == null) return false;

        Properties properties = new Properties();
        properties.putAll(inheritedProperties);
        properties.putAll(model.getProperties());
        String group = model.getGroupId() != null ? model.getGroupId()
                : model.getParent() == null ? null : model.getParent().getGroupId();
        String version = model.getVersion() != null ? model.getVersion()
                : model.getParent() == null ? null : model.getParent().getVersion();
        putStandardProperties(properties, group, model.getArtifactId(), version, normalized);

        boolean complete = true;
        if (model.getParent() != null) {
            String relative = model.getParent().getRelativePath();
            if (relative == null || relative.isBlank()) relative = "../pom.xml";
            Path parent = normalized.getParent().resolve(interpolate(relative, properties))
                    .normalize();
            if (Files.isDirectory(parent)) parent = parent.resolve("pom.xml");
            if (Files.isRegularFile(parent)) {
                complete &= readMavenDependencies(parent, result, visited, properties);
            }
        }
        complete &= addDependencies(model.getDependencies(), result, properties);
        for (Profile profile : model.getProfiles()) {
            complete &= addDependencies(profile.getDependencies(), result, properties);
        }
        return complete;
    }

    private static boolean addDependencies(
            java.util.List<Dependency> dependencies, Set<String> result, Properties properties) {
        boolean complete = true;
        for (Dependency dependency : dependencies) {
            String group = interpolate(dependency.getGroupId(), properties);
            String artifact = interpolate(dependency.getArtifactId(), properties);
            if (group == null || artifact == null || group.contains("${")
                    || artifact.contains("${")) {
                complete = false;
            } else {
                result.add(group + ":" + artifact);
            }
        }
        return complete;
    }

    private static Result discoverGradle(Path root, Set<String> modules) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> completeModules = new LinkedHashSet<>();
        boolean complete = true;
        for (String module : modules) {
            Path directory = module.equals(".") ? root : root.resolve(module);
            Path snapshot = directory.resolve("build/quill-direct-dependencies.tsv");
            Set<String> dependencies = new LinkedHashSet<>();
            if (Files.isRegularFile(snapshot)) {
                try {
                    for (String line : Files.readAllLines(snapshot, StandardCharsets.UTF_8)) {
                        String[] fields = line.split("\\t", -1);
                        if (fields.length >= 2 && !fields[0].isBlank() && !fields[1].isBlank()) {
                            dependencies.add(fields[0] + ":" + fields[1]);
                        }
                    }
                    completeModules.add(module);
                } catch (IOException e) {
                    complete = false;
                }
            } else {
                complete = false;
            }
            result.put(module, dependencies);
        }
        return new Result(result, completeModules, "gradle_discovery_snapshot", complete);
    }

    private static Model readModel(Path pom) {
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            return null;
        }
    }

    private static void putStandardProperties(Properties properties, String group,
            String artifact, String version, Path pom) {
        if (group != null) {
            properties.setProperty("project.groupId", group);
            properties.setProperty("pom.groupId", group);
        }
        if (artifact != null) {
            properties.setProperty("project.artifactId", artifact);
            properties.setProperty("pom.artifactId", artifact);
        }
        if (version != null) {
            properties.setProperty("project.version", version);
            properties.setProperty("pom.version", version);
        }
        properties.setProperty("project.basedir", pom.getParent().toString());
        properties.setProperty("pom.basedir", pom.getParent().toString());
        properties.setProperty("basedir", pom.getParent().toString());
    }

    private static String interpolate(String value, Properties properties) {
        if (value == null) return null;
        String result = value;
        for (int pass = 0; pass < 10 && result.contains("${"); pass++) {
            String previous = result;
            for (String name : properties.stringPropertyNames()) {
                result = result.replace("${" + name + "}", properties.getProperty(name));
            }
            if (result.equals(previous)) break;
        }
        return result;
    }
}
