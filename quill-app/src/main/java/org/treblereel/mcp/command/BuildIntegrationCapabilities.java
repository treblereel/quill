package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.treblereel.mcp.core.BuildSystem;

/** Interprets an observed producer contract; missing manifests do not disable legacy bytecode analysis. */
public final class BuildIntegrationCapabilities {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN = Set.of("build_events", "runtime_classpath", "kotlin_compiler_settings", "java_compiler_settings");
    private BuildIntegrationCapabilities() {}

    public static ObjectNode inspect(Path root, BuildSystem tool) {
        Path file = manifest(root, tool);
        ObjectNode result = JSON.createObjectNode().put("path", file.toString());
        var available = result.putArray("available_capabilities");
        ObjectNode features = result.putObject("features");
        KNOWN.stream().sorted().forEach(key -> features.put(key, "unknown"));
        if (!Files.isRegularFile(file)) return result.put("status", "legacy");
        try {
            if (Files.size(file) > 64 * 1024) throw new IOException("Capability manifest exceeds 64 KiB");
            var values = new Properties(); try (var reader = Files.newBufferedReader(file)) { values.load(reader); }
            int version = Integer.parseInt(required(values, "manifestVersion"));
            result.put("manifest_version", version);
            if (version != 1) {
                KNOWN.forEach(key -> features.put(key, "unsupported_manifest"));
                return result.put("status", "unsupported_manifest");
            }
            if (!tool.name().equalsIgnoreCase(required(values, "buildTool"))) throw new IOException("Build-tool identity mismatch");
            result.put("producer", required(values, "producer")); result.put("producer_version", required(values, "producerVersion"));
            int events = Integer.parseInt(required(values, "buildEventProtocol"));
            int compiler = Integer.parseInt(required(values, "compilerSettingsProtocol"));
            if (events <= 0 || compiler <= 0) throw new IOException("Invalid producer protocol");
            result.put("build_event_protocol", events); result.put("compiler_settings_protocol", compiler);
            int count = Integer.parseInt(required(values, "capability.count"));
            if (count < 0 || count > 128) throw new IOException("Invalid capability count");
            var declared = new TreeSet<String>();
            var advertised = result.putArray("advertised_capabilities");
            var unknown = result.putArray("unknown_capabilities");
            for (int i = 0; i < count; i++) {
                String capability = required(values, "capability." + i);
                if (!capability.matches("[a-z][a-z0-9_]{0,99}") || !declared.add(capability)) throw new IOException("Invalid or duplicate capability");
                advertised.add(capability); if (!KNOWN.contains(capability)) unknown.add(capability);
            }
            Path configuration = Path.of(required(values, "configuration.path"));
            String expected = required(values, "configuration.sha256");
            if (!expected.equals("absent") && !expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid configuration fingerprint");
            boolean fresh = expected.equals(hash(configuration));
            boolean unsupported = false;
            for (String capability : KNOWN.stream().sorted().toList()) {
                boolean supported = switch (capability) {
                    case "build_events" -> events == 3;
                    case "runtime_classpath" -> true;
                    default -> compiler == 1;
                };
                String status = !declared.contains(capability) ? "not_advertised" : !fresh ? "stale" : !supported ? "unsupported_protocol" : "available";
                features.put(capability, status);
                if (status.equals("available")) available.add(capability);
                if (status.equals("unsupported_protocol")) unsupported = true;
            }
            return result.put("status", !fresh ? "stale" : unsupported ? "partially_supported" : "supported");
        } catch (IOException | RuntimeException failure) {
            available.removeAll(); KNOWN.forEach(key -> features.put(key, "invalid"));
            return result.put("status", "invalid").put("detail", failure.getMessage());
        }
    }
    private static Path manifest(Path root, BuildSystem tool) {
        Path original = root.toAbsolutePath().normalize().resolve(".quill/build-integration.properties");
        for (Path directory = root.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(".quill/build-integration.properties");
            if (Files.isRegularFile(candidate)) return candidate;
            if (Files.exists(directory.resolve(".git")) || tool == BuildSystem.MAVEN && Files.isDirectory(directory.resolve(".mvn"))) break;
        }
        return original;
    }
    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Missing protocol field: " + key); return value;
    }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
