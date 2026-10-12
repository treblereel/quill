package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Initialization reuses the already generated effective POM; queries never evaluate Maven. */
final class MavenKotlinSettings {
    static void capture(Path effectivePom, Path root, Map<String, Path> modules) throws IOException, XmlPullParserException {
        Xpp3Dom document; try (var reader = Files.newBufferedReader(effectivePom)) { document = Xpp3DomBuilder.build(reader); }
        var projects = document.getName().equals("project") ? new Xpp3Dom[]{document} : document.getChildren("project");
        for (var project : projects) {
            Path module = modules.get(value(project, "groupId") + ":" + value(project, "artifactId") + ":" + value(project, "version"));
            var build = project.getChild("build"); var plugins = build == null ? null : build.getChild("plugins");
            if (module == null || plugins == null) continue;
            for (var plugin : plugins.getChildren("plugin")) {
                if (!"org.jetbrains.kotlin".equals(value(plugin, "groupId")) || !"kotlin-maven-plugin".equals(value(plugin, "artifactId"))) continue;
                for (String set : List.of("main", "test")) {
                    var configurations = new ArrayList<Xpp3Dom>(); var executions = plugin.getChild("executions");
                    if (executions != null) for (var execution : executions.getChildren("execution")) {
                        var goals = execution.getChild("goals");
                        if (goals != null && Arrays.stream(goals.getChildren()).anyMatch(goal -> (set.equals("main") ? "compile" : "test-compile").equals(goal.getValue()))) {
                            var config = execution.getChild("configuration"); var base = plugin.getChild("configuration");
                            configurations.add(config == null ? base : Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(config), base == null ? null : new Xpp3Dom(base)));
                        }
                    }
                    if (configurations.isEmpty()) configurations.add(plugin.getChild("configuration"));
                    Properties settings = options(configurations.get(0), project.getChild("properties"));
                    for (var config : configurations) if (!settings.equals(options(config, project.getChild("properties")))) settings.setProperty("configurationError", "ambiguous execution settings");
                    settings.setProperty("compilerVersion", value(plugin, "version"));
                    var inputs = new ArrayList<Path>();
                    for (Path directory = module.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
                        inputs.add(directory.resolve("pom.xml")); inputs.add(directory.resolve(".mvn/maven.config"));
                        if (directory.equals(root.toAbsolutePath().normalize())) break;
                    }
                    var parent = project.getChild("parent");
                    if (parent != null) {
                        Path parentModule = modules.get(value(parent, "groupId") + ":" + value(parent, "artifactId") + ":" + value(parent, "version"));
                        if (parentModule == null) settings.setProperty("configurationError", "external parent requires a build capture");
                        else {
                            Path parentPom = parentModule.toAbsolutePath().normalize().resolve("pom.xml");
                            if (!inputs.contains(parentPom)) inputs.add(parentPom);
                        }
                    }
                    settings.setProperty("buildInput.count", Integer.toString(inputs.size()));
                    for (int i = 0; i < inputs.size(); i++) {
                        settings.setProperty("buildInput.path." + i, inputs.get(i).toString()); settings.setProperty("buildInput.sha256." + i, hash(inputs.get(i)));
                    }
                    write(module.resolve("target/quill-kotlin-" + set + ".properties"), settings);
                }
            }
        }
    }
    private static Properties options(Xpp3Dom config, Xpp3Dom properties) {
        var result = new Properties(); if (config == null) return result;
        for (String key : List.of("languageVersion", "apiVersion", "jvmTarget")) {
            String value = value(config, key); if (!value.isEmpty()) result.setProperty(key, interpolate(value, properties));
        }
        var args = config.getChild("args"); var arguments = args == null ? new Xpp3Dom[0] : args.getChildren();
        result.setProperty("argument.count", Integer.toString(arguments.length));
        for (int i = 0; i < arguments.length; i++) result.setProperty("argument." + i, interpolate(arguments[i].getValue(), properties));
        var plugins = config.getChild("compilerPlugins");
        if (plugins != null && plugins.getChildCount() > 0) result.setProperty("unsupportedPlugins", "plugin artifacts require a build capture");
        if (result.values().stream().anyMatch(value -> value.toString().contains("${"))) result.setProperty("configurationError", "unresolved effective compiler settings");
        return result;
    }
    private static String interpolate(String value, Xpp3Dom properties) {
        if (value == null) return "";
        if (properties == null) return value;
        for (int attempt = 0; attempt < 10 && value.contains("${"); attempt++) {
            String before = value;
            for (var property : properties.getChildren()) if (property.getValue() != null) value = value.replace("${" + property.getName() + "}", property.getValue());
            if (before.equals(value)) break;
        }
        return value;
    }
    private static String value(Xpp3Dom parent, String name) { var child = parent.getChild(name); return child == null || child.getValue() == null ? "" : child.getValue(); }
    private static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void write(Path path, Properties values) throws IOException {
        if (Files.isRegularFile(path)) {
            try {
                var previous = new Properties(); try (var reader = Files.newBufferedReader(path)) { previous.load(reader); }
                int count = Integer.parseInt(previous.getProperty("buildInput.count", "0"));
                boolean fresh = count > 0 && count <= 1024;
                for (int i = 0; i < count && fresh; i++) fresh = hash(Path.of(previous.getProperty("buildInput.path." + i))).equals(previous.getProperty("buildInput.sha256." + i));
                if (fresh || previous.equals(values)) return;
            } catch (IllegalArgumentException | NullPointerException malformed) {
                // Initialization repairs malformed generated metadata from an interrupted/older export.
            }
        }
        Files.createDirectories(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), ".kotlin-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { values.store(writer, "Quill effective Kotlin compiler settings"); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
