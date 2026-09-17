package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.jandex.Indexer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.fixture.OrderService;
import org.treblereel.mcp.fixture.PaymentService;
import org.treblereel.mcp.fixture.StripePaymentService;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.CandidateDisposition;
import org.treblereel.mcp.model.ResolutionCandidate;
import org.treblereel.mcp.model.ResolutionStatus;
import org.treblereel.mcp.model.ResolutionTrace;

class ProjectInitializerRemapTest {

    static class PlainClass {}

    @Test
    void firstInitDoesNotMistakeManagedGitignoreChangeForConcurrentWorktreeChange(
            @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>sample</artifactId><version>1</version>
                </project>
                """);
        Files.writeString(root.resolve(".gitignore"), "target/\n");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern("pom.xml").addFilepattern(".gitignore").call();
            git.commit().setMessage("initial").setAuthor("Quill Test", "quill@example.test")
                    .setSign(false).call();
        }

        Path classes = Files.createDirectories(root.resolve("target/classes/org/example"));
        try (var bytecode = ProjectInitializerRemapTest.class.getResourceAsStream(
                "ProjectInitializerRemapTest$PlainClass.class")) {
            assertNotNull(bytecode);
            Files.copy(bytecode, classes.resolve("PlainClass.class"));
        }

        assertTrue(ProjectInitializer.initialize(root, false));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".quill/"));
        try (var indexFiles = Files.list(root.resolve(".quill"))) {
            assertTrue(indexFiles.anyMatch(path -> path.getFileName().toString().endsWith(".db")));
        }
    }

    @Test
    void mavenDiscoveryIgnoresOutputsOutsideDeclaredReactor(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <modules><module>included</module></modules>
                </project>
                """);
        Path included = Files.createDirectories(root.resolve("included/target/classes"));
        Files.writeString(root.resolve("included/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>included</artifactId><version>1</version>
                </project>
                """);
        Path stray = Files.createDirectories(root.resolve("not-a-module/target/classes"));
        Files.write(included.resolve("Included.class"), new byte[]{1});
        Files.write(stray.resolve("Stray.class"), new byte[]{1});

        assertEquals(List.of(included), ProjectInitializer.findClassesDirs(root));
    }

    @Test
    void incompleteMavenModelFallsBackToOutputScan(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <modules><module>missing</module></modules>
                </project>
                """);
        Path fallbackOutput = Files.createDirectories(root.resolve("fallback/target/classes"));
        Files.write(fallbackOutput.resolve("Fallback.class"), new byte[]{1});

        assertEquals(List.of(fallbackOutput), ProjectInitializer.findClassesDirs(root));
    }

    @Test
    void failedGradleDiscoveryFallsBackToOutputScan(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("settings.gradle"));
        Path output = Files.createDirectories(root.resolve("fallback/build/classes/java/main"));
        Files.write(output.resolve("Fallback.class"), new byte[] {1});
        if (BuildSystem.isWindows()) {
            Files.writeString(root.resolve("gradlew.bat"), "@exit /b 7\r\n");
        } else {
            Path wrapper = Files.writeString(root.resolve("gradlew"), "#!/bin/sh\nexit 7\n");
            assertTrue(wrapper.toFile().setExecutable(true));
        }

