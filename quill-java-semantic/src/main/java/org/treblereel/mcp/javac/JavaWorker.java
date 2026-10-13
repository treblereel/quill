package org.treblereel.mcp.javac;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned, dependency-free transport. No build code or annotation processors are executed. */
public final class JavaWorker {
    private static String encode(String value) {
        return value == null ? "-" : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static List<Path> paths(Properties settings, String prefix) {
        int count = Integer.parseInt(settings.getProperty(prefix + ".count"));
        if (count < 0 || count > 8192) throw new IllegalArgumentException("Input limit");
        List<Path> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(Path.of(settings.getProperty(prefix + "." + i)));
        return result;
    }
    public static void main(String[] args) throws Exception {
        var models = new LinkedHashMap<String, JavaPositionResolver>(4, 0.75f, true);
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("QUILL_JAVA_2");
            for (String line; (line = reader.readLine()) != null;) {
                try {
                    var parts = line.split("\t", -1);
                    if (parts.length != 4 || line.length() > 1_500_000) throw new IllegalArgumentException("Invalid request");
                    String key = decode(parts[0]);
                    JavaPositionResolver model = models.get(key);
                    if (model == null) {
                        Properties settings = new Properties();
                        settings.load(new StringReader(decode(parts[1])));
                        model = new JavaPositionResolver(paths(settings, "roots"), paths(settings, "classpath"),
                                settings.getProperty("release"), settings.getProperty("source"), settings.getProperty("target"));
                        models.put(key, model);
                        if (models.size() > 2) {
                            var eldest = models.entrySet().iterator().next();
                            eldest.getValue().close(); models.remove(eldest.getKey());
                        }
                    }
                    Path file = Path.of(decode(parts[2]));
                    int offset = Integer.parseInt(parts[3]);
                    var result = offset == -1 ? model.warm(file) : model.resolve(file, offset);
                    System.out.println(String.join("\t", result.status(), encode(result.owner()), encode(result.kind()),
                            encode(result.name()), encode(result.descriptor()), encode(result.declarationFile()),
                            Long.toString(result.generation()), Boolean.toString(result.reused())));
                } catch (Exception error) { System.out.println("ERROR\t" + error.getClass().getSimpleName()); }
            }
        } finally {
            for (var model : models.values()) model.close();
        }
    }
    private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}
