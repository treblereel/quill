package org.treblereel.mcp.javac;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Probe-only line transport. Query: base64 UTF-8 file path, tab, UTF-16 offset. */
public final class ProbeMain {
    private static String encode(String value) {
        return value == null ? "-" : Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        List<Path> classpath = args[1].isEmpty() ? List.of() : java.util.Arrays.stream(
                args[1].split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).toList();
        try (var model = new JavaPositionResolver(List.of(Path.of(args[0])), classpath, args[2]);
             var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("READY");
            for (String line; (line = reader.readLine()) != null;) {
                var parts = line.split("\t", -1);
                long start = System.nanoTime();
                try {
                    if (parts.length != 2) throw new IllegalArgumentException("Invalid probe request");
                    Path file = Path.of(new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8));
                    var result = model.resolve(file, Integer.parseInt(parts[1]));
                    System.out.println(String.join("\t", result.status(), encode(result.owner()), encode(result.kind()),
                            encode(result.name()), encode(result.descriptor()), Long.toString(result.generation()),
                            Boolean.toString(result.reused()), Double.toString((System.nanoTime()-start)/1_000_000.0),
                            Long.toString(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())));
                } catch (Exception error) {
                    System.out.println("ERROR\t" + error.getClass().getSimpleName());
                }
            }
        }
    }
}
