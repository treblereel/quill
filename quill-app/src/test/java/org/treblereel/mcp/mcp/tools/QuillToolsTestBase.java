package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.ProjectEntryFixture;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.model.*;

/** Shared index fixture and helpers for the QuillTools tool tests. */
abstract class QuillToolsTestBase {

    static final QuillToolsJdbiReads JDBI_READS = new QuillToolsJdbiReads();

    @org.junit.jupiter.api.extension.RegisterExtension
    final ProjectEntryFixture projectEntries = new ProjectEntryFixture();

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;

    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("test.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "org.acme.OrderService",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/org/acme/OrderService.java",
                                10,
                                true,
                                500),
                        new ClassRecord(
                                0,
                                "org.acme.PaymentService",
                                "INTERFACE",
                                null,
                                List.of(),
                                "src/main/java/org/acme/PaymentService.java",
                                5,
                                false,
                                100),
                        new ClassRecord(
                                0,
                                "org.acme.StripePaymentService",
                                "CLASS",
                                "java.lang.Object",
                                List.of("org.acme.PaymentService"),
                                "src/main/java/org/acme/StripePaymentService.java",
                                8,
                                true,
                                300),
                        new ClassRecord(
                                0,
                                "org.acme.AuditService",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/org/acme/AuditService.java",
                                5,
                                true,
                                200));
        var beans =
                List.of(
                        new BeanRecord(
                                0,
                                1,
                                "CLASS",
                                "@ApplicationScoped",
                                List.of("@Default"),
                                List.of(),
                                false,
                                null,
                                null,
                                null,
                                null,
                                List.of("OrderService", "Object")),
                        new BeanRecord(
                                0,
                                3,
                                "CLASS",
                                "@ApplicationScoped",
                                List.of("@Default", "@Premium"),
                                List.of(),
                                false,
                                null,
                                List.of("prod"),
                                null,
                                null,
                                List.of("PaymentService", "StripePaymentService", "Object")),
                        new BeanRecord(
                                0,
                                4,
                                "CLASS",
                                "@Dependent",
                                List.of("@Default"),
                                List.of(),
                                false,
                                null,
                                List.of("dev", "test"),
                                null,
                                null,
                                List.of("AuditService", "Object")));
        var ips =
                List.of(
                        InjectionPointRecord.staticAnalysis(
                                        0,
                                        1,
                                        "FIELD",
                                        "PaymentService",
                                        List.of("@Default"),
                                        "paymentService",
                                        2,
                                        false,
                                        InjectionPointRecord.STATIC_CDI)
                                .withResolution(
                                        2,
                                        false,
                                        new ResolutionTrace(
                                                List.of(
                                                        new ResolutionCandidate(
                                                                2,
                                                                "org.acme.StripePaymentService",
                                                                CandidateDisposition.SELECTED,
                                                                ResolutionReason
                                                                        .UNIQUE_ELIGIBLE_CANDIDATE,
                                                                List.of(
                                                                        ResolutionRule
                                                                                .TYPE_ASSIGNABILITY,
                                                                        ResolutionRule
                                                                                .QUALIFIER_MATCHING))),
                                                List.of(
                                                        ResolutionRule.TYPE_ASSIGNABILITY,
                                                        ResolutionRule.QUALIFIER_MATCHING),
                                                List.of(ResolutionRule.RUNTIME_CDI_EXTENSIONS))),
                        InjectionPointRecord.staticAnalysis(
                                0,
                                1,
                                "FIELD",
                                "RuntimeProvidedService",
                                List.of("@Default"),
                                "runtimeProvidedService",
                                null,
                                false,
                                InjectionPointRecord.STATIC_CDI));
        var deps =
                List.of(
                        new DependencyRecord(1, 3, "CDI_INJECT", 1),
                        new DependencyRecord(3, 4, "CONSTRUCTS", null, 1, List.of(42)));
        IndexWriter.writeIncremental(
                jdbi,
                new IndexFixture()
                        .classes(classes)
                        .beans(beans)
                        .injectionPoints(ips)
                        .dependencies(deps)
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-08-26T14:30:00",
                                        "last_commit",
                                        "abc1234",
                                        "dependency_index",
                                        "degraded",
                                        "dependency_index_detail",
                                        "1/2 modules resolved",
                                        "git_scanned_commits",
                                        "3",
                                        "git_repository_commits",
                                        "3",
                                        "git_history_complete",
                                        "true",
                                        "module_discovery_scope",
                                        "maven_reactor",
                                        "module_discovery_complete",
                                        "true",
                                        "service_registrations_detail",
                                        """
                                [{"serviceType":"javax.annotation.processing.Processor",
                                  "providerType":"org.acme.FirstProcessor",
                                  "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":1},
                                 {"serviceType":"javax.annotation.processing.Processor",
                                  "providerType":"org.acme.SecondProcessor",
                                  "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":3}]
                                """))
                        .snapshot());

        var gitCommits =
                List.of(
                        new GitCommitRecord(
                                1,
                                "aaa1111aaa1111aaa1111aaa1111aaa1111aaa111",
                                "aaa1111",
                                "dev1",
                                "dev1@test.com",
                                "2026-08-28T10:00:00Z",
                                "feat: add order service"),
                        new GitCommitRecord(
                                2,
                                "bbb2222bbb2222bbb2222bbb2222bbb2222bbb222",
                                "bbb2222",
                                "dev2",
                                "dev2@test.com",
                                "2026-08-27T09:00:00Z",
                                "fix: payment edge case"),
                        new GitCommitRecord(
                                3,
                                "ccc3333ccc3333ccc3333ccc3333ccc3333ccc333",
                                "ccc3333",
                                "dev1",
                                "dev1-alias@test.com",
                                "2026-08-26T08:00:00Z",
                                "initial commit"));
        var gitFiles =
                List.of(
                        new GitCommitFile(
                                1, 1, "src/main/java/org/acme/OrderService.java", "MODIFY"),
                        new GitCommitFile(
                                1, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                        new GitCommitFile(
                                2, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                        new GitCommitFile(3, 1, "src/main/java/org/acme/OrderService.java", "ADD"),
                        new GitCommitFile(
                                3, 2, "src/main/java/org/acme/PaymentService.java", "ADD"),
                        new GitCommitFile(
                                3, 3, "src/main/java/org/acme/StripePaymentService.java", "ADD"));
        var gitStats =
                List.of(
                        new GitFileStats(
                                1,
                                "src/main/java/org/acme/OrderService.java",
                                1,
                                2,
                                "2026-08-28T10:00:00Z",
                                "dev1",
                                "2026-08-26T08:00:00Z",
                                1),
                        new GitFileStats(
                                2,
                                "src/main/java/org/acme/StripePaymentService.java",
                                3,
                                3,
                                "2026-08-28T10:00:00Z",
                                "dev1",
                                "2026-08-26T08:00:00Z",
                                2),
                        new GitFileStats(
                                3,
                                "src/main/java/org/acme/PaymentService.java",
                                2,
                                1,
                                "2026-08-26T08:00:00Z",
                                "dev1",
                                "2026-08-26T08:00:00Z",
                                1));
        IndexFixture.writeGitData(jdbi, gitStats, gitCommits, gitFiles);

        var externalDeps =
                List.of(
                        new ExternalDepRecord(
                                1, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                        new ExternalDepRecord(1, "jakarta.inject.Inject", "ANNOTATION"),
                        new ExternalDepRecord(
                                3, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                        new ExternalDepRecord(3, "com.stripe.Stripe", "FIELD"),
                        new ExternalDepRecord(3, "com.stripe.model.Charge", "METHOD"),
                        new ExternalDepRecord(
                                4, "jakarta.enterprise.context.Dependent", "ANNOTATION"));
        IndexFixture.writeExternalDeps(jdbi, externalDeps);
    }

    void addOrderTests(int start, int count) {
        jdbi.useHandle(
                handle -> {
                    for (int index = start; index < start + count; index++) {
                        int id =
                                handle.createUpdate(
                                                """
                        INSERT INTO classes (class_name, kind, source_file, source_line,
                                             source_set, origin, lifecycle)
                        VALUES (:name, 'CLASS', :source, 1, 'test', 'source', 'current')
                        """)
                                        .bind("name", "org.acme.OrderTest" + index)
                                        .bind(
                                                "source",
                                                "src/test/java/org/acme/OrderTest"
                                                        + index
                                                        + ".java")
                                        .executeAndReturnGeneratedKeys("id")
                                        .mapTo(Integer.class)
                                        .one();
                        handle.createUpdate(
                                        """
                        INSERT INTO dependencies (from_class_id, to_class_id, kind)
                        VALUES (:from, 1, 'CALLS')
                        """)
                                .bind("from", id)
                                .execute();
                    }
                });
    }

    void writeMinimalPom() throws Exception {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>verification-fixture</artifactId>
                  <version>1</version>
                </project>
                """);
    }

    static JsonNode findAction(JsonNode result, String action) {
        return result.path("next_actions")
                .valueStream()
                .filter(candidate -> action.equals(candidate.path("action").asText()))
                .findFirst()
                .orElseThrow();
    }

    Jdbi relatedTargetsFixture(int size, List<DependencyRecord> dependencies) {
        var names =
                List.of(
                        "OrderService",
                        "PaymentService",
                        "StripePaymentService",
                        "AuditService",
                        "Related5",
                        "Related6");
        var classes = new java.util.ArrayList<ClassRecord>();
        for (int id = 1; id <= size; id++) {
            String name = id <= names.size() ? names.get(id - 1) : "Related" + id;
            classes.add(
                    new ClassRecord(
                            id,
                            "org.acme." + name,
                            "CLASS",
                            null,
                            List.of(),
                            (id == size ? "src/test/" : "src/main/") + name + ".java",
                            1,
                            false,
                            10,
                            null,
                            "source",
                            "current",
                            ".",
                            id == size ? "test" : "main"));
        }
        var database = QuillDatabase.create(tempDir.resolve("related-targets.db"));
        IndexWriter.writeFresh(
                database,
                new IndexFixture().classes(classes).dependencies(dependencies).snapshot());
        return database;
    }

    ProjectRegistry singleProjectRegistry(String name) {
        return new ProjectRegistry() {
            @Override
            public Resolution resolve() {
                return new Resolution(
                        List.of(projectEntries.entry(name, tempDir, jdbi)), List.of());
            }

            @Override
            public Resolution resolve(String selector) {
                return name.equalsIgnoreCase(selector)
                        ? resolve()
                        : new Resolution(List.of(), List.of());
            }

            @Override
            public List<String> configuredProjectNames() {
                return List.of(name);
            }
        };
    }

    static String relationClass(JsonNode page) {
        JsonNode outbound = page.get("depends_on");
        if (outbound != null && !outbound.isEmpty()) return outbound.get(0).get("class").asText();
        return page.get("depended_by").get(0).get("class").asText();
    }
}
