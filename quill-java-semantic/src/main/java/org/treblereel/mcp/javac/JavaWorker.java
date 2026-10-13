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
        Properties settings = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { settings.load(reader); }
        try (var model = new JavaPositionResolver(paths(settings, "roots"), paths(settings, "classpath"), settings.getProperty("release"), settings.getProperty("source"), settings.getProperty("target"));
             var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("QUILL_JAVA_1");
            for (String line; (line = reader.readLine()) != null;) {
                try {
                    var parts = line.split("\t", -1);
                    if (parts.length != 2) throw new IllegalArgumentException("Invalid request");
                    Path file = Path.of(new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8));
                    int offset = Integer.parseInt(parts[1]);
                    var result = offset == -1 ? model.warm(file) : model.resolve(file, offset);
                    System.out.println(String.join("\t", result.status(), encode(result.owner()), encode(result.kind()),
                            encode(result.name()), encode(result.descriptor()), encode(result.declarationFile()),
                            Long.toString(result.generation()), Boolean.toString(result.reused())));
                } catch (Exception error) { System.out.println("ERROR\t" + error.getClass().getSimpleName()); }
            }
        }
    }
}
