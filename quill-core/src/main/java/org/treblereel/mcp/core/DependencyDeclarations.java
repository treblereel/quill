package org.treblereel.mcp.core;

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
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3ReaderEx;
import org.treblereel.mcp.diagnostics.QuillLog;

/**
 * Where a module's direct Maven dependencies are declared (file:line of their artifactId, the
 * module's POM or a parent's) and where each version comes from: an explicit version, a property,
 * a dependencyManagement entry, or the imported BOM in the local repository that manages it.
 * Answers read pom.xml for exactly this to cite it.
 */
public final class DependencyDeclarations {

    public record Declaration(List<String> declaredAt, String versionSource) {
        public Declaration {
            declaredAt = List.copyOf(declaredAt);
        }
    }

    private record Pom(Path file, Model model) {}

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private DependencyDeclarations() {
    }

    public static Map<String, Declaration> locate(Path root, String module, Path repository) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path start = (module == null || module.equals(".") ? normalizedRoot : normalizedRoot.resolve(module)).resolve("pom.xml");
        List<Pom> chain = chain(start);
        if (chain.isEmpty()) return Map.of();
        Properties properties = new Properties();
        Map<String, String> propertyLocations = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Pom pom = chain.get(i);
            Model model = pom.model();
            if (model.getVersion() != null) properties.setProperty("project.version", model.getVersion());
            InputLocation locations = model.getLocation("properties");
            for (String name : model.getProperties().stringPropertyNames()) {
                properties.setProperty(name, model.getProperties().getProperty(name));
                InputLocation location = locations == null ? null : locations.getLocation(name);
                if (location != null) propertyLocations.put(name, where(normalizedRoot, pom.file(), location));
            }
        }
        Map<String, String> managed = new LinkedHashMap<>();
        List<String[]> imports = new ArrayList<>();
        for (Pom pom : chain) {
            if (pom.model().getDependencyManagement() == null) continue;
            for (Dependency dependency : pom.model().getDependencyManagement().getDependencies()) {
                String ga = interpolate(dependency.getGroupId(), properties) + ":" + interpolate(dependency.getArtifactId(), properties);
                if ("import".equals(dependency.getScope()) && "pom".equals(dependency.getType())) {
                    imports.add(new String[] {ga + ":" + interpolate(dependency.getVersion(), properties),
                            where(normalizedRoot, pom.file(), dependency.getLocation("artifactId")),
                            propertyNote(dependency.getVersion(), propertyLocations, false)});
                } else managed.putIfAbsent(ga, versionOf(dependency, pom, normalizedRoot, properties, propertyLocations, "managed in "));
            }
        }
        Map<String, List<String>> declared = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (Pom pom : chain) {
            List<Dependency> dependencies = new ArrayList<>(pom.model().getDependencies());
            for (Profile profile : pom.model().getProfiles()) dependencies.addAll(profile.getDependencies());
            for (Dependency dependency : dependencies) {
                String ga = interpolate(dependency.getGroupId(), properties) + ":" + interpolate(dependency.getArtifactId(), properties);
                declared.computeIfAbsent(ga, ignored -> new ArrayList<>()).add(where(normalizedRoot, pom.file(), dependency.getLocation("artifactId")));
                if (versions.containsKey(ga)) continue;
                if (dependency.getVersion() != null) versions.put(ga, versionOf(dependency, pom, normalizedRoot, properties, propertyLocations, ""));
                else if (managed.containsKey(ga)) versions.put(ga, managed.get(ga));
                else versions.put(ga, bom(ga, imports, repository));
            }
        }
        Map<String, Declaration> result = new LinkedHashMap<>();
        declared.forEach((ga, places) -> result.put(ga, new Declaration(places, versions.get(ga))));
        return result;
    }

    private static String versionOf(Dependency dependency, Pom pom, Path root, Properties properties,
            Map<String, String> propertyLocations, String prefix) {
        String version = dependency.getVersion();
        if (version == null) return null;
        String note = propertyNote(version, propertyLocations, true);
        if (note != null) {
            Matcher matcher = PROPERTY.matcher(version);
            matcher.find();
            return prefix + note.replace("version property " + matcher.group(1),
                    "version property " + matcher.group(1) + "=" + interpolate(version, properties));
        }
        return prefix + "version " + version + " at " + where(root, pom.file(), dependency.getLocation("version"));
    }

    /** "version property name at file:line" when the version is one property. */
    private static String propertyNote(String version, Map<String, String> locations, boolean bare) {
        if (version == null) return null;
        Matcher matcher = PROPERTY.matcher(version);
        if (!matcher.matches()) return null;
        String location = locations.get(matcher.group(1));
        return location == null ? null : "version property " + matcher.group(1) + " at " + location;
    }

    private static String bom(String ga, List<String[]> imports, Path repository) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String[] imported : imports) {
            String[] coordinates = imported[0].split(":");
            if (coordinates.length != 3 || repository == null) continue;
            Path pom = repository.resolve(coordinates[0].replace('.', '/')).resolve(coordinates[1]).resolve(coordinates[2])
                    .resolve(coordinates[1] + "-" + coordinates[2] + ".pom");
            Model model = read(pom);
            if (model == null || model.getDependencyManagement() == null) {
                candidates.add(imported[0] + " at " + imported[1]);
                continue;
            }
            boolean manages = model.getDependencyManagement().getDependencies().stream()
                    .anyMatch(dependency -> ga.equals(dependency.getGroupId() + ":" + dependency.getArtifactId()));
            if (manages) return "managed by imported BOM " + imported[0] + " at " + imported[1]
                    + (imported[2] == null ? "" : " (" + imported[2] + ")");
        }
        return candidates.isEmpty() ? null : "managed by one of the imported BOMs " + String.join(", ", candidates);
    }

    private static List<Pom> chain(Path start) {
        List<Pom> chain = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        Path current = start.toAbsolutePath().normalize();
        while (current != null && Files.isRegularFile(current) && visited.add(current)) {
            Model model = readWithLocations(current);
            if (model == null) break;
            chain.add(new Pom(current, model));
            if (model.getParent() == null) break;
            String relative = model.getParent().getRelativePath();
            if (relative == null || relative.isBlank()) relative = "../pom.xml";
            Path parent = current.getParent().resolve(relative).normalize();
            current = Files.isDirectory(parent) ? parent.resolve("pom.xml") : parent;
        }
        return chain;
    }

    private static Model readWithLocations(Path pom) {
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3ReaderEx().read(reader, false, new InputSource());
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return null;
        }
    }

    private static Model read(Path pom) {
        if (!Files.isRegularFile(pom)) return null;
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader, false);
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return null;
        }
    }

    private static String where(Path root, Path file, InputLocation location) {
        String path = root.relativize(file).toString();
        return location == null ? path : path + ":" + location.getLineNumber();
    }

    private static String interpolate(String value, Properties properties) {
        if (value == null) return null;
        Matcher matcher = PROPERTY.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result,
                Matcher.quoteReplacement(properties.getProperty(matcher.group(1), matcher.group())));
        matcher.appendTail(result);
        return result.toString();
    }
}
