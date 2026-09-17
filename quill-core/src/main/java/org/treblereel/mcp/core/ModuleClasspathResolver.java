package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.treblereel.mcp.model.ModuleClasspathRecord;

/** Builds the transitive project-module visibility graph without running the target build. */
public final class ModuleClasspathResolver {

    private static final Pattern GRADLE_PROJECT = Pattern.compile(
            "project\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)|project\\s+['\"]([^'\"]+)['\"]");

    private ModuleClasspathResolver() {}

    public static List<ModuleClasspathRecord> resolve(
            Path projectRoot, BuildSystem buildSystem, List<Path> moduleDirectories) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Path> modules = moduleDirectories.stream().map(path -> path.toAbsolutePath().normalize())
                .distinct().sorted().toList();
        Map<Path, Set<Path>> direct = buildSystem == BuildSystem.MAVEN
                ? mavenDependencies(modules) : gradleDependencies(root, modules);
        List<ModuleClasspathRecord> result = new ArrayList<>();
        for (Path application : modules) {
            Map<Path, Integer> distances = distances(application, direct);
            distances.entrySet().stream()
                    .sorted(Map.Entry.<Path, Integer>comparingByValue()
                            .thenComparing(entry -> moduleName(root, entry.getKey())))
                    .forEach(entry -> result.add(new ModuleClasspathRecord(
                            moduleName(root, application), moduleName(root, entry.getKey()),
                            entry.getValue(), entry.getValue() == 0 ? "self" : "project_dependency")));
        }
        return List.copyOf(result);
    }

    private static Map<Path, Integer> distances(Path application, Map<Path, Set<Path>> direct) {
        Map<Path, Integer> result = new LinkedHashMap<>();
        ArrayDeque<Path> queue = new ArrayDeque<>();
        result.put(application, 0);
        queue.add(application);
        while (!queue.isEmpty()) {
            Path current = queue.remove();
            int nextDistance = result.get(current) + 1;
            for (Path dependency : direct.getOrDefault(current, Set.of())) {
                Integer previous = result.get(dependency);
                if (previous == null || nextDistance < previous) {
                    result.put(dependency, nextDistance);
                    queue.add(dependency);
                }
            }
        }
        return result;
    }

    private static Map<Path, Set<Path>> mavenDependencies(List<Path> modules) {
        Map<Path, MavenModule> parsed = new LinkedHashMap<>();
        for (Path module : modules) {
            Model model = readPom(module.resolve("pom.xml"));
            if (model != null) parsed.put(module, new MavenModule(module, model));
        }
        Map<String, Path> coordinates = new HashMap<>();
        for (MavenModule module : parsed.values()) {
            String group = value(module.model().getGroupId(),
                    module.model().getParent() == null ? null : module.model().getParent().getGroupId());
            if (group != null && module.model().getArtifactId() != null) {
                coordinates.put(group + ":" + module.model().getArtifactId(), module.path());
            }
        }
        Map<Path, Set<Path>> result = emptyGraph(modules);
        for (MavenModule module : parsed.values()) {
            Properties properties = new Properties();
            properties.putAll(module.model().getProperties());
            String projectGroup = value(module.model().getGroupId(),
                    module.model().getParent() == null ? null : module.model().getParent().getGroupId());
            put(properties, "project.groupId", projectGroup);
            put(properties, "pom.groupId", projectGroup);
            put(properties, "project.artifactId", module.model().getArtifactId());
            for (Dependency dependency : module.model().getDependencies()) {
                if (!runtimeVisible(dependency)) continue;
                String group = interpolate(dependency.getGroupId(), properties);
                String artifact = interpolate(dependency.getArtifactId(), properties);
                Path target = coordinates.get(group + ":" + artifact);
                if (target != null && !target.equals(module.path())) result.get(module.path()).add(target);
            }
        }
        return result;
    }

    private static boolean runtimeVisible(Dependency dependency) {
        String scope = dependency.getScope();
        return scope == null || scope.isBlank() || scope.equals("compile") || scope.equals("runtime");
    }

    private static Map<Path, Set<Path>> gradleDependencies(Path root, List<Path> modules) {
        Map<Path, Set<Path>> result = emptyGraph(modules);
        Map<String, Path> names = new HashMap<>();
        for (Path module : modules) {
            String relative = moduleName(root, module);
            names.put(":" + relative.replace('/', ':'), module);
            if (module.getFileName() != null) names.put(":" + module.getFileName(), module);
        }
        for (Path module : modules) {
            Path build = Files.isRegularFile(module.resolve("build.gradle.kts"))
                    ? module.resolve("build.gradle.kts") : module.resolve("build.gradle");
            if (!Files.isRegularFile(build)) continue;
            try {
                Matcher matcher = GRADLE_PROJECT.matcher(Files.readString(build, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    Path target = names.get(name.startsWith(":") ? name : ":" + name);
                    if (target != null && !target.equals(module)) result.get(module).add(target);
                }
            } catch (IOException ignored) {
                // A partial graph is still useful; every module retains its self context.
            }
        }
        return result;
    }

    private static Map<Path, Set<Path>> emptyGraph(List<Path> modules) {
        Map<Path, Set<Path>> result = new LinkedHashMap<>();
        modules.forEach(module -> result.put(module, new LinkedHashSet<>()));
        return result;
    }

    private static Model readPom(Path pom) {
        if (!Files.isRegularFile(pom)) return null;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException | RuntimeException ignored) {
            return null;
        }
    }

    private static String interpolate(String value, Properties properties) {
        if (value == null) return "";
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

    private static void put(Properties properties, String key, String value) {
        if (value != null) properties.setProperty(key, value);
    }

    private static String value(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String moduleName(Path root, Path module) {
        if (module.equals(root)) return ".";
        return module.startsWith(root)
                ? root.relativize(module).toString().replace('\\', '/')
                : module.toString().replace('\\', '/');
    }

    private record MavenModule(Path path, Model model) {}
}
