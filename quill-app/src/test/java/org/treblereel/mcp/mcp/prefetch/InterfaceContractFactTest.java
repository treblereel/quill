package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-043: asked what an interface contract change touches, the bean list (alphabetical)
 * opened with two test configurations and the budget left three of seven, cutting the production
 * providers; callers repeated their package and called method per class, so 12 of 25 fit and
 * Codex fetched the rest with find_usages (20 KB, twice).
 */
class InterfaceContractFactTest {
    @TempDir Path root;

    @Test
    void productionBeansComeFirstAndCallersAreGroupedByPackageAndMethod() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package p; interface Holder { String user(); } "
                + "class Impl implements Holder { public String user() { return \"\"; } } "
                + "class AaTestConfig {} class ZConfig {} "
                + "class Web1 { Holder h; String a() { return h.user(); } } "
                + "class Web2 { Holder h; String b() { return h.user(); } }");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
            h.execute("UPDATE classes SET source_set = 'test' WHERE class_name = 'p.AaTestConfig'");
            for (String owner : new String[] {"p.AaTestConfig", "p.ZConfig"})
                h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) "
                        + "SELECT id, 'PRODUCER_METHOD', 'singleton', '[]', '[\"p.Holder\"]', id, 'holder', 'spring' FROM classes WHERE class_name = ?", owner);
        });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(db, reader -> {
            ClassPrefetchRelations.append(reader, fact, reader.symbols.findClassByName("p.Holder").orElseThrow());
            return null;
        });
        var beans = fact.path("contract_beans");
        assertTrue(beans.get(0).asText().startsWith("p.ZConfig#holder"), beans.toString());
        assertTrue(beans.get(1).asText().startsWith("test sources only: p.AaTestConfig#holder"), beans.toString());
        assertEquals("p: Web1, Web2 call user()", fact.path("contract_callers").get(0).asText(), fact.toString());
        assertEquals(2, fact.path("contract_caller_classes").asInt(), fact.toString());
    }
}
