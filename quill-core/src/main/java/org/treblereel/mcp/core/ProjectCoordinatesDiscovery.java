package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Discovers coordinates from build metadata without executing Maven or Gradle. */
public final class ProjectCoordinatesDiscovery {

    public record Module(String module, String group, String artifact, String version,
            BuildSystem buildSystem, String evidence) {
        public String ga() {
            return group == null || artifact == null ? null : group + ":" + artifact;
        }
    }

    public record Result(List<Module> modules, boolean complete, List<String> diagnostics) {
        public Result {
            modules = List.copyOf(modules);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record EffectiveModel(String group, String artifact, String version,
            Properties properties) {}

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(group|version)\\s*(?:=\\s*)?[\"']([^\"']+)[\"']\\s*$");
    private static final Pattern ROOT_NAME = Pattern.compile(
            "(?m)^\\s*rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern INCLUDE_LINE = Pattern.compile("(?m)^\\s*include(?:\\s*\\(|\\s+)([^\\n]+)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private ProjectCoordinatesDiscovery() {}

    public static Result discover(Path requestedRoot) {
        Path root = requestedRoot.toAbsolutePath().normalize();
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException unsupported) {
            return new Result(List.of(), false, List.of(unsupported.getMessage()));
        }
        return buildSystem == BuildSystem.MAVEN ? discoverMaven(root) : discoverGradle(root);
    }

    private static Result discoverMaven(Path root) {
        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);
        List<Module> modules = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        boolean complete = discovery.complete();
        for (Path directory : discovery.moduleDirectories()) {
            Path pom = directory.resolve("pom.xml");
            EffectiveModel model = effectiveMavenModel(pom, new LinkedHashSet<>());
            String module = relative(root, directory);
            if (model == null || unresolved(model.group()) || unresolved(model.artifact())
                    || unresolved(model.version())) {
                diagnostics.add("Could not resolve Maven coordinates for " + module);
                complete = false;
                continue;
            }
            modules.add(new Module(module, model.group(), model.artifact(), model.version(),
                    BuildSystem.MAVEN, "maven_model"));
        }
        return new Result(modules, complete, diagnostics);
    }

    private static EffectiveModel effectiveMavenModel(Path pom, Set<Path> visited) {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!visited.add(normalized) || !Files.isRegularFile(normalized)) return null;
        Model model = readModel(normalized);
        if (model == null) return null;

        EffectiveModel parent = null;
        if (model.getParent() != null) {
            String relative = model.getParent().getRelativePath();
            if (relative == null || relative.isBlank()) relative = "../pom.xml";
            Path parentPom = normalized.getParent().resolve(relative).normalize();
            if (Files.isDirectory(parentPom)) parentPom = parentPom.resolve("pom.xml");
            if (Files.isRegularFile(parentPom)) {
                parent = effectiveMavenModel(parentPom, visited);
            }
        }

        Properties properties = new Properties();
        if (parent != null) properties.putAll(parent.properties());
        properties.putAll(model.getProperties());
        if (parent != null) {
            put(properties, "project.parent.groupId", parent.group());
            put(properties, "project.parent.artifactId", parent.artifact());
            put(properties, "project.parent.version", parent.version());
        }
        String group = first(model.getGroupId(), parent == null ? null : parent.group(),
                model.getParent() == null ? null : model.getParent().getGroupId());
        String artifact = model.getArtifactId();
        String version = first(model.getVersion(), parent == null ? null : parent.version(),
                model.getParent() == null ? null : model.getParent().getVersion());
        group = interpolate(group, properties);
        artifact = interpolate(artifact, properties);
        version = interpolate(version, properties);
        put(properties, "project.groupId", group);
        put(properties, "pom.groupId", group);
        put(properties, "project.artifactId", artifact);
        put(properties, "pom.artifactId", artifact);
        put(properties, "project.version", version);
        put(properties, "pom.version", version);
        put(properties, "revision", interpolate(properties.getProperty("revision"), properties));
        group = interpolate(group, properties);
        artifact = interpolate(artifact, properties);
        version = interpolate(version, properties);
        return new EffectiveModel(group, artifact, version, properties);
    }

    private static Result discoverGradle(Path root) {
        List<String> diagnostics = new ArrayList<>();
        String settings = readFirst(root.resolve("settings.gradle.kts"),
                root.resolve("settings.gradle"));
        String rootBuild = readFirst(root.resolve("build.gradle.kts"),
                root.resolve("build.gradle"));
        Map<String, String> rootAssignments = assignments(rootBuild);
        String rootName = match(ROOT_NAME, settings);
        if (rootName == null) rootName = root.getFileName().toString();

        Set<String> modulePaths = new LinkedHashSet<>();
        modulePaths.add(".");
        if (settings != null) {
            Matcher includes = INCLUDE_LINE.matcher(settings);
            while (includes.find()) {
                Matcher quoted = QUOTED.matcher(includes.group(1));
                while (quoted.find()) {
                    String module = quoted.group(1).replace(':', '/');
                    while (module.startsWith("/")) module = module.substring(1);
                    if (!module.isBlank()) modulePaths.add(module);
                }
            }
        }

        List<Module> modules = new ArrayList<>();
        boolean complete = true;
        for (String modulePath : modulePaths) {
            Path directory = modulePath.equals(".") ? root : root.resolve(modulePath);
            if (!Files.isDirectory(directory)) {
                diagnostics.add("Gradle module directory is missing: " + modulePath);
                complete = false;
                continue;
            }
            String build = readFirst(directory.resolve("build.gradle.kts"),
                    directory.resolve("build.gradle"));
            Map<String, String> own = assignments(build);
            String group = own.getOrDefault("group", rootAssignments.get("group"));
            String version = own.getOrDefault("version", rootAssignments.get("version"));
            String artifact = modulePath.equals(".") ? rootName : directory.getFileName().toString();
            if (unresolved(group) || unresolved(version)) {
                diagnostics.add("Static Gradle coordinates are incomplete for " + modulePath);
                complete = false;
            }
            modules.add(new Module(modulePath, group, artifact, version,
                    BuildSystem.GRADLE, "static_gradle_build_files"));
        }
        return new Result(modules, complete, diagnostics);
    }

    private static Map<String, String> assignments(String source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) return result;
        Matcher matcher = ASSIGNMENT.matcher(source);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2));
        return result;
    }

    private static String readFirst(Path... candidates) {
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String match(Pattern pattern, String source) {
        if (source == null) return null;
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Model readModel(Path pom) {
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException | RuntimeException failure) {
            return null;
        }
    }

    private static String interpolate(String value, Properties properties) {
        if (value == null) return null;
        String result = value;
        for (int pass = 0; pass < 10 && result.contains("${"); pass++) {
            String previous = result;
            for (String name : properties.stringPropertyNames()) {
                String replacement = properties.getProperty(name);
                if (replacement != null) {
                    result = result.replace("${" + name + "}", replacement);
                }
            }
            if (result.equals(previous)) break;
        }
        return result;
    }

    private static void put(Properties properties, String name, String value) {
        if (value != null) properties.setProperty(name, value);
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static boolean unresolved(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }

    private static String relative(Path root, Path module) {
        if (root.equals(module)) return ".";
        return root.relativize(module).toString().replace('\\', '/');
    }
}
