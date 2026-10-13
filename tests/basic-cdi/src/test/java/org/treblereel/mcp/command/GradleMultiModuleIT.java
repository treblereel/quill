package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class GradleMultiModuleIT {

    private static final Path PROJECT_FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-multimodule");
    private static final Path SPRING_PROJECT_FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-spring");

    @TempDir Path tempDir;

    @Test
    void indexesCrossModuleCdiInjection() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("gradle-multimodule"));
        runGradleCleanAndBuild(project);

        InitCommand command = new InitCommand();
        command.projectPath = project;
        command.indexOnly = true;
        command.call();

        Path db = ProjectIndexStore.findDbForHead(project);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        assertEquals(4, IndexReader.findAllClasses(jdbi).size());
        assertTrue(IndexReader.findAllClasses(jdbi).stream()
                .allMatch(record -> record.sourceTokens() > 0));
        assertEquals(2, IndexReader.findBeans(jdbi, null).size());

        var userClass = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.gradle.UserService").orElseThrow();
        var userBean = IndexReader.findBeanByClassId(jdbi, userClass.id()).orElseThrow();
        var injection = IndexReader.findInjectionPoints(jdbi, userBean.id()).getFirst();
        assertNotNull(injection.resolvedBeanId(), "Cross-module injection should resolve");

        assertTrue(Files.isRegularFile(project.resolve("common/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("service/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(
                project.resolve("modules/custom/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("build/quill-projects.tsv")));
        assertTrue(Files.isRegularFile(project.resolve("build/quill-projects.sha256")));
    }

    @Test
    void discoversEvaluatedProjectsAndIgnoresStrayBuildOutput() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("gradle-multimodule"));
        runGradleClean(project);
        Path stray = Files.createDirectories(
                project.resolve("retired/build/classes/java/main"));
        Files.write(stray.resolve("Stale.class"), new byte[] {1, 2, 3});

        GradleProjectDiscovery.Discovery discovery =
                GradleProjectDiscovery.discover(project);

        assertTrue(discovery.complete());
        assertEquals(Set.of(
                        project.resolve("common").toAbsolutePath(),
                        project.resolve("service").toAbsolutePath(),
                        project.resolve("modules/custom").toAbsolutePath()),
                Set.copyOf(discovery.moduleDirectories()));
        assertTrue(discovery.classesDirectories().contains(
                project.resolve("out/custom/classes/java/main").toAbsolutePath()));
        assertFalse(discovery.classesDirectories().stream()
                .anyMatch(path -> path.startsWith(project.resolve("retired"))));
        assertFalse(Files.exists(project.resolve("common/build/quill-classpath.txt")),
                "Read-only discovery must not modify the classpath cache");

        GradleProjectDiscovery.Discovery pinned =
                GradleProjectDiscovery.discover(project.resolve("service"));
        assertEquals(Set.of(project.resolve("service").toAbsolutePath()),
                Set.copyOf(pinned.moduleDirectories()));
    }

    @Test
    void indexesSpringKotlinProject() throws Exception {
        assumeGradleAvailable();
        Path springProject = TestProjectCopies.copyFixture(
                SPRING_PROJECT_FIXTURE, tempDir.resolve("gradle-spring"));
        runGradleCleanAndBuild(springProject);

        InitCommand command = new InitCommand();
        command.projectPath = springProject;
        command.indexOnly = true;
        command.call();

        Path db = ProjectIndexStore.findDbForHead(springProject);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        var metadata = IndexReader.getMetadata(jdbi);
        assertEquals("Spring", metadata.get("framework"));
        assertTrue(Integer.parseInt(metadata.get("kotlin_metadata_classes")) > 0);
        assertNotNull(metadata.get("kotlin_metadata_fallbacks"));
        assertEquals(2, IndexReader.findBeans(jdbi, null).size());
        assertTrue(IndexReader.findAllClasses(jdbi).stream()
                .allMatch(record -> record.sourceTokens() > 0));

        var serviceClass = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.gradlespring.OrderService").orElseThrow();
        assertTrue(serviceClass.sourceFile().endsWith("OrderService.kt"));
        assertEquals("source", serviceClass.origin());
        assertEquals(serviceClass, IndexReader.findClassByPath(jdbi,
                "src/main/kotlin/org/treblereel/mcp/fixture/gradlespring/OrderService.kt")
                .orElseThrow());
        var serviceBean = IndexReader.findBeanByClassId(jdbi, serviceClass.id()).orElseThrow();
        assertNotNull(IndexReader.findInjectionPoints(jdbi, serviceBean.id())
                .getFirst().resolvedBeanId());
        var repository = IndexReader.findKotlinDeclarations(jdbi, serviceClass.id()).stream()
                .filter(value -> value.name().equals("repository"))
                .findFirst().orElseThrow();
        assertTrue(repository.mutable());
        assertTrue(repository.lateinit());

        var functionsClass = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.gradlespring.OrderFunctionsKt").orElseThrow();
        assertTrue(functionsClass.sourceFile().endsWith("OrderFunctions.kt"));
        var functions = IndexReader.findKotlinDeclarations(jdbi, functionsClass.id());
        assertEquals("FILE_FACADE", functions.getFirst().kind());
        assertTrue(functions.stream()
                .filter(value -> value.name().equals("formatOrder"))
                .anyMatch(value -> value.hasDefaultParameters()));
        assertTrue(functions.stream()
                .filter(value -> value.name().equals("loadOrder"))
                .anyMatch(value -> value.isSuspend()));
        int declarationCount = IndexReader.findAllClasses(jdbi).stream()
                .mapToInt(value -> IndexReader.findKotlinDeclarations(jdbi, value.id()).size())
                .sum();
        assertEquals(Integer.toString(declarationCount), metadata.get("kotlin_declarations"));
    }

    @Test
    void discoversProjectsAndExportsClasspathWithConfigurationCacheEnabled() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("configuration cache ü"));
        Path properties = project.resolve("gradle.properties");
        String configured = (Files.exists(properties) ? Files.readString(properties) : "")
                + "\norg.gradle.configuration-cache=true\n"
                + "org.gradle.configuration-cache.problems=fail\n";
        Files.writeString(properties, configured);
        runGradleCleanAndBuild(project);
        Path classes = project.resolve("common/build/classes/java/main");
        Path example;
        try (var files = Files.walk(classes)) {
            example = files.filter(path -> path.toString().endsWith(".class"))
                    .findFirst().orElseThrow();
        }
        byte[] bytecode = Files.readAllBytes(example);

        var readOnly = GradleProjectDiscovery.discover(project);
        assertTrue(readOnly.complete(), "Read-only discovery must bypass configuration cache");
        assertTrue(readOnly.classesDirectories().contains(
                project.resolve("out/custom/classes/java/main").toAbsolutePath()));
        assertFalse(Files.exists(project.resolve("common/build/quill-classpath.txt")));

        var exported = GradleProjectDiscovery.discoverAndWriteClasspath(project);
        assertTrue(exported.complete(), "Classpath export must bypass configuration cache");
        assertEquals(readOnly.moduleDirectories(), exported.moduleDirectories());
        assertTrue(Files.isRegularFile(project.resolve("common/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("service/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("build/quill-projects.tsv")));
        assertTrue(GradleProjectDiscovery.discover(project).complete(), "Cached discovery must remain usable");
        assertEquals(configured, Files.readString(properties), "Normal build cache preference is preserved");
        assertArrayEquals(bytecode, Files.readAllBytes(example), "Discovery must not compile the project");
    }

    @Test
    void indexesCustomSourceSetsWithRelocatedSourcesOutputsAndIsolatedDependencies() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(PROJECT_FIXTURE, tempDir.resolve("custom sets ü"));
        Path build = project.resolve("service/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + """

                val integrationTest by sourceSets.creating {
                    java.setSrcDirs(listOf("custom sources ü/java"))
                    compileClasspath += sourceSets.main.get().output
                    runtimeClasspath += output + compileClasspath
                }
                apply(plugin = "java-test-fixtures")
                dependencies {
                    add(integrationTest.implementationConfigurationName, "org.apache.commons:commons-lang3:3.17.0")
                }
                tasks.named<JavaCompile>(integrationTest.compileJavaTaskName) {
                    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out/service/integration"))
                    options.release.set(17)
                }
                """);
        Path source = project.resolve("service/custom sources ü/java/custom/IntegrationProbe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package custom; public class IntegrationProbe { org.apache.commons.lang3.StringUtils marker; }");
        Path fixture = project.resolve("service/src/testFixtures/java/custom/Fixture.java");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, "package custom; public class Fixture {}");
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(project,
                "clean", "classes", ":service:integrationTestClasses", ":service:testFixturesClasses", "--quiet"))
                .directory(project.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor());
        var discovery = GradleProjectDiscovery.discoverAndWriteClasspath(project);
        assertTrue(discovery.complete());
        assertEquals("integrationTest", discovery.classDirectorySourceSets().get(project.resolve("out/service/integration")));
        assertTrue(discovery.sourceRoots().contains(source.getParent().getParent()));
        assertTrue(Files.readString(project.resolve("service/build/quill-source-set-classpath.txt"))
                .contains("commons-lang3-3.17.0.jar"), "Dependencies unique to the custom set must be indexed");
        assertFalse(Files.readString(project.resolve("service/build/quill-classpath.txt"))
                .contains("commons-lang3-3.17.0.jar"), "Custom dependencies must not leak into main");
        var settings = new java.util.Properties();
        try (var reader = Files.newBufferedReader(project.resolve("service/build/quill-java-integrationTest.properties"))) {
            settings.load(reader);
        }
        assertEquals("17", settings.getProperty("release"));
        assertEquals("integrationTest", settings.getProperty("sourceSet"));
        assertEquals("configured_task", settings.getProperty("evidence"), "Discovery must not claim it executed compilation");
        InitCommand command = new InitCommand(); command.projectPath = project; command.indexOnly = true; command.call();
        var db = QuillDatabase.open(ProjectIndexStore.findDbForHead(project));
        var probe = IndexReader.findClassByName(db, "custom.IntegrationProbe").orElseThrow();
        assertTrue(probe.sourceTokens() > 0);
        var occurrence = IndexReader.findClassOccurrencesByClassIds(db, java.util.List.of(probe.id())).get(probe.id()).getFirst();
        assertEquals("service", occurrence.module());
        assertEquals("integrationTest", occurrence.sourceSet());
        assertEquals("service/custom sources ü/java/custom/IntegrationProbe.java", occurrence.sourceFile());
        assertEquals("integrationTest", IndexReader.findFileByPath(db,
                "service/custom sources ü/java/custom/IntegrationProbe.java").orElseThrow().sourceSet());
        var fixtureClass = IndexReader.findClassByName(db, "custom.Fixture").orElseThrow();
        assertEquals("testFixtures", IndexReader.findClassOccurrencesByClassIds(db,
                java.util.List.of(fixtureClass.id())).get(fixtureClass.id()).getFirst().sourceSet());
    }

    @Test
    void indexesProjectWithOnlyCustomCompiledOutputs() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(PROJECT_FIXTURE, tempDir.resolve("custom only"));
        Path build = project.resolve("service/build.gradle.kts");
        Files.writeString(build, Files.readString(build) + "\nval isolated by sourceSets.creating\n");
        Path source = project.resolve("service/src/isolated/java/custom/Only.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "package custom; public class Only {}");
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(project, "clean", ":service:isolatedClasses", "--quiet"))
                .directory(project.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor());
        assertTrue(ProjectInitializer.findMainClassesDirs(project).isEmpty());
        var result = ProjectInitializer.initializeDetailed(project, true);
        assertTrue(result.successful(), result.message());
        var db = QuillDatabase.open(ProjectIndexStore.findDbForHead(project));
        assertEquals("isolated", IndexReader.findClassByName(db, "custom.Only").orElseThrow().sourceSet());
        assertEquals("0", IndexReader.getMetadata(db).get("compiled_main_output_count"));
        assertEquals("1", IndexReader.getMetadata(db).get("compiled_additional_output_count"));
        assertEquals(1, ProjectInitializer.findIndexableClassesDirs(project).size());
        assertEquals("PASS", DoctorCommand.inspect(project).checks().stream()
                .filter(check -> check.id().equals("compiled_outputs")).findFirst().orElseThrow().status().name());
        var repository = new org.treblereel.mcp.workspace.WorkspaceDiscovery.Repository("custom", project, ".");
        assertTrue(WorkspaceRepositoryStatusInspector.inspect(repository).compiled());
    }

    @Test
    void indexesKotlinTaskOverrideOutsideConfiguredSourceSetRoots() throws Exception {
        indexesKotlinTaskOverride(false);
    }

    @Test
    void indexesSingleFileKotlinTaskOverrideOutsideConfiguredRoots() throws Exception {
        indexesKotlinTaskOverride(true);
    }

    private void indexesKotlinTaskOverride(boolean singleFile) throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(SPRING_PROJECT_FIXTURE, tempDir.resolve("kotlin custom"));
        Path build = project.resolve("build.gradle.kts");
        Files.writeString(build, Files.readString(build) + """

                val integrationTest by sourceSets.creating
                tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIntegrationTestKotlin") {
                    setSource(%s)
                }
                """.formatted(singleFile ? "file(\"override sources ü/custom/Probe.kt\")"
                        : "fileTree(\"override sources ü\") { include(\"**/*.kt\"); exclude(\"**/Ignored.kt\") }"));
        Path source = project.resolve("override sources ü/custom/Probe.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package custom\nclass Probe { fun value() = 42 }");
        Files.writeString(source.getParent().resolve("Ignored.kt"), "invalid excluded Kotlin");
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(project, "clean", "classes", "integrationTestClasses", "--quiet"))
                .directory(project.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor());
        var result = ProjectInitializer.initializeDetailed(project, true);
        assertTrue(result.successful(), result.message());
        var settings = new java.util.Properties();
        try (var reader = Files.newBufferedReader(project.resolve("build/quill-kotlin-integrationTest.properties"))) { settings.load(reader); }
        assertEquals("1", settings.getProperty("analysisInputsProtocol"));
        assertNull(settings.getProperty("configurationError"), settings.toString());
        String kotlinRoot = (singleFile ? source : source.getParent().getParent()).toRealPath().toString();
        Path inventoryRoot = singleFile ? source.getParent() : source.getParent().getParent();
        var selection = new com.fasterxml.jackson.databind.ObjectMapper().readTree(settings.getProperty("sourceSelection"));
        assertTrue(selection.path("entries").findValuesAsText("root").contains(kotlinRoot), selection.toString());
        assertTrue(org.treblereel.mcp.core.GradleProjectDiscovery.discover(project).sourceRoots().stream()
                .anyMatch(path -> path.toAbsolutePath().normalize().toString().equals(inventoryRoot.toAbsolutePath().normalize().toString())));
        assertTrue(Integer.parseInt(settings.getProperty("compileClasspath.count")) > 0);
        var db = QuillDatabase.open(ProjectIndexStore.findDbForHead(project));
        var probe = IndexReader.findClassByName(db, "custom.Probe").orElseThrow();
        assertEquals("integrationTest", probe.sourceSet());
        assertEquals("override sources ü/custom/Probe.kt", probe.sourceFile());
        assertTrue(probe.sourceTokens() > 0);
        assertFalse(IndexReader.findKotlinDeclarations(db, probe.id()).isEmpty());
        assertEquals("integrationTest", IndexReader.findFileByPath(db, probe.sourceFile()).orElseThrow().sourceSet());
    }

    private static void assumeGradleAvailable() throws Exception {
        Process process = new ProcessBuilder(
                BuildSystem.GRADLE.command(PROJECT_FIXTURE, "--version"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                "Gradle executable is required for Gradle integration tests");
    }

    private static void runGradleClean(Path project) throws Exception {
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(project, "clean", "--quiet"))
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor(), "Gradle clean must succeed");
    }

    private static void runGradleCleanAndBuild(Path project) throws Exception {
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(
                project, "clean", "classes", "--quiet"))
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor(), "Gradle fixture build must succeed");
    }
}
