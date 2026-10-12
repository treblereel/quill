package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/** Captured build settings plus an explicit per-module analysis override. Never executes a build. */
final class KotlinCompilerSettings {
    static void apply(Path module, Path output, String sourceSet, ObjectNode request) throws IOException {
        var properties = new Properties();
        Path captured = output.resolve("quill-kotlin-" + sourceSet + ".properties");
        var fingerprints = request.putArray("compiler_settings_fingerprints");
        boolean supported = read(captured, properties, fingerprints);
        Path override = module.resolve(".quill/kotlin-" + sourceSet + ".properties");
        var settingsFiles = request.putArray("compiler_settings_files").add(captured.toString()).add(override.toString());
        supported &= read(override, properties, fingerprints);
        if (!supported) {
            request.put("compiler_configuration_error", "compiler_settings_protocol_unsupported");
            return;
        }
        if (!Files.isRegularFile(captured) && !Files.isRegularFile(override)) {
            Path pom = module.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("kotlin-maven-plugin")
                    || Files.isRegularFile(module.resolve("build.gradle")) || Files.isRegularFile(module.resolve("build.gradle.kts")))
                request.put("compiler_configuration_error", "compiler_settings_missing");
        }
        var buildInputs = request.putArray("compiler_build_inputs");
        int inputs = Integer.parseInt(properties.getProperty("buildInput.count", "0"));
        if (inputs < 0 || inputs > 1024) throw new IOException("Too many compiler build inputs");
        for (int i = 0; i < inputs; i++) {
            Path input = Path.of(properties.getProperty("buildInput.path." + i));
            settingsFiles.add(input.toString()); buildInputs.add(input.toString());
            String current = Files.isRegularFile(input) ? hash(Files.readAllBytes(input)) : "absent";
            fingerprints.addObject().put("path", input.toString()).put("sha256", properties.getProperty("buildInput.sha256." + i, ""));
            if (!current.equals(properties.getProperty("buildInput.sha256." + i))) request.put("compiler_configuration_error", "compiler_settings_stale");
        }
        if (properties.containsKey("configurationError")) request.put("compiler_configuration_error", "compiler_settings_unavailable");
        for (String key : new String[]{"languageVersion", "apiVersion", "jvmTarget", "compilerVersion"})
            if (properties.containsKey(key)) request.put(key, properties.getProperty(key));
        if (!request.has("languageVersion")) {
            var version = java.util.regex.Pattern.compile("^(\\d+\\.\\d+)(?:\\..*)?$")
                    .matcher(properties.getProperty("compilerVersion", ""));
            if (version.matches()) request.put("languageVersion", version.group(1));
        }
        copy(properties, "argument", request.putArray("compiler_arguments"), module, false);
        copy(properties, "pluginClasspath", request.putArray("plugin_classpath"), module, true);
        copy(properties, "pluginOption", request.putArray("plugin_options"), module, false);
        request.put("allow_compiler_plugins", Boolean.parseBoolean(properties.getProperty("allowPlugins", "false")));
        if (properties.containsKey("unsupportedPlugins")) request.put("unsupported_plugins", properties.getProperty("unsupportedPlugins"));
    }
    private static boolean read(Path path, Properties into, com.fasterxml.jackson.databind.node.ArrayNode fingerprints) throws IOException {
        byte[] bytes = Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        fingerprints.addObject().put("path", path.toString()).put("sha256", bytes == null ? "absent" : hash(bytes));
        if (bytes == null) return true;
        var values = new Properties();
        try (var reader = new java.io.StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))) { values.load(reader); }
        String protocol = values.getProperty("compilerSettingsProtocol", "0");
        if (!protocol.equals("0") && !protocol.equals("1")) return false;
        into.putAll(values);
        return true;
    }
    private static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void copy(Properties values, String prefix, com.fasterxml.jackson.databind.node.ArrayNode target,
                             Path module, boolean paths) throws IOException {
        int count = Integer.parseInt(values.getProperty(prefix + ".count", "0"));
        if (count < 0 || count > 1024) throw new IOException("Too many compiler settings");
        for (int i = 0; i < count; i++) {
            String value = values.getProperty(prefix + "." + i);
            if (value == null) throw new IOException("Incomplete compiler settings");
            target.add(paths ? module.resolve(value).toAbsolutePath().normalize().toString() : value);
        }
    }
}
