package org.treblereel.mcp.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.treblereel.mcp.core.BuildSystem;

/** Reads captured Java build evidence. It never starts Maven, Gradle or a compiler. */
public final class JavaCompilerSettings {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = List.of("buildTool", "sourceSet", "execution", "evidence", "compilerId",
            "compilerSettingsProtocol", "producerVersion", "compilerPluginVersion", "source", "target", "release", "enablePreview", "fork", "proc", "parameters",
            "encoding", "useModulePath", "modulePathEvidence", "processorPathEvidence", "jdkSelection", "jdkHome",
            "jdkVersion", "jdkVendor", "compilerExecutable", "requestedJdkVersion", "generatedSourcesDirectory", "legacyCompilerArgument");
    private static final List<String> LISTS = List.of("argument", "processor", "processorArtifact", "processorPath", "sourceRoot", "compileClasspath", "activeProfile");
    private JavaCompilerSettings() {}

    public static ArrayNode inspect(Collection<Path> modules, BuildSystem buildSystem) {
        ArrayNode result = JSON.createArrayNode();
        for (Path module : modules.stream().distinct().sorted().limit(100).toList()) {
            Path output = module.resolve(buildSystem == BuildSystem.MAVEN ? "target" : "build");
            var captures = new TreeMap<String, Path>();
            captures.put("main", output.resolve("quill-java-main.properties"));
            captures.put("test", output.resolve("quill-java-test.properties"));
            if (Files.isDirectory(output)) try (var files = Files.list(output)) {
                files.filter(path -> path.getFileName().toString().matches("quill-java-[A-Za-z0-9._-]+\\.properties"))
                        .sorted().limit(64).forEach(path -> {
                            String name = path.getFileName().toString();
                            captures.put(name.substring(11, name.length() - 11), path);
                        });
            } catch (IOException ignored) { }
            for (var capture : captures.entrySet()) result.add(read(module, capture.getKey(), capture.getValue()));
        }
        return result;
    }

    public static ObjectNode read(Path module, String sourceSet, Path capture) {
        ObjectNode result = JSON.createObjectNode().put("module", module.toString()).put("source_set", sourceSet)
                .put("path", capture.toString());
        if (!Files.isRegularFile(capture)) return result.put("status", "missing").put("usable", false);
        try {
            if (Files.size(capture) > 4 * 1024 * 1024) throw new IOException("Compiler settings exceed 4 MiB");
            Properties values = new Properties(); try (var reader = Files.newBufferedReader(capture)) { values.load(reader); }
            if (values.containsKey("compilationProtocol")) result.put("compilation_protocol", values.getProperty("compilationProtocol"));
            int protocol = Integer.parseInt(values.getProperty("compilerSettingsProtocol", "0"));
            result.put("compiler_settings_protocol", protocol);
            if (protocol != 0 && protocol != 1) return result.put("status", "unsupported_protocol").put("usable", false);
            if (!sourceSet.equals(values.getProperty("sourceSet"))) throw new IOException("Source-set identity mismatch");
            ObjectNode settings = result.putObject("settings");
            for (String field : FIELDS) if (values.containsKey(field)) settings.put(field, values.getProperty(field));
            for (String list : LISTS) {
                ArrayNode items = settings.putArray(list);
                int count = count(values, list + ".count", 4096);
                for (int i = 0; i < count; i++) items.add(required(values, list + "." + i));
            }
            int count = count(values, "buildInput.count", 4096);
            if (count == 0) throw new IOException("Compiler settings have no build fingerprints");
            ArrayNode stale = result.putArray("stale_inputs");
            for (int i = 0; i < count; i++) {
                Path input = Path.of(required(values, "buildInput.path." + i));
                String expected = required(values, "buildInput.sha256." + i);
                if (!expected.equals("absent") && !expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid build fingerprint");
                if (!expected.equals(hash(input))) stale.add(input.toString());
            }
            String evidence = values.getProperty("evidence", "");
            if (!Set.of("successful_mojo", "successful_task", "configured_task").contains(evidence)) throw new IOException("Unknown capture evidence");
            if (values.containsKey("compilationProtocol")) {
                var compilation = result.putObject("compilation");
                compilation.set("active_profiles", settings.path("activeProfile").deepCopy());
                compilation.put("source_set", sourceSet);
                compilation.put("evidence", values.getProperty("compilationEvidence", "unknown"));
                var languages = compilation.putArray("required_languages");
                int languageCount = count(values, "compilationLanguage.count", 8);
                for (int i=0;i<languageCount;i++) {
                    String language = required(values, "compilationLanguage."+i);
                    if (!Set.of("java", "kotlin").contains(language)) throw new IOException("Unknown compiled language");
                    languages.add(language);
                }
                compilation.put("build_tool", values.getProperty("buildTool", "unknown"));
                if (values.containsKey("buildVariantSha256")) compilation.put("variant_sha256", values.getProperty("buildVariantSha256"));
                compilation.put("compiled_at", values.getProperty("compiledAt", "unknown"));
                boolean matching = values.getProperty("compilationProtocol").equals("1")
                        && values.getProperty("compilationEvidence", "").equals("matching")
                        && Files.isSameFile(module, Path.of(required(values, "compilationModule")))
                        && stale.isEmpty() && !evidence.equals("configured_task");
                var changed = compilation.putArray("changed_inputs");
                int roots = count(values, "compilationRoot.count", 4096);
                if (roots == 0) matching = false;
                for (int i = 0; i < roots; i++) {
                    Path input = Path.of(required(values, "compilationRoot.path." + i));
                    if (!required(values, "compilationRoot.sha256." + i).equals(treeHash(input))) {
                        matching = false; changed.add(input.toString());
                    }
                }
                Path output = Path.of(required(values, "compiledOutput.path"));
                compilation.put("output", output.toString());
                if (!required(values, "compiledOutput.sha256").equals(treeHash(output))) {
                    matching = false; changed.add(output.toString());
                }
                stale.forEach(changed::add);
                compilation.put("status", matching ? "matching" : "changed");
            }
            boolean fresh = stale.isEmpty();
            return result.put("status", !fresh ? "stale" : evidence.equals("configured_task") ? "configuration_only" : "captured")
                    .put("usable", fresh).put("compiled_settings_confirmed", fresh && !evidence.equals("configured_task"));
        } catch (IOException | RuntimeException failure) {
            return result.put("status", "invalid").put("usable", false).put("detail", failure.getMessage());
        }
    }
    static String treeHash(Path root) throws IOException {
        if (!Files.exists(root)) return "absent";
        if (Files.isRegularFile(root)) return hash(root);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            Path directory = root.toRealPath();
            try (var files = Files.walk(directory, FileVisitOption.FOLLOW_LINKS)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(directory.relativize(file).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) 0); digest.update(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static int count(Properties values, String key, int limit) throws IOException {
        int count = Integer.parseInt(values.getProperty(key, "0"));
        if (count < 0 || count > limit) throw new IOException("Invalid compiler settings count: " + key);
        return count;
    }
    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key); if (value == null) throw new IOException("Missing compiler setting: " + key); return value;
    }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
