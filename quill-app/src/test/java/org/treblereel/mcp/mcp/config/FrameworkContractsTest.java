package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.change.ContextQueries;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;

class FrameworkContractsTest {

    @TempDir Path root;

    @AfterEach
    void stop() {
        JavaSemanticQueries.close();
    }

    /**
     * V21 CQ-027: Codex asked get_context for the method, then opened the Narayana interceptor
     * jars.
     */
    @Test
    void methodContextCarriesTheTransactionContract() throws Exception {
        String runtime =
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/jakarta/transaction/Transactional.java",
                                "package jakarta.transaction; "
                                        + runtime
                                        + "public @interface Transactional { TxType value() default TxType.REQUIRED; "
                                        + "enum TxType { REQUIRED, REQUIRES_NEW } }",
                                "src/main/java/p/Fixture.java",
                                """
                        package p;
                        class Fixture {
                            @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
                            void save() {}
                            void plain() {}
                        }
                        """));
        String context =
                TestReads.read(
                                db,
                                reader ->
                                        new ContextQueries()
                                                .getContext(
                                                        reader,
                                                        java.util.List.of(
                                                                "p.Fixture#save",
                                                                "p.Fixture#plain"),
                                                        false,
                                                        10))
                        .toString();
        var contexts = new ObjectMapper().readTree(context).path("contexts");
        String save = contexts.get(0).path("method_context").path("framework_contract").toString();
        assertTrue(save.contains("REQUIRES_NEW: suspends"), save);
        assertTrue(
                save.contains(
                        "quarkus.transaction-manager.default-transaction-timeout: not declared; framework default 60s"),
                save);
        assertFalse(
                contexts.get(1).path("method_context").has("framework_contract"),
                contexts.get(1).toString());
    }

    /**
     * Apollo AQ-035: asked what an ApplicationRunner#run does at startup and in which order, Codex
     * read SpringApplication#callRunners twice (14 KB each) and listed ApplicationRunner subtypes.
     */
    @Test
    void anApplicationRunnerStatesWhenSpringBootCallsItAndItsPeersOrder() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        java.util.Map.of(
                                "src/main/java/org/springframework/boot/ApplicationRunner.java",
                                "package org.springframework.boot; public interface ApplicationRunner { void run(Object args) throws Exception; }",
                                "src/main/java/org/springframework/boot/CommandLineRunner.java",
                                "package org.springframework.boot; public interface CommandLineRunner { void run(String... args) throws Exception; }",
                                "src/main/java/org/springframework/core/annotation/Order.java",
                                "package org.springframework.core.annotation; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Order { int value(); }",
                                "src/main/java/p/Fixture.java",
                                """
                        package p;
                        class Heartbeat implements org.springframework.boot.ApplicationRunner { public void run(Object args) {} }
                        @org.springframework.core.annotation.Order(5)
                        class Clear implements org.springframework.boot.ApplicationRunner { public void run(Object args) {} }
                        class Cli implements org.springframework.boot.CommandLineRunner { public void run(String... args) {} }
                        """));
        db.useHandle(h -> h.execute("UPDATE classes SET source_set = 'main', module = 'app'"));
        var fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    var cls = reader.symbols.findClassByName("p.Heartbeat").orElseThrow();
                    FrameworkContracts.append(
                            reader, fact, cls.id(), "run", "(Ljava/lang/Object;)V");
                    return null;
                });
        String contract = fact.path("framework_contract").toString();
        assertTrue(contract.contains("ApplicationRunner#run: Spring Boot calls it once"), contract);
        assertTrue(
                contract.contains("Clear @Order(5)")
                        && contract.contains("Cli CommandLineRunner, no @Order"),
                contract);
        assertFalse(
                contract.contains("Heartbeat ApplicationRunner"),
                "the method's own class is not its peer: " + contract);
    }
}
