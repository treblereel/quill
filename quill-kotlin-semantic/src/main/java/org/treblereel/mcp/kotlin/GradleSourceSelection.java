package org.treblereel.mcp.kotlin;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Replays declarative Gradle file trees. Executable predicates use a guarded build snapshot. */
public final class GradleSourceSelection {
    public static final class Invalid extends IOException {
        public final String status;
        Invalid(String status) { super(status); this.status = status; }
    }
    public record Result(List<Path> kotlin, List<Path> java, List<Path> roots) {}
    private GradleSourceSelection() {}

    public static Result collect(Path repository, JsonNode selection) throws IOException {
        if (!selection.isObject() || selection.path("protocol").asInt(-1) != 1)
            throw new Invalid("source_selection_protocol_unsupported");
        JsonNode entries = selection.path("entries");
        if (!entries.isArray() || entries.size() > 1024) throw new Invalid("source_selection_invalid");
        Path root = repository.toRealPath();
        var kotlin = new TreeSet<Path>(); var java = new TreeSet<Path>(); var roots = new TreeSet<Path>();
        var observed = new TreeMap<String, String>();
        boolean snapshot = selection.path("snapshot").asBoolean();
        for (JsonNode entry : entries) {
            String kind = entry.path("kind").asText();
            if (!kind.equals("kotlin") && !kind.equals("java")) throw new Invalid("source_selection_invalid");
            if (entry.has("root") == entry.has("file")) throw new Invalid("source_selection_invalid");
            Path path = canonical(Path.of(entry.path(entry.has("root") ? "root" : "file").asText()));
            if (!path.startsWith(root)) throw new Invalid("source_outside_root");
            if (entry.has("file")) {
                roots.add(path.getParent());
                if (snapshot) observed.put(path.toString(), hash(path));
                if (Files.isRegularFile(path)) add(kind, path, kotlin, java);
            } else {
                boolean singleFile = Files.isRegularFile(path);
                roots.add(singleFile ? path.getParent() : path);
                JsonNode gates = entry.path("gates");
                if (!gates.isArray() || gates.size() > 64) throw new Invalid("source_selection_invalid");
                validatePatterns(entry.path("defaultExcludes"));
                for (JsonNode gate : gates) { validatePatterns(gate.path("includes")); validatePatterns(gate.path("excludes")); }
                if (!singleFile && !Files.isDirectory(path)) continue;
                try (var files = singleFile ? Stream.of(path) : Files.walk(path, FileVisitOption.FOLLOW_LINKS)) {
                    var iterator = files.filter(Files::isRegularFile).iterator();
                    while (iterator.hasNext()) {
                        Path candidate = iterator.next();
                        String relative = (singleFile ? candidate.getFileName() : path.relativize(candidate)).toString().replace('\\', '/');
                        if (matches(entry.path("defaultExcludes"), relative, true)) continue;
                        Path actual = candidate.toRealPath();
                        if (!actual.startsWith(root)) throw new Invalid("source_outside_root");
                        if (snapshot) observed.put(actual.toString(), hash(actual));
                        boolean included = true;
                        for (JsonNode gate : gates) {
                            boolean sensitive = gate.path("caseSensitive").asBoolean(true);
                            if (!gate.path("includes").isEmpty() && !matches(gate.path("includes"), relative, sensitive)
                                    || matches(gate.path("excludes"), relative, sensitive)) { included = false; break; }
                        }
                        if (included) add(kind, actual, kotlin, java);
                        if (kotlin.size() + java.size() > 5000 || observed.size() > 20_000) throw new Invalid("source_limit");
                    }
                } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
            }
        }
        if (snapshot) {
            var expected = new TreeMap<String, String>();
            JsonNode guard = selection.path("guard");
            if (!guard.isArray() || guard.size() > 20_000) throw new Invalid("source_selection_invalid");
            for (JsonNode item : guard) {
                Path path = canonical(Path.of(item.path("path").asText()));
                if (!path.startsWith(root)) throw new Invalid("source_outside_root");
                expected.put(path.toString(), item.path("sha256").asText());
            }
            if (!observed.equals(expected)) throw new Invalid("source_selection_refresh_required");
            kotlin = selected(selection.path("selected").path("kotlin"), root, ".kt");
            java = selected(selection.path("selected").path("java"), root, ".java");
        }
        if (kotlin.size() + java.size() > 5000) throw new Invalid("source_limit");
        return new Result(List.copyOf(kotlin), List.copyOf(java), List.copyOf(roots));
    }

    private static TreeSet<Path> selected(JsonNode files, Path root, String suffix) throws IOException {
        if (!files.isArray() || files.size() > 5000) throw new Invalid("source_selection_invalid");
        var result = new TreeSet<Path>();
        for (JsonNode file : files) {
            Path path = canonical(Path.of(file.asText()));
            if (!path.startsWith(root)) throw new Invalid("source_outside_root");
            if (!Files.isRegularFile(path) || !path.toString().endsWith(suffix)) throw new Invalid("source_selection_refresh_required");
            result.add(path);
        }
        return result;
    }

    private static void add(String kind, Path path, Set<Path> kotlin, Set<Path> java) {
        if (kind.equals("kotlin") && path.toString().endsWith(".kt")) kotlin.add(path);
        if (kind.equals("java") && path.toString().endsWith(".java")) java.add(path);
    }
    private static void validatePatterns(JsonNode patterns) throws Invalid {
        if (!patterns.isArray() || patterns.size() > 1024) throw new Invalid("source_selection_invalid");
        for (JsonNode pattern : patterns) if (!pattern.isTextual() || pattern.asText().length() > 4096)
            throw new Invalid("source_selection_invalid");
    }
    private static boolean matches(JsonNode patterns, String path, boolean sensitive) {
        for (JsonNode pattern : patterns) if (ant(pattern.asText(), path, sensitive)) return true;
        return false;
    }
    static boolean ant(String pattern, String path, boolean sensitive) {
        pattern = pattern.replace('\\', '/');
        if (pattern.endsWith("/")) pattern += "**";
        String[] parts = pattern.split("/", -1), names = path.split("/", -1);
        boolean[][] matches = new boolean[parts.length + 1][names.length + 1]; matches[0][0] = true;
        for (int p = 0; p < parts.length; p++) {
            Pattern segment = null;
            if (!parts[p].equals("**")) {
                var regex = new StringBuilder();
                for (char character : parts[p].toCharArray()) regex.append(character == '*' ? ".*" : character == '?' ? "." : Pattern.quote(String.valueOf(character)));
                segment = Pattern.compile(regex.toString(), Pattern.DOTALL | (sensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            for (int n = 0; n <= names.length; n++) {
                if (parts[p].equals("**")) {
                    matches[p + 1][n] |= matches[p][n];
                    if (n > 0) matches[p + 1][n] |= matches[p + 1][n - 1];
                } else if (n < names.length && matches[p][n] && segment.matcher(names[n]).matches()) matches[p + 1][n + 1] = true;
            }
        }
        return matches[parts.length][names.length];
    }
    private static Path canonical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent())
            if (Files.exists(ancestor)) return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
        return path;
    }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[16_384]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
