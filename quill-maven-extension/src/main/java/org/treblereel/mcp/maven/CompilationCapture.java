package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.execution.ExecutionEvent;

/** Confirms the actual inputs of a successful compiler execution, including no-op compiles. */
final class CompilationCapture {
    private static final Map<String, Properties> STARTED = new ConcurrentHashMap<>();
    // Maven events resolve getProject() through a mutable session.currentProject.
    private record CapturedExecution(org.apache.maven.project.MavenProject project,
            org.apache.maven.execution.MavenSession session, org.apache.maven.plugin.MojoExecution mojo) implements ExecutionEvent {
        public Type getType() { return Type.MojoSucceeded; }
        public org.apache.maven.project.MavenProject getProject() { return project; }
        public org.apache.maven.execution.MavenSession getSession() { return session; }
        public org.apache.maven.plugin.MojoExecution getMojoExecution() { return mojo; }
        public Exception getException() { return null; }
    }
    private record Pending(ExecutionEvent event, Properties settings, Properties inputs, boolean stable) {}
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    static boolean compiler(ExecutionEvent event) {
        var mojo = event.getMojoExecution();
        return mojo != null && event.getProject() != null && event.getProject().getBasedir() != null
                && event.getSession() != null
                && ((mojo.getArtifactId().equals("maven-compiler-plugin") && Set.of("compile", "testCompile").contains(mojo.getGoal()))
                || (mojo.getArtifactId().equals("kotlin-maven-plugin") && Set.of("compile", "test-compile").contains(mojo.getGoal())));
    }

    private static String key(ExecutionEvent event) {
        return event.getProject().getBasedir() + ":" + event.getMojoExecution().getArtifactId() + ":" + event.getMojoExecution().getGoal()
                + ":" + event.getMojoExecution().getExecutionId();
    }

    static void started(ExecutionEvent event) throws IOException {
        if (compiler(event)) STARTED.put(key(event), inputs(event));
    }

    static void clear() { STARTED.clear(); PENDING.clear(); }

