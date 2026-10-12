package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Exports the resolved configuration of a successful compiler execution, without invoking a compiler. */
final class JavaCompilerCapture {
    static void capture(ExecutionEvent event) throws Exception {
        var mojo = event.getMojoExecution();
        if (mojo == null || !"org.apache.maven.plugins".equals(mojo.getGroupId())
                || !"maven-compiler-plugin".equals(mojo.getArtifactId())
                || !("compile".equals(mojo.getGoal()) || "testCompile".equals(mojo.getGoal()))) return;
        var project = event.getProject();
        if (project == null || project.getBasedir() == null || event.getSession() == null) return;
        boolean test = "testCompile".equals(mojo.getGoal());
        var config = mojo.getConfiguration() == null ? new Xpp3Dom("configuration") : new Xpp3Dom(mojo.getConfiguration());
        var evaluator = new PluginParameterExpressionEvaluator(event.getSession(), mojo);
        resolve(config, evaluator);
        Properties values = settings(config, test);
        values.setProperty("buildTool", "maven");
        values.setProperty("sourceSet", test ? "test" : "main");
        values.setProperty("execution", mojo.getExecutionId() == null ? "" : mojo.getExecutionId());
        values.setProperty("compilerPluginVersion", mojo.getVersion());
        values.setProperty("evidence", "successful_mojo");
        Path module = project.getBasedir().toPath().toAbsolutePath().normalize();
        String executable = value(config, "executable");
        Toolchain toolchain = null;
        if (executable == null || !Boolean.parseBoolean(value(config, "fork"))) {
            var manager = event.getSession().getContainer().lookup(ToolchainManager.class);
            var requirements = config.getChild("jdkToolchain");
            if (requirements != null) {
                var wanted = new LinkedHashMap<String, String>();
                for (var child : requirements.getChildren()) if (child.getValue() != null) wanted.put(child.getName(), child.getValue());
                var matches = manager.getToolchains(event.getSession(), "jdk", wanted);
                if (!matches.isEmpty()) toolchain = matches.get(0);
                else values.setProperty("jdkSelection", "requested_toolchain_unavailable");
            } else toolchain = manager.getToolchainFromBuildContext("jdk", event.getSession());
            if (toolchain != null) executable = toolchain.findTool("javac");
            else executable = null;
        }
        String selection = toolchain != null ? "toolchain" : executable != null ? "explicit_executable" : "maven_jvm";
        if (!values.containsKey("jdkSelection")) values.setProperty("jdkSelection", selection);
        Path home = executable == null ? Path.of(System.getProperty("java.home")) : module.resolve(executable).normalize().getParent();
        if (executable != null && home != null) home = home.getParent();
        if (executable != null) values.setProperty("compilerExecutable", module.resolve(executable).normalize().toString());
        if (home != null && Files.isRegularFile(home.resolve("release"))) {
            values.setProperty("jdkHome", home.toAbsolutePath().normalize().toString());
            var release = new Properties(); try (var reader = Files.newBufferedReader(home.resolve("release"))) { release.load(reader); }
            String version = release.getProperty("JAVA_VERSION");
            if (version != null) values.setProperty("jdkVersion", version.replace("\"", ""));
        } else values.setProperty("jdkSelection", "executable_jdk_unavailable");
        put(values, "sourceRoot", test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots());
        put(values, "compileClasspath", test ? project.getTestClasspathElements() : project.getCompileClasspathElements());
        put(values, "activeProfile", project.getActiveProfiles().stream().map(org.apache.maven.model.Profile::getId).toList());
        var inputs = new LinkedHashSet<Path>();
        Path reactor = event.getSession().getRequest().getMultiModuleProjectDirectory() == null ? module
                : event.getSession().getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize();
        for (Path directory = module; directory != null; directory = directory.getParent()) {
            for (String name : List.of("pom.xml", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml")) inputs.add(directory.resolve(name));
            if (directory.equals(reactor)) break;
        }
        for (var parent = project.getParent(); parent != null; parent = parent.getParent()) if (parent.getFile() != null) inputs.add(parent.getFile().toPath());
        var request = event.getSession().getRequest();
        for (var file : new java.io.File[]{request.getUserSettingsFile(), request.getGlobalSettingsFile(), request.getUserToolchainsFile(), request.getGlobalToolchainsFile()})
            if (file != null) inputs.add(file.toPath());
        if (home != null) inputs.add(home.resolve("release"));
        for (String source : test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots()) inputs.add(Path.of(source).resolve("module-info.java"));
        fingerprints(values, inputs);
        KotlinCompilerCapture.write(module.resolve("target/quill-java-" + (test ? "test" : "main") + ".properties"), values);
    }

    static Properties settings(Xpp3Dom config, boolean test) {
        var values = new Properties();
        for (String key : List.of("source", "target", "release")) {
            String selected = test ? value(config, "test" + Character.toUpperCase(key.charAt(0)) + key.substring(1)) : null;
            if (selected == null) selected = value(config, key);
            if (selected != null) values.setProperty(key, selected);
        }
        for (String key : List.of("compilerId", "fork", "enablePreview", "proc", "parameters", "encoding", "useModulePath")) {
            String selected = value(config, key); if (selected != null) values.setProperty(key, selected);
        }
        String generated = value(config, test ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory");
        if (generated != null) values.setProperty("generatedSourcesDirectory", generated);
        var arguments = new ArrayList<String>();
        var args = config.getChild("compilerArgs");
        if (args != null) for (var arg : args.getChildren()) if (arg.getValue() != null) arguments.add(arg.getValue());
        var legacy = config.getChild("compilerArguments");
        if (legacy != null) for (var arg : legacy.getChildren()) {
            arguments.add("-" + arg.getName());
            if (arg.getValue() != null && !arg.getValue().isEmpty()) arguments.add(arg.getValue());
        }
        String raw = value(config, "compilerArgument");
        if (raw != null) values.setProperty("legacyCompilerArgument", raw);
        if (arguments.contains("--enable-preview")) values.setProperty("enablePreview", "true");
        put(values, "argument", arguments);
        var processors = config.getChild("annotationProcessors");
        put(values, "processor", processors == null ? List.of() : Arrays.stream(processors.getChildren()).map(Xpp3Dom::getValue).filter(Objects::nonNull).toList());
        var paths = config.getChild("annotationProcessorPaths");
        var coordinates = new ArrayList<String>();
        if (paths != null) for (var path : paths.getChildren()) {
            String coordinate = String.join(":", Objects.toString(value(path, "groupId"), ""), Objects.toString(value(path, "artifactId"), ""),
                    Objects.toString(value(path, "version"), ""), Objects.toString(value(path, "classifier"), ""));
            coordinates.add(coordinate);
        }
        put(values, "processorArtifact", coordinates);
        values.setProperty("processorPathEvidence", paths == null ? "compiler_default_or_arguments" : "declared_coordinates");
        values.setProperty("modulePathEvidence", "compiler_inference_or_arguments");
        return values;
    }

    private static void resolve(Xpp3Dom node, PluginParameterExpressionEvaluator evaluator) throws Exception {
        String expression = node.getValue();
        Object evaluated = expression == null ? null : evaluator.evaluate(expression);
        if (evaluated == null && node.getAttribute("default-value") != null) evaluated = evaluator.evaluate(node.getAttribute("default-value"));
        node.setValue(evaluated == null || evaluated.toString().contains("${") ? null : evaluated.toString());
        for (var child : node.getChildren()) resolve(child, evaluator);
    }
    private static String value(Xpp3Dom config, String name) { var child = config.getChild(name); return child == null ? null : child.getValue(); }
    static void put(Properties values, String prefix, List<String> items) {
        values.setProperty(prefix + ".count", Integer.toString(items.size()));
        for (int i = 0; i < items.size(); i++) values.setProperty(prefix + "." + i, items.get(i));
    }
    static void fingerprints(Properties values, Collection<Path> inputs) throws IOException {
        values.setProperty("buildInput.count", Integer.toString(inputs.size())); int index = 0;
        for (Path path : inputs) {
            values.setProperty("buildInput.path." + index, path.toAbsolutePath().normalize().toString());
            values.setProperty("buildInput.sha256." + index++, hash(path));
        }
    }
    static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "absent";
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
