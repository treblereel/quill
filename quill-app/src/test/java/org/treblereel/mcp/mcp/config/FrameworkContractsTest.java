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
}
