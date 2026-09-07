package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.fixture.OrderService;
import org.treblereel.mcp.fixture.PaymentService;
import org.treblereel.mcp.fixture.StripePaymentService;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

class ProjectInitializerRemapTest {

    @Test
    void findsMavenAndGradleMainOutputsButSkipsBuildSrcAndTests(@TempDir Path root) throws Exception {
        Path maven = Files.createDirectories(root.resolve("maven/target/classes"));
        Path java = Files.createDirectories(root.resolve("gradle/build/classes/java/main"));
        Path kotlin = Files.createDirectories(root.resolve("gradle/build/classes/kotlin/main"));
        Path tests = Files.createDirectories(root.resolve("gradle/build/classes/java/test"));
        Path buildSrc = Files.createDirectories(root.resolve("buildSrc/build/classes/java/main"));
        for (Path directory : List.of(maven, java, kotlin, tests, buildSrc)) {
            Files.write(directory.resolve("Sample.class"), new byte[]{1});
        }

        assertEquals(Set.of(maven, java, kotlin), Set.copyOf(ProjectInitializer.findClassesDirs(root)));
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
                "External bean resolution must become unsatisfied in the project-only database");
        assertTrue(persisted.dependencies().stream()
                .noneMatch(d -> d.injectionPointId() != null
                        && d.injectionPointId() == paymentInjection.id()));
    }

    @Test
    void filteredExternalBeanCannotLeaveDanglingIds(@TempDir Path tempDir) {
        var applicationBean = bean(5, 10, "org.acme.OrderService");
        var externalBean = bean(9, 20, "com.vendor.PaymentService");
        var injection = new InjectionPointRecord(
                7, 5, "FIELD", "com.vendor.PaymentService", List.of("@Default"),
                "paymentService", 9, false);
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
        var injection = new InjectionPointRecord(
                12, 4, "FIELD", "org.acme.OrderService", List.of("@Default"),
                "self", 4, false);
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

    private static BeanRecord bean(int id, int classId, String type) {
        return new BeanRecord(id, classId, "CLASS", "@ApplicationScoped", List.of("@Default"),
                List.of(), false, null, null, null, null, List.of(type));
    }
}