        assertEquals(List.of(output), ProjectInitializer.findClassesDirs(root));
    }

    @Test
    void realExternalCdiResolutionIsSafeToPersist() throws Exception {
        Indexer applicationIndexer = new Indexer();
        applicationIndexer.indexClass(PaymentService.class);
        applicationIndexer.indexClass(OrderService.class);
        Indexer dependencyIndexer = new Indexer();
        dependencyIndexer.indexClass(StripePaymentService.class);

        var resolution = BeanResolver.resolve(
                applicationIndexer.complete(), dependencyIndexer.complete());
        Map<Integer, Integer> applicationClassIds = new HashMap<>();
        for (var entry : resolution.classNameToId().entrySet()) {
            if (entry.getKey().equals(OrderService.class.getName())) {
                applicationClassIds.put(entry.getValue(), 1);
            } else if (entry.getKey().equals(PaymentService.class.getName())) {
                applicationClassIds.put(entry.getValue(), 2);
            }
        }

        var persisted = ProjectInitializer.remapForPersistence(resolution, applicationClassIds);

        assertTrue(persisted.beans().stream()
                .noneMatch(b -> b.beanTypes().contains(StripePaymentService.class.getName())));
        var paymentInjection = persisted.injectionPoints().stream()
                .filter(ip -> ip.targetType().equals(PaymentService.class.getName()))
                .findFirst().orElseThrow();
        assertNull(paymentInjection.resolvedBeanId(),
                "External bean resolution must not leave a dangling bean id");
        assertEquals(org.treblereel.mcp.model.ResolutionStatus.UNKNOWN,
                paymentInjection.resolutionStatus(),
                "A filtered static candidate must become unknown, not unsatisfied");
        assertTrue(persisted.dependencies().stream()
                .noneMatch(d -> d.injectionPointId() != null
                        && d.injectionPointId() == paymentInjection.id()));
    }

    @Test
    void filteredExternalBeanCannotLeaveDanglingIds(@TempDir Path tempDir) {
        var applicationBean = bean(5, 10, "org.acme.OrderService");
        var externalBean = bean(9, 20, "com.vendor.PaymentService");
        var injection = InjectionPointRecord.staticAnalysis(
                7, 5, "FIELD", "com.vendor.PaymentService", List.of("@Default"),
                "paymentService", 9, false, InjectionPointRecord.STATIC_CDI);
        var dependency = new DependencyRecord(10, 20, "CDI_INJECT", 7);

        var resolution = new BeanResolver.ResolutionResult(
                List.of(applicationBean, externalBean), List.of(injection), List.of(dependency),
                Map.of("org.acme.OrderService", 10, "com.vendor.PaymentService", 20), List.of());

        var persisted = ProjectInitializer.remapForPersistence(resolution, Map.of(10, 1));

        assertEquals(1, persisted.beans().size());
        assertEquals(1, persisted.beans().get(0).id());
        assertEquals(1, persisted.injectionPoints().size());
        assertEquals(1, persisted.injectionPoints().get(0).beanId());
        assertNull(persisted.injectionPoints().get(0).resolvedBeanId());
        assertTrue(persisted.dependencies().isEmpty());

        var jdbi = QuillDatabase.create(tempDir.resolve("index.db"));
        IndexWriter.write(jdbi,
                List.of(new ClassRecord(0, "org.acme.OrderService", "CLASS", null,
                        List.of(), null, 0, true, 0)),
                persisted.beans(), persisted.injectionPoints(), persisted.dependencies(), Map.of());
    }

    @Test
    void survivingIdsAndInjectionEdgesAreCompactedTogether() {
        var filteredBean = bean(1, 99, "com.vendor.FilteredBean");
        var applicationBean = bean(4, 10, "org.acme.OrderService");
        var injection = InjectionPointRecord.staticAnalysis(
                12, 4, "FIELD", "org.acme.OrderService", List.of("@Default"),
                "self", 4, false, InjectionPointRecord.STATIC_CDI);
        var dependency = new DependencyRecord(10, 10, "CDI_INJECT", 12);

        var resolution = new BeanResolver.ResolutionResult(
                List.of(filteredBean, applicationBean), List.of(injection), List.of(dependency),
                Map.of("com.vendor.FilteredBean", 99, "org.acme.OrderService", 10), List.of());

        var persisted = ProjectInitializer.remapForPersistence(resolution, Map.of(10, 3));

        assertEquals(1, persisted.beans().get(0).id());
        assertEquals(3, persisted.beans().get(0).classId());
        assertEquals(1, persisted.injectionPoints().get(0).id());
        assertEquals(1, persisted.injectionPoints().get(0).beanId());
        assertEquals(1, persisted.injectionPoints().get(0).resolvedBeanId());
        assertEquals(1, persisted.dependencies().get(0).injectionPointId());
        assertEquals(3, persisted.dependencies().get(0).fromClassId());
        assertEquals(3, persisted.dependencies().get(0).toClassId());
    }

    @Test
    void mixedFrameworkPartsShareOnePersistentIdSpace() {
        var spring = new ProjectInitializer.PersistedResolution(
                List.of(bean(1, 10, "org.acme.SpringService")),
                List.of(InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                        "org.acme.Repository", List.of(), "repository", 1, false,
                        InjectionPointRecord.STATIC_SPRING)),
                List.of(
                        new DependencyRecord(10, 10, "SPRING_INJECT", 1),
                        new DependencyRecord(10, 20, "CLASS_REFERENCE", null)));
        var cdi = new ProjectInitializer.PersistedResolution(
                List.of(bean(1, 20, "org.acme.CdiService")),
                List.of(InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                        "org.acme.Repository", List.of(), "repository", 1, false,
                        InjectionPointRecord.STATIC_CDI)),
                List.of(
                        new DependencyRecord(20, 20, "CDI_INJECT", 1),
                        new DependencyRecord(20, 10, "CLASS_REFERENCE", null)));

        var merged = ProjectInitializer.mergePersistedResolutions(
                List.of(spring, cdi), true);

        assertEquals(List.of(1, 2), merged.beans().stream().map(BeanRecord::id).toList());
        assertEquals(List.of(1, 2), merged.injectionPoints().stream()
                .map(InjectionPointRecord::id).toList());
        assertEquals(2, merged.injectionPoints().get(1).beanId());
        assertEquals(2, merged.injectionPoints().get(1).resolvedBeanId());
        assertEquals(List.of("SPRING_INJECT", "CDI_INJECT"), merged.dependencies().stream()
                .map(DependencyRecord::kind).toList());
        assertEquals(2, merged.dependencies().get(1).injectionPointId());
    }

    @Test
    void resolutionAcrossMultipleModulesRequiresApplicationContext() {
        List<BeanRecord> beans = List.of(
                bean(1, 1, "example.Navigation"),
                bean(2, 2, "example.NavigationGraph"),
                bean(3, 3, "example.GeneratedNavigationGraph"));
        ResolutionTrace trace = new ResolutionTrace(List.of(
                new ResolutionCandidate(2, "example.NavigationGraph", 3,
                        CandidateDisposition.EXCLUDED, "SPECIALIZED_BY",
                        List.of("SPECIALIZATION")),
                new ResolutionCandidate(3, "example.GeneratedNavigationGraph", null,
                        CandidateDisposition.SELECTED, "UNIQUE_ELIGIBLE_CANDIDATE",
                        List.of("SPECIALIZATION"))),
                List.of("TYPE_ASSIGNABILITY", "SPECIALIZATION"), List.of());
        InjectionPointRecord injection = InjectionPointRecord.staticAnalysis(
                1, 1, "FIELD", "example.NavigationGraph", List.of("@Default"),
                "navGraph", 3, false, InjectionPointRecord.STATIC_CDI)
                .withResolution(3, false, trace);
        List<ClassRecord> classes = List.of(
                contextualClass("example.Navigation", "runtime"),
                contextualClass("example.NavigationGraph", "runtime"),
                contextualClass("example.GeneratedNavigationGraph", "applications/one"));

        InjectionPointRecord refined = ProjectInitializer.requireApplicationContext(
                List.of(injection), beans, classes).getFirst();

        assertEquals(ResolutionStatus.CONTEXT_REQUIRED, refined.resolutionStatus());
        assertNull(refined.resolvedBeanId());
        assertEquals("APPLICATION_CONTEXT_REQUIRED", refined.resolutionReason());
        assertTrue(refined.resolutionTrace().unsupportedRules()
                .contains("APPLICATION_RUNTIME_CLASSPATH"));
        assertEquals(CandidateDisposition.ELIGIBLE,
                refined.resolutionTrace().candidates().get(1).disposition());
    }

    private static ClassRecord contextualClass(String name, String module) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                module + "/src/main/java/" + name.replace('.', '/') + ".java",
                1, true, 10, null, "source", "current", module, "main");
    }

    private static BeanRecord bean(int id, int classId, String type) {
        return new BeanRecord(id, classId, "CLASS", "@ApplicationScoped", List.of("@Default"),
                List.of(), false, null, null, null, null, List.of(type));
    }
}
