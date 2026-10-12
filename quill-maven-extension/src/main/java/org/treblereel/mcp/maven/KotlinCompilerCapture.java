package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.execution.ExecutionEvent;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Captures the effective configuration of a Kotlin compile mojo, after Maven has merged execution settings. */
final class KotlinCompilerCapture {
    static void capture(ExecutionEvent event) throws IOException {
        var mojo = event.getMojoExecution();
        if (mojo == null || !"org.jetbrains.kotlin".equals(mojo.getGroupId())
                || !"kotlin-maven-plugin".equals(mojo.getArtifactId())
                || !(mojo.getGoal().equals("compile") || mojo.getGoal().equals("test-compile"))) return;
        var project = event.getProject();
        if (project == null || project.getBasedir() == null) return;
        var plugins = new ArrayList<Path>();
        var descriptor = mojo.getMojoDescriptor();
        if (descriptor != null && descriptor.getPluginDescriptor() != null && descriptor.getPluginDescriptor().getClassRealm() != null)
            for (var url : descriptor.getPluginDescriptor().getClassRealm().getURLs()) {
                if (!url.getProtocol().equals("file")) continue;
                try {
                    Path path = Path.of(url.toURI());
                    if (path.getFileName().toString().startsWith("kotlin-maven-") && !path.getFileName().toString().startsWith("kotlin-maven-plugin-")) plugins.add(path);
                } catch (java.net.URISyntaxException ignored) { }
            }
        Properties settings = settings(mojo.getConfiguration(), mojo.getVersion(), plugins);
        Path target = project.getBasedir().toPath().resolve("target/quill-kotlin-" + (mojo.getGoal().equals("test-compile") ? "test" : "main") + ".properties");
        var inputs = new LinkedHashSet<Path>();
        for (Path directory = project.getBasedir().toPath(); directory != null; directory = directory.getParent()) {
            inputs.add(directory.resolve("pom.xml"));
            inputs.add(directory.resolve(".mvn/maven.config"));
            if (event.getSession().getRequest().getMultiModuleProjectDirectory() != null
                    && directory.equals(event.getSession().getRequest().getMultiModuleProjectDirectory().toPath())) break;
        }
        for (var parent = project.getParent(); parent != null; parent = parent.getParent())
            if (parent.getFile() != null) inputs.add(parent.getFile().toPath());
        settings.setProperty("buildInput.count", Integer.toString(inputs.size())); int index = 0;
        for (Path input : inputs) {
            settings.setProperty("buildInput.path." + index, input.toAbsolutePath().normalize().toString());
            settings.setProperty("buildInput.sha256." + index++, hash(input));
        }
        write(target, settings);
    }
    static Properties settings(Xpp3Dom config, String version, List<Path> plugins) {
        var result = new Properties();
        if (version != null) result.setProperty("compilerVersion", version);
        if (config == null) return result;
        for (String field : List.of("languageVersion", "apiVersion", "jvmTarget")) {
            var node = config.getChild(field);
            if (node != null && node.getValue() != null) result.setProperty(field, node.getValue());
        }
        var args = config.getChild("args");
        put(result, "argument", args == null ? List.of() : Arrays.stream(args.getChildren()).map(Xpp3Dom::getValue).filter(Objects::nonNull).toList());
        var options = new ArrayList<String>(); var unknown = new ArrayList<String>();
        var enabled = config.getChild("compilerPlugins");
        if (enabled != null) for (var node : enabled.getChildren()) {
            String name = node.getValue();
            if (name == null) continue;
            String id = pluginId(name);
            if (id == null) unknown.add(name);
            else if (name.equals("spring") || name.equals("jpa")) options.add("plugin:" + id + ":preset=" + name);
        }
        var configured = config.getChild("pluginOptions");
        if (configured != null) for (var node : configured.getChildren()) {
            String option = node.getValue(); if (option == null) continue;
            int colon = option.indexOf(':'); String id = colon < 0 ? null : pluginId(option.substring(0, colon));
            if (id == null) unknown.add(option); else options.add("plugin:" + id + option.substring(colon));
        }
        put(result, "pluginOption", options);
        put(result, "pluginClasspath", plugins.stream().map(Path::toString).toList());
        if (!unknown.isEmpty() || enabled != null && enabled.getChildCount() > 0 && plugins.isEmpty())
            result.setProperty("unsupportedPlugins", String.join(",", unknown.isEmpty() ? List.of("plugin artifacts missing") : unknown));
        return result;
    }
    private static String pluginId(String name) {
        return switch (name) {
            case "all-open", "spring" -> "org.jetbrains.kotlin.allopen";
            case "no-arg", "jpa" -> "org.jetbrains.kotlin.noarg";
            case "sam-with-receiver" -> "org.jetbrains.kotlin.samWithReceiver";
            case "kotlinx-serialization", "serialization" -> "org.jetbrains.kotlinx.serialization";
            default -> null;
        };
    }
    private static void put(Properties values, String prefix, List<String> items) {
        values.setProperty(prefix + ".count", Integer.toString(items.size()));
        for (int i = 0; i < items.size(); i++) values.setProperty(prefix + "." + i, items.get(i));
    }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static void write(Path target, Properties settings) throws IOException {
        if (Files.isRegularFile(target)) {
            var previous = new Properties(); try (var reader = Files.newBufferedReader(target)) { previous.load(reader); }
            if (previous.equals(settings)) return;
        }
        Files.createDirectories(target.getParent()); Path temporary = Files.createTempFile(target.getParent(), ".kotlin-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { settings.store(writer, "Quill compiler settings"); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