    static Set<String> languages(ExecutionEvent event) throws IOException {
        var project = event.getProject();
        boolean test = event.getMojoExecution().getGoal().toLowerCase(Locale.ROOT).contains("test");
        var roots = new ArrayList<Path>();
        (test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots()).forEach(path -> roots.add(Path.of(path)));
        roots.add(project.getBasedir().toPath().resolve(test ? "src/test/kotlin" : "src/main/kotlin"));
        var result = new TreeSet<String>();
        for (Path root : roots) if (Files.isDirectory(root)) try (var files = Files.walk(root.toRealPath(), FileVisitOption.FOLLOW_LINKS)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                if (path.toString().endsWith(".java")) result.add("java");
                if (path.toString().endsWith(".kt")) result.add("kotlin");
            });
        }
        return result;
    }

    static boolean skipped(ExecutionEvent event) throws Exception {
        var mojo = event.getMojoExecution();
        boolean test = mojo.getGoal().toLowerCase(Locale.ROOT).contains("test");
        String property = test ? "maven.test.skip" : "maven.main.skip";
        var user = event.getSession().getUserProperties();
        for (String name : mojo.getArtifactId().equals("kotlin-maven-plugin") ? List.of(property, "kotlin.compiler.skip") : List.of(property)) {
            String value = user == null ? null : user.getProperty(name);
            if (value == null) value = event.getProject().getProperties().getProperty(name);
            if (value == null) value = System.getProperty(name);
            if (Boolean.parseBoolean(value)) return true;
        }
        var config = mojo.getConfiguration();
        if (config != null) for (String field : List.of(test ? "skip" : "skipMain", "skip")) {
            var node = config.getChild(field);
            if (node != null) {
                String value = node.getValue() == null ? node.getAttribute("default-value") : node.getValue();
                Object evaluated = value == null ? null : new org.apache.maven.plugin.PluginParameterExpressionEvaluator(event.getSession(), mojo).evaluate(value);
                if (Boolean.parseBoolean(String.valueOf(evaluated))) return true;
            }
        }
        return !languages(event).contains(mojo.getArtifactId().equals("kotlin-maven-plugin") ? "kotlin" : "java");
    }

    static void complete(boolean successful) throws IOException {
        try {
            if (!successful) return;
            for (var pending : PENDING.values()) {
                var event = pending.event(); var settings = pending.settings();
                String scope = settings.getProperty("compilationModule") + ":" + settings.getProperty("sourceSet");
                var completed = new TreeSet<String>();
                for (var other : PENDING.values()) if (other.stable()
                        && scope.equals(other.settings().getProperty("compilationModule") + ":" + other.settings().getProperty("sourceSet")))
                    completed.add(other.event().getMojoExecution().getArtifactId().equals("kotlin-maven-plugin") ? "kotlin" : "java");
                boolean matching = pending.stable() && pending.inputs().equals(inputs(event)) && completed.containsAll(languages(event));
                settings.setProperty("compilationEvidence", matching ? "matching" : "inputs_changed_or_partial_compile");
                Path output = Path.of(settings.getProperty("compiledOutput.path"));
                settings.setProperty("compiledOutput.sha256", treeHash(output));
                String language = event.getMojoExecution().getArtifactId().equals("kotlin-maven-plugin") ? "kotlin" : "java";
                Path file = event.getProject().getBasedir().toPath().resolve("target/quill-"+language+"-"+settings.getProperty("sourceSet")+".properties");
                KotlinCompilerCapture.write(file, settings);
            }
        } finally { clear(); }
    }

    static void failed(ExecutionEvent event) { if (compiler(event)) STARTED.remove(key(event)); }

    static void finish(ExecutionEvent event, Properties settings) throws IOException {
        if (!compiler(event)) return;
        Properties before = STARTED.remove(key(event));
        Properties after = inputs(event);
        after.forEach((name, value) -> { if (!name.toString().startsWith("buildInput.")) settings.put(name, value); });
        settings.setProperty("buildTool", "maven");
        settings.setProperty("evidence", "successful_mojo");
        settings.setProperty("compilationProtocol", "1");
        settings.setProperty("compilationEvidence", "awaiting_session_end");
        var project = event.getProject();
        boolean test = event.getMojoExecution().getGoal().toLowerCase(Locale.ROOT).contains("test");
        Path output = Path.of(test ? project.getBuild().getTestOutputDirectory() : project.getBuild().getOutputDirectory()).toAbsolutePath().normalize();
        settings.setProperty("compiledOutput.path", output.toString());
        settings.setProperty("compiledOutput.sha256", treeHash(output));
        settings.setProperty("compiledAt", Long.toString(System.currentTimeMillis()));
        JavaCompilerCapture.put(settings, "compilationLanguage", List.copyOf(languages(event)));
        PENDING.put(key(event), new Pending(new CapturedExecution(event.getProject(), event.getSession(), event.getMojoExecution()), settings, after, before != null && before.equals(after)));
    }

    static Properties inputs(ExecutionEvent event) throws IOException {
        var project = event.getProject();
        Path module = project.getBasedir().toPath().toAbsolutePath().normalize();
        boolean test = event.getMojoExecution().getGoal().toLowerCase(Locale.ROOT).contains("test");
        var roots = new TreeSet<Path>();
        project.getCompileSourceRoots().forEach(path -> roots.add(Path.of(path).toAbsolutePath().normalize()));
        roots.add(module.resolve("src/main/java"));
        roots.add(module.resolve("src/main/kotlin"));
        project.getResources().forEach(resource -> roots.add(module.resolve(resource.getDirectory()).normalize()));
        if (test) {
            project.getTestCompileSourceRoots().forEach(path -> roots.add(Path.of(path).toAbsolutePath().normalize()));
            roots.add(module.resolve("src/test/java"));
            roots.add(module.resolve("src/test/kotlin"));
            project.getTestResources().forEach(resource -> roots.add(module.resolve(resource.getDirectory()).normalize()));
            roots.add(Path.of(project.getBuild().getOutputDirectory()).toAbsolutePath().normalize());
        }
        // Annotation-processor outputs are produced by this compile, not inputs to its start snapshot.
        roots.remove(module.resolve("target/generated-sources/annotations"));
        roots.remove(module.resolve("target/generated-test-sources/test-annotations"));
        var values = new Properties();
        values.setProperty("compilationModule", module.toString());
        values.setProperty("sourceSet", test ? "test" : "main");
        values.setProperty("compilationRoot.count", Integer.toString(roots.size()));
        int i = 0;
        for (Path root : roots) {
            values.setProperty("compilationRoot.path." + i, root.toString());
            values.setProperty("compilationRoot.sha256." + i++, treeHash(root));
        }
        JavaCompilerCapture.put(values, "activeProfile", project.getActiveProfiles().stream().map(org.apache.maven.model.Profile::getId).sorted().toList());
        var configuration = new TreeSet<Path>();
        Path reactor = event.getSession().getRequest().getMultiModuleProjectDirectory() == null ? module
                : event.getSession().getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize();
        for (Path directory = module; directory != null; directory = directory.getParent()) {
            for (String name : List.of("pom.xml", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml")) configuration.add(directory.resolve(name));
            if (directory.equals(reactor)) break;
        }
        for (var parent = project.getParent(); parent != null; parent = parent.getParent())
            if (parent.getFile() != null) configuration.add(parent.getFile().toPath());
        JavaCompilerCapture.fingerprints(values, configuration);
        return values;
    }

    static String treeHash(Path root) throws IOException {
        if (!Files.exists(root)) return "absent";
        if (Files.isRegularFile(root)) return JavaCompilerCapture.hash(root);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            Path directory = root.toRealPath();
            try (var files = Files.walk(directory, FileVisitOption.FOLLOW_LINKS)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(directory.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
