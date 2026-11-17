package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Cryostat CQ-005: "kept: injected into SsarAuthorizer" left open whether the injector itself
 * survives; Codex read HttpSecurityProcessor bytecode (26 KB) to learn that Quarkus keeps the
 * HttpAuthenticationMechanism injecting it.
 */
class ArcRemovalChainTest {
    @TempDir Path root;

    @Test
    void removalFollowsInjectorsUpToTheRootThatKeepsThem() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package x; class Mapper {} class Auth { Mapper mapper; } "
                + "class Mechanism { Auth auth; } class Orphan {} class Lonely { Orphan orphan; }");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
            h.execute("UPDATE classes SET interfaces = '[\"io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism\"]' "
                    + "WHERE class_name = 'x.Mechanism'");
            for (String bean : new String[] {"x.Mapper", "x.Auth", "x.Mechanism", "x.Orphan", "x.Lonely"})
                h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', "
                        + "'@ApplicationScoped', '[\"@Default\"]', json_array(class_name), 'cdi' FROM classes WHERE class_name = ?", bean);
            String[][] points = {{"x.Auth", "mapper", "x.Mapper"}, {"x.Mechanism", "auth", "x.Auth"}, {"x.Lonely", "orphan", "x.Orphan"}};
            for (String[] p : points)
                h.execute("INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, "
                        + "resolution_strategy, resolution_reason, resolution_confidence) SELECT o.id, 'FIELD', ?, '[]', ?, t.id, 'RESOLVED', "
                        + "'STATIC_CDI', 'UNIQUE_ELIGIBLE_CANDIDATE', 'HIGH' FROM beans o JOIN classes oc ON oc.id = o.class_id, "
                        + "beans t JOIN classes tc ON tc.id = t.class_id WHERE oc.class_name = ? AND tc.class_name = ?", p[2], p[1], p[0], p[2]);
        });
        String kept = removal(db, "x.Mapper");
        assertTrue(kept.contains("injector chain: Auth <- Mechanism (Quarkus security root: HttpAuthenticationMechanism)"), kept);
        String orphan = removal(db, "x.Orphan");
        assertTrue(orphan.contains("injector chain: Lonely, which no indexed injector or root keeps"), orphan);
    }

    private static String removal(Jdbi db, String className) throws Exception {
        return TestReads.read(db, reader -> new SymbolToolQueries().getSymbolDetails(reader, className, false, null, 10, 0))
                .path("bean")
                .path("arc_removal")
                .asText();
    }
}
