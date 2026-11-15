package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.project.ProjectDependencyQueries;
import org.treblereel.mcp.core.Json;

/**
 * The configuration phase, default and declaring extension of framework keys, read from the
 * {@code META-INF/quarkus-config-doc/quarkus-config-model.json} each Quarkus extension jar on the
 * project's classpath ships. Whether a key is fixed at build time is otherwise a guess from docs.
 */
final class QuarkusConfigPhases {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final String MODEL = "META-INF/quarkus-config-doc/quarkus-config-model.json";
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private QuarkusConfigPhases() {
    }

    /** One line per key the classpath's extensions document, sorted by key; unknown keys are left out. */
    static List<String> lookup(ReadQueryContext reader, Path root, Collection<String> keys) {
        if (keys.stream().noneMatch(key -> key.startsWith("quarkus."))) return List.of();
        List<Path> jars = ProjectDependencyQueries.resolvedJars(reader, root.toAbsolutePath().normalize());
        String fingerprint = jars.stream().map(Path::toString).reduce("", (left, right) -> left + "|" + right);
        Map<String, String> documented = CACHE.computeIfAbsent(fingerprint, ignored -> {
            if (CACHE.size() > 8) CACHE.clear();
            return read(jars);
        });
        Map<String, String> found = new TreeMap<>();
        for (String key : keys) {
            String line = documented.get(key);
            if (line == null) for (var entry : documented.entrySet())
                if (entry.getKey().contains("\"") && pattern(entry.getKey()).matcher(key).matches()) { line = entry.getValue(); break; }
            if (line != null) found.put(key, key + ": " + line);
        }
        return List.copyOf(found.values());
    }

    /** A documented map key such as quarkus.cache.caffeine."cache-name".maximum-size matches any one name there. */
    private static Pattern pattern(String documented) {
        StringBuilder regex = new StringBuilder();
        for (String part : documented.split("\"[^\"]*\"", -1)) {
            if (!regex.isEmpty()) regex.append("[^.]+");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private static Map<String, String> read(List<Path> jars) {
        Map<String, String> documented = new java.util.HashMap<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile file = new JarFile(jar.toFile())) {
                var entry = file.getJarEntry(MODEL);
                if (entry == null) continue;
                String coordinates = ProjectDependencyQueries.coordinates(jar).id();
                JsonNode model;
                try (InputStream input = file.getInputStream(entry)) { model = JSON.readTree(input); }
                List<JsonNode> properties = new ArrayList<>();
                collect(model, properties);
                for (JsonNode property : properties) {
                    String key = property.path("path").path("property").asText();
                    String line = property.path("phase").asText()
                            + (property.hasNonNull("defaultValue") ? " default=" + property.path("defaultValue").asText() : "")
                            + " (" + coordinates + ")";
                    documented.putIfAbsent(key, line);
                }
            } catch (Exception unreadable) {
                // A jar without a readable model documents nothing; other jars still do.
            }
        }
        return documented;
    }

    private static void collect(JsonNode node, List<JsonNode> properties) {
        if (node.isObject()) {
            if (node.path("path").path("property").isTextual() && node.has("phase")) properties.add(node);
            node.forEach(child -> collect(child, properties));
        } else if (node.isArray()) node.forEach(child -> collect(child, properties));
    }
}
